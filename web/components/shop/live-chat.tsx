"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { api, newKey } from "@/lib/client";
import { errorMessage } from "@/lib/error-text";
import { money } from "@/lib/format";
import { mediaUrl } from "@/lib/media";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { AgentAvatar, DEFAULT_AGENT_AVATAR, isAvatarId } from "@/components/shop/agent-avatar";

type Product = { name: string; slug: string; priceMinor: number; currency: string; imageUrl?: string; imageMediaBase?: string; imageMediaExt?: string };
type Order = { orderNumber: string; totalMinor: number; currency: string; trackPath?: string; paymentPath?: string; whatsappUrl?: string; whatsappConfirmed?: boolean; test?: boolean };
type Msg = { role: "user" | "assistant"; content: string; mediaUrl?: string | null; audioUrl?: string; products?: Product[]; order?: Order | null };
type Reply = { conversationId: string; reply: string; order?: Order | null; products?: Product[]; heard?: string };
type History = { role: "user" | "assistant"; content: string; mediaUrl?: string | null }[];
type Job = { text: string } | { file: File; caption: string };
type Config = { enabled: boolean; greeting: string; agentName?: string; agentAvatar?: string; media?: boolean };

const KEY = "chatConv";
const SOUND_KEY = "chatSound";
const HELLO_KEY = "chatHello";
const EMOJIS = ["😀", "😂", "😍", "🙏", "👍", "👏", "🎉", "❤️", "🔥", "😊", "🤔", "😢", "😎", "🛍️", "👕", "👗", "👟", "📦", "🚚", "💳", "✅", "⭐", "🎁", "💬"];
const MAX_VOICE_SECONDS = 120;

function conversationId(): string {
  try {
    let id = localStorage.getItem(KEY);
    if (!id) { id = newKey(); localStorage.setItem(KEY, id); }
    return id;
  } catch { return newKey(); }
}

function read(key: string): string | null {
  try { return localStorage.getItem(key); } catch { return null; }
}
function write(key: string, value: string) {
  try { localStorage.setItem(key, value); } catch { /* private window: the choice just is not remembered */ }
}

/** A phone photo is often 5-10 MB: it is shrunk to at most 1600 px (JPEG) before it is uploaded, so it is quick to send and cheap to read. */
async function shrinkImage(file: File): Promise<File> {
  try {
    const bmp = await createImageBitmap(file);
    const scale = Math.min(1, 1600 / Math.max(bmp.width, bmp.height));
    const canvas = document.createElement("canvas");
    canvas.width = Math.max(1, Math.round(bmp.width * scale));
    canvas.height = Math.max(1, Math.round(bmp.height * scale));
    canvas.getContext("2d")?.drawImage(bmp, 0, 0, canvas.width, canvas.height);
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, "image/jpeg", 0.85));
    if (blob && blob.size < file.size) return new File([blob], "photo.jpg", { type: "image/jpeg" });
  } catch { /* a format the browser cannot draw: the original goes as it is */ }
  return file;
}

/** A short "ding" when the assistant answers (the speaker button in the header switches it off). */
function ping() {
  try {
    const Ctx = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!Ctx) return;
    const ctx = new Ctx();
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = "sine";
    osc.frequency.setValueAtTime(880, ctx.currentTime);
    osc.frequency.exponentialRampToValueAtTime(1320, ctx.currentTime + 0.12);
    gain.gain.setValueAtTime(0.0001, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.12, ctx.currentTime + 0.02);
    gain.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + 0.3);
    osc.connect(gain).connect(ctx.destination);
    osc.start();
    osc.stop(ctx.currentTime + 0.32);
    osc.onended = () => void ctx.close();
  } catch { /* no sound is not a problem */ }
}

/** What the chat shows for a customer's photo or voice note that the history stores as the text the assistant read. */
function pretty(content: string, photo: string, voice: string): string {
  const m = content.match(/^\[صورة من العميل:\s*([\s\S]*?)\]/);
  if (m) return `📷 ${photo}: ${m[1]}${content.slice(m[0].length)}`;
  if (content.startsWith("🎤 ")) return `🎤 ${voice}: ${content.slice(3)}`;
  return content;
}

/**
 * The conversation, shared by the storefront chat window and the owner's test screen (same messages, different endpoint). {@code widget} is the storefront look: round avatar, pill
 * suggestions, a pill-shaped input with emoji, photo and voice note. {@code sendMedia} switches the photo and voice buttons on.
 */
export function ChatPanel({ send, sendMedia, load, onReset, onTeach, onReply, greeting, agentAvatar = DEFAULT_AGENT_AVATAR, live = false, widget = false, className = "" }: {
  send: (text: string) => Promise<Reply>; sendMedia?: (file: File, caption: string) => Promise<Reply>;
  load: () => Promise<History>; onReset?: () => Promise<void>; onTeach?: (question: string, answer: string) => void; onReply?: () => void; greeting: string;
  agentAvatar?: string; live?: boolean; widget?: boolean; className?: string;
}) {
  const { t, locale } = useI18n();
  const [msgs, setMsgs] = useState<Msg[]>([]);
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const end = useRef<HTMLDivElement>(null);
  const queue = useRef<Job[]>([]);   // messages typed while the assistant is still answering are sent one after another
  const running = useRef(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const field = useRef<HTMLInputElement>(null);
  const [attach, setAttach] = useState<{ file: File; url: string } | null>(null);
  const [emojis, setEmojis] = useState(false);
  const [recording, setRecording] = useState(false);
  const [seconds, setSeconds] = useState(0);
  const recorder = useRef<MediaRecorder | null>(null);
  const chunks = useRef<Blob[]>([]);
  const cancelled = useRef(false);
  const ticker = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    let live = true;
    load().then((h) => { if (live) setMsgs(h); }).catch(() => undefined);
    return () => { live = false; };
  }, [load]);
  // the storefront chat: a reply the shop team writes from their inbox shows up while the window is open
  useEffect(() => {
    if (!live) return;
    const timer = setInterval(() => {
      if (running.current) return;
      load().then((h) => setMsgs((m) => (h.length > m.length ? h : m))).catch(() => undefined);
    }, 6000);
    return () => clearInterval(timer);
  }, [live, load]);
  useEffect(() => { end.current?.scrollIntoView({ block: "end" }); }, [msgs, busy]);
  // leaving the page while recording: stop the microphone and drop the clip
  useEffect(() => () => {
    cancelled.current = true;
    if (ticker.current) clearInterval(ticker.current);
    if (recorder.current?.state === "recording") recorder.current.stop();
  }, []);

  const drain = async () => {
    if (running.current) return;
    running.current = true;
    setBusy(true);
    while (queue.current.length > 0) {
      const job = queue.current.shift() as Job;
      try {
        const r = "file" in job && sendMedia ? await sendMedia(job.file, job.caption) : await send("text" in job ? job.text : job.caption);
        // an empty reply: the shop team is answering this chat themselves, nothing automatic to show
        if (r.reply && r.reply.trim()) {
          setMsgs((m) => [...m, { role: "assistant", content: r.reply, products: r.products, order: r.order }]);
          onReply?.();
        }
      } catch (e) {
        setMsgs((m) => [...m, { role: "assistant", content: errorMessage(t, locale, e) }]);
      }
    }
    running.current = false;
    setBusy(false);
  };

  const go = async (value: string) => {
    const v = value.trim();
    if (!v) return;
    setText("");
    setEmojis(false);
    setMsgs((m) => [...m, { role: "user", content: v }]);
    queue.current.push({ text: v });
    void drain();
  };

  const sendPhoto = async () => {
    if (!attach) return;
    const caption = text.trim();
    const { file, url } = attach;
    setAttach(null);
    setText("");
    setEmojis(false);
    setMsgs((m) => [...m, { role: "user", content: caption, mediaUrl: url }]);
    queue.current.push({ file, caption });
    void drain();
  };

  const pickPhoto = async (file: File | undefined) => {
    if (!file || !file.type.startsWith("image/")) return;
    const small = await shrinkImage(file);
    setAttach((old) => { if (old) URL.revokeObjectURL(old.url); return { file: small, url: URL.createObjectURL(small) }; });
  };

  const sendVoice = (file: File) => {
    setMsgs((m) => [...m, { role: "user", content: "", audioUrl: URL.createObjectURL(file) }]);
    queue.current.push({ file, caption: "" });
    void drain();
  };

  const startRecording = async () => {
    if (recording || typeof MediaRecorder === "undefined" || !navigator.mediaDevices?.getUserMedia) {
      if (typeof MediaRecorder === "undefined") setMsgs((m) => [...m, { role: "assistant", content: t("chat.micDenied") }]);
      return;
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const mime = ["audio/webm;codecs=opus", "audio/webm", "audio/mp4", "audio/ogg;codecs=opus"].find((m) => MediaRecorder.isTypeSupported(m));
      const rec = new MediaRecorder(stream, mime ? { mimeType: mime } : undefined);
      chunks.current = [];
      cancelled.current = false;
      rec.ondataavailable = (e) => { if (e.data.size > 0) chunks.current.push(e.data); };
      rec.onstop = () => {
        stream.getTracks().forEach((track) => track.stop());
        if (ticker.current) clearInterval(ticker.current);
        setRecording(false);
        if (cancelled.current) return;
        const type = (rec.mimeType || mime || "audio/webm").split(";")[0];
        const blob = new Blob(chunks.current, { type });
        if (blob.size < 800) return;   // a tap with no speech
        sendVoice(new File([blob], `voice.${type.includes("mp4") ? "m4a" : type.includes("ogg") ? "ogg" : "webm"}`, { type }));
      };
      rec.start();
      recorder.current = rec;
      setRecording(true);
      setSeconds(0);
      ticker.current = setInterval(() => setSeconds((s) => {
        if (s + 1 >= MAX_VOICE_SECONDS && rec.state === "recording") rec.stop();
        return s + 1;
      }), 1000);
    } catch {
      setMsgs((m) => [...m, { role: "assistant", content: t("chat.micDenied") }]);
    }
  };

  const stopRecording = (cancel: boolean) => {
    cancelled.current = cancel;
    if (recorder.current?.state === "recording") recorder.current.stop();
  };

  const quick = [t("chat.q1"), t("chat.q2"), t("chat.q3")];
  const canMedia = !!sendMedia;
  const shown = (m: Msg) => pretty(m.content, t("chat.photo"), t("chat.voiceNote"));
  return (
    <div className={`flex min-h-0 flex-col ${className}`}>
      <div className={`min-h-0 flex-1 overflow-y-auto text-sm ${widget ? "space-y-2.5 px-3 pb-2 pt-1" : "space-y-3 p-3"}`}>
        <Bubble role="assistant" widget={widget} avatar={widget ? agentAvatar : undefined}>{greeting}</Bubble>
        {msgs.length === 0 && (
          <div className={widget ? "flex flex-col items-end gap-2 ps-10" : "flex flex-wrap gap-2"}>
            {quick.map((q) => (
              <button key={q} type="button" onClick={() => void go(q)}
                className={widget ? "rounded-full border border-[var(--s-line,#e2e8f0)] bg-white px-4 py-2 text-start text-sm font-semibold shadow-sm transition hover:bg-[var(--s-soft,#f1f5f9)]" : "rounded-full border border-[var(--s-line,#e2e8f0)] bg-white px-3 py-1.5 text-xs font-semibold hover:bg-[var(--s-soft,#f1f5f9)]"}>{q}</button>
            ))}
          </div>
        )}
        {msgs.map((m, i) => (
          <div key={i} className="space-y-2">
            <Bubble role={m.role} widget={widget} avatar={widget && m.role === "assistant" && msgs[i - 1]?.role !== "assistant" ? agentAvatar : widget && m.role === "assistant" ? "" : undefined}>
              {/* a picture the shop sent from its inbox, or the customer's own photo */}
              {/* eslint-disable-next-line @next/next/no-img-element */}
              {m.mediaUrl && <img src={m.mediaUrl} alt="" className="mb-1.5 max-h-56 rounded-xl object-cover" />}
              {m.audioUrl && <audio src={m.audioUrl} controls className="h-9 max-w-full" />}
              {shown(m)}
            </Bubble>
            {onTeach && m.role === "assistant" && i > 0 && (
              <button type="button" onClick={() => onTeach(msgs[i - 1]?.role === "user" ? msgs[i - 1].content : "", m.content)} className="text-xs font-semibold text-brand underline">🎓 {t("ai.teachThis")}</button>
            )}
            {m.products && m.products.length > 0 && (
              <div className="flex gap-2 overflow-x-auto pb-1">
                {m.products.slice(0, 6).map((p) => (
                  <Link key={p.slug} href={`/products/${p.slug}`} className="w-32 shrink-0 overflow-hidden rounded-xl border border-[var(--s-line,#e2e8f0)] bg-white">
                    {(p.imageUrl || p.imageMediaBase) &&   <img src={mediaUrl({ url: p.imageUrl, mediaBase: p.imageMediaBase, mediaExt: p.imageMediaExt }, "thumb") ?? ""} alt="" className="h-24 w-full bg-[var(--s-soft,#f1f5f9)] object-cover" />}
                    <p className="line-clamp-2 px-2 pt-1 text-xs font-semibold">{p.name}</p>
                    <p className="px-2 pb-2 text-xs font-bold" style={{ color: "var(--brand)" }}>{money(p.priceMinor, p.currency, locale)}</p>
                  </Link>
                ))}
              </div>
            )}
            {m.order && (
              <div className="rounded-2xl border border-emerald-200 bg-emerald-50 p-3 text-xs text-emerald-900">
                <p className="font-bold">✓ {t("chat.orderPlaced")} #{m.order.orderNumber}{m.order.test ? ` (${t("chat.testOrder")})` : ""}</p>
                <p className="mt-0.5">{t("chat.total")}: {money(m.order.totalMinor, m.order.currency, locale)}</p>
                {!m.order.test && !m.order.whatsappConfirmed && <p className="mt-1.5 leading-relaxed">{m.order.whatsappUrl ? t("chat.cardWa") : t("chat.cardCs")}</p>}
                <div className="mt-2 flex flex-wrap gap-2">
                  {m.order.trackPath && !m.order.test && <Link href={m.order.trackPath} className="rounded-full bg-white px-3 py-1 font-semibold">{t("chat.track")}</Link>}
                  {m.order.whatsappUrl && <a href={m.order.whatsappUrl} target="_blank" rel="noopener noreferrer" className="rounded-full bg-[#25D366] px-3 py-1 font-semibold text-white">{t("chat.confirmWhatsApp")}</a>}
                  {m.order.whatsappConfirmed && <span className="rounded-full bg-white px-3 py-1 font-semibold">✓ {t("chat.confirmedWhatsApp")}</span>}
                  {m.order.paymentPath && <a href={m.order.paymentPath} className="rounded-full bg-emerald-600 px-3 py-1 font-semibold text-white">{t("chat.pay")}</a>}
                </div>
              </div>
            )}
          </div>
        ))}
        {busy && <Bubble role="assistant" widget={widget} avatar={widget ? "" : undefined}><span className="inline-flex gap-1"><i className="h-1.5 w-1.5 animate-bounce rounded-full bg-slate-400" /><i className="h-1.5 w-1.5 animate-bounce rounded-full bg-slate-400 [animation-delay:120ms]" /><i className="h-1.5 w-1.5 animate-bounce rounded-full bg-slate-400 [animation-delay:240ms]" /></span></Bubble>}
        <div ref={end} />
      </div>

      {!widget ? (
        <form onSubmit={(e) => { e.preventDefault(); void go(text); }} className="flex items-center gap-2 border-t border-[var(--s-line,#e2e8f0)] bg-white p-2">
          <input value={text} onChange={(e) => setText(e.target.value)} maxLength={1000} placeholder={t("chat.placeholder")} aria-label={t("chat.placeholder")}
            className="min-w-0 flex-1 rounded-full border border-[var(--s-line,#e2e8f0)] bg-white px-4 py-2.5 text-sm outline-none focus:border-[var(--brand)]" />
          <button type="submit" disabled={!text.trim()} aria-label={t("chat.send")} className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full text-white disabled:opacity-50" style={{ background: "var(--brand)" }}>
            <Icon name="arrow" className="h-4 w-4 rtl:rotate-180" />
          </button>
          {onReset && <button type="button" onClick={() => void onReset().then(() => setMsgs([]))} className="shrink-0 rounded-full px-2 text-xs text-slate-500 underline">{t("chat.reset")}</button>}
        </form>
      ) : (
        <div className="relative px-3 pb-1 pt-1">
          {attach && (
            <div className="mb-2 flex items-center gap-2 rounded-2xl bg-white p-2 shadow-sm">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={attach.url} alt="" className="h-14 w-14 rounded-xl object-cover" />
              <span className="min-w-0 flex-1 truncate text-xs text-slate-500">{t("chat.photo")}</span>
              <button type="button" onClick={() => setAttach(null)} aria-label={t("chat.voiceCancel")} className="grid h-8 w-8 place-items-center rounded-full hover:bg-slate-100"><Icon name="close" className="h-4 w-4" /></button>
            </div>
          )}
          {emojis && (
            <div className="absolute inset-x-3 bottom-full z-10 mb-1 grid grid-cols-8 gap-1 rounded-2xl border border-[var(--s-line,#e2e8f0)] bg-white p-2 text-xl shadow-xl">
              {EMOJIS.map((e) => <button key={e} type="button" onClick={() => { setText((v) => (v + e).slice(0, 1000)); field.current?.focus(); }} className="rounded-lg p-1 hover:bg-slate-100">{e}</button>)}
            </div>
          )}
          {recording ? (
            <div className="flex items-center gap-2 rounded-full border border-red-200 bg-white p-1.5 shadow-sm">
              <button type="button" onClick={() => stopRecording(true)} aria-label={t("chat.voiceCancel")} className="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-slate-100"><Icon name="close" className="h-4 w-4" /></button>
              <span className="flex flex-1 items-center gap-2 text-sm font-semibold text-red-600"><i className="h-2.5 w-2.5 animate-pulse rounded-full bg-red-500" />{Math.floor(seconds / 60)}:{String(seconds % 60).padStart(2, "0")}</span>
              <button type="button" onClick={() => stopRecording(false)} aria-label={t("chat.voiceStop")} className="grid h-10 w-10 shrink-0 place-items-center rounded-full text-white" style={{ background: "var(--brand)" }}><Icon name="arrow" className="h-4 w-4 rtl:rotate-180" /></button>
            </div>
          ) : (
            <form onSubmit={(e) => { e.preventDefault(); if (attach) void sendPhoto(); else void go(text); }} className="flex items-center gap-1 rounded-full border border-[var(--s-line,#e2e8f0)] bg-white p-1.5 shadow-sm focus-within:border-[var(--brand)]">
              <button type="button" onClick={() => setEmojis((v) => !v)} aria-label={t("chat.emoji")} className="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-[var(--s-soft,#f1f5f9)]">
                <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" aria-hidden><circle cx="12" cy="12" r="9" /><path d="M8.5 14.2a4.2 4.2 0 0 0 7 0M9 9.6h.01M15 9.6h.01" /></svg>
              </button>
              {canMedia && (
                <>
                  <input ref={fileInput} type="file" accept="image/*" hidden onChange={(e) => { void pickPhoto(e.target.files?.[0]); e.target.value = ""; }} />
                  <button type="button" onClick={() => fileInput.current?.click()} aria-label={t("chat.attach")} className="grid h-10 w-10 shrink-0 place-items-center rounded-full text-slate-500 hover:bg-slate-100">
                    <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" aria-hidden><path d="m20 11-8.6 8.6a5 5 0 0 1-7-7L13 4a3.3 3.3 0 0 1 4.7 4.7l-8.6 8.6a1.7 1.7 0 0 1-2.4-2.4L14.4 7" /></svg>
                  </button>
                </>
              )}
              <input ref={field} value={text} onChange={(e) => setText(e.target.value)} onFocus={() => setEmojis(false)} maxLength={1000} placeholder={t("chat.placeholder")} aria-label={t("chat.placeholder")} enterKeyHint="send"
                className="min-w-0 flex-1 bg-transparent px-2 text-base outline-none placeholder:text-slate-400" />
              {canMedia && !text.trim() && !attach ? (
                <button type="button" onClick={() => void startRecording()} aria-label={t("chat.voice")} className="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-[var(--s-soft,#f1f5f9)]">
                  <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" aria-hidden><rect x="9" y="3" width="6" height="11" rx="3" /><path d="M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21" /></svg>
                </button>
              ) : (
                <button type="submit" disabled={!text.trim() && !attach} aria-label={t("chat.send")} className="grid h-10 w-10 shrink-0 place-items-center rounded-full text-white disabled:opacity-40" style={{ background: "var(--brand)" }}>
                  <Icon name="arrow" className="h-4 w-4 rtl:rotate-180" />
                </button>
              )}
            </form>
          )}
        </div>
      )}
    </div>
  );
}

function Bubble({ role, children, widget = false, avatar }: { role: "user" | "assistant"; children: React.ReactNode; widget?: boolean; avatar?: string }) {
  const mine = role === "user";
  if (!widget) {
    return (
      <div className={`flex ${mine ? "justify-end" : "justify-start"}`}>
        <div className={`max-w-[85%] whitespace-pre-wrap rounded-2xl px-3.5 py-2 leading-relaxed ${mine ? "rounded-ee-md text-white" : "rounded-es-md bg-[var(--s-soft,#f1f5f9)]"}`} style={mine ? { background: "var(--brand)" } : undefined}>{children}</div>
      </div>
    );
  }
  // storefront widget: the assistant's first bubble in a run carries its avatar, the next ones keep the same indent
  return (
    <div className={`flex items-end gap-2 ${mine ? "justify-end" : "justify-start"}`}>
      {!mine && (avatar ? <AgentAvatar id={avatar} className="h-8 w-8" /> : avatar === "" ? <span className="h-8 w-8 shrink-0" /> : null)}
      <div className={`max-w-[80%] whitespace-pre-wrap break-words rounded-3xl px-4 py-2.5 leading-relaxed shadow-sm ${mine ? "rounded-ee-md text-white" : "rounded-es-md bg-white text-slate-900"}`} style={mine ? { background: "var(--brand)" } : undefined}>{children}</div>
    </div>
  );
}

/** The always-on green "online" dot. */
function OnlineDot({ className = "" }: { className?: string }) {
  return <span className={`block h-3 w-3 rounded-full bg-emerald-500 ${className}`} aria-hidden><span className="absolute inset-0 animate-ping rounded-full bg-emerald-400 opacity-60" /></span>;
}

/** On a phone the keyboard shrinks the visible area: the full-screen chat follows it, so the input never hides behind the keyboard. */
function useVisualViewport(active: boolean) {
  useEffect(() => {
    const vv = window.visualViewport;
    if (!active || !vv) return;
    const root = document.documentElement;
    const apply = () => { root.style.setProperty("--vvh", `${vv.height}px`); root.style.setProperty("--vvt", `${vv.offsetTop}px`); };
    apply();
    vv.addEventListener("resize", apply);
    vv.addEventListener("scroll", apply);
    return () => {
      vv.removeEventListener("resize", apply);
      vv.removeEventListener("scroll", apply);
      root.style.removeProperty("--vvh");
      root.style.removeProperty("--vvt");
    };
  }, [active]);
}

/**
 * The assistant on every storefront page: a "Need help? <name> is here" pill that opens the chat (a card on a laptop, the whole screen on a phone). Hidden when the owner switches the
 * assistant off.
 */
export function LiveChat() {
  const { t, whiteLabel } = useI18n();
  const [cfg, setCfg] = useState<Config | null>(null);
  const [open, setOpen] = useState(false);
  const [conv, setConv] = useState("");
  // the remembered sound setting (the widget renders nothing until the config arrives, so reading the browser's storage here cannot mismatch the server's page)
  const [sound, setSound] = useState(() => read(SOUND_KEY) !== "off");
  const [hello, setHello] = useState(false);

  useEffect(() => {
    let live = true;
    api<Config>("shop/chat/config").then((c) => { if (live) setCfg(c); }).catch(() => undefined);
    return () => { live = false; };
  }, []);
  // the "Hi 👋" bubble beside the closed pill, once per visit
  useEffect(() => {
    if (!cfg?.enabled || open) return;
    let seen = false;
    try { seen = sessionStorage.getItem(HELLO_KEY) === "1"; } catch { /* shown */ }
    if (seen) return;
    const h = setTimeout(() => setHello(true), 1500);
    return () => clearTimeout(h);
  }, [cfg?.enabled, open]);
  // a phone: the open chat is the whole screen, so the page behind it must not scroll
  useEffect(() => {
    if (!open || !window.matchMedia("(max-width: 639px)").matches) return;
    const root = document.documentElement;
    const before = root.style.overflow;
    root.style.overflow = "hidden";
    return () => { root.style.overflow = before; };
  }, [open]);
  useVisualViewport(open);

  const loadFn = useCallback(() => api<History>(`shop/chat/history?conversationId=${conv}`), [conv]);
  const send = useCallback((text: string) => api<Reply>("shop/chat", { body: { conversationId: conv, message: text } }), [conv]);
  const sendMedia = useCallback((file: File, caption: string) => {
    const form = new FormData();
    form.set("conversationId", conv);
    form.set("file", file);
    if (caption) form.set("message", caption);
    return api<Reply>("shop/chat/media", { form });
  }, [conv]);

  const dismissHello = () => {
    setHello(false);
    try { sessionStorage.setItem(HELLO_KEY, "1"); } catch { /* shown again next page */ }
  };
  const openChat = () => {
    if (!conv) setConv(conversationId());
    setHello(false);
    setOpen(true);
    try { sessionStorage.setItem(HELLO_KEY, "1"); } catch { /* ignore */ }
  };

  if (!cfg?.enabled) return null;
  const name = cfg.agentName?.trim() || t("chat.agentDefault");   // the shop's own name for the assistant, else Nada
  const avatar = isAvatarId(cfg.agentAvatar) ? cfg.agentAvatar : DEFAULT_AGENT_AVATAR;
  return (
    <>
      {open && (
        <section role="dialog" aria-label={t("chat.title")}
          className="fixed inset-x-0 top-[var(--vvt,0px)] z-50 flex h-[var(--vvh,100dvh)] flex-col overflow-hidden bg-[#f4f3ee] pt-[env(safe-area-inset-top)] sm:inset-x-auto sm:bottom-6 sm:start-4 sm:top-auto sm:h-[min(40rem,calc(100dvh-3rem))] sm:w-[24rem] sm:rounded-[28px] sm:border sm:border-[var(--s-line,#e2e8f0)] sm:pt-0 sm:shadow-2xl">
          <header className="m-3 flex items-center gap-2 rounded-3xl bg-[#101010] px-3 py-2.5 text-white">
            <span className="relative shrink-0"><AgentAvatar id={avatar} className="h-10 w-10" title={name} /><OnlineDot className="absolute -bottom-0.5 -end-0.5 border-2 border-[#101010]" /></span>
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-bold leading-tight">{name}</p>
              <p className="text-xs text-emerald-400">{t("chat.online")}</p>
            </div>
            <button type="button" onClick={() => { const next = !sound; setSound(next); write(SOUND_KEY, next ? "on" : "off"); }} aria-label={t("chat.sound")} aria-pressed={sound}
              className="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-white/10 hover:bg-white/20">
              <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                <path d="M4 9.5v5h3.5L12 18.5v-13L7.5 9.5H4Z" />
                {sound ? <path d="M15.5 9a4.2 4.2 0 0 1 0 6M18 6.8a7.6 7.6 0 0 1 0 10.4" /> : <path d="m16 9.5 4 5m0-5-4 5" />}
              </svg>
            </button>
            <button type="button" onClick={() => setOpen(false)} aria-label={t("common.close")} className="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-white/10 hover:bg-white/20">
              <Icon name="close" className="h-5 w-5" />
            </button>
          </header>
          {conv && (
            <ChatPanel live widget className="flex-1" agentAvatar={avatar} greeting={cfg.greeting || t("chat.greeting")} load={loadFn} send={send}
              sendMedia={cfg.media === false ? undefined : sendMedia} onReply={() => { if (sound) ping(); }} />
          )}
          {!whiteLabel && <p className="pb-[max(0.5rem,env(safe-area-inset-bottom))] pt-1 text-center text-xs text-slate-400">{t("brand.powered")} {t("brand.name")}</p>}
        </section>
      )}
      {!open && (
        <div className="float-b fixed start-4 z-30 flex flex-col items-start gap-2">
          {hello && (
            <div className="flex items-center gap-1 rounded-3xl bg-white py-2 pe-2 ps-4 text-sm font-bold text-slate-900 shadow-[0_10px_30px_-10px_rgb(0_0_0/0.4)]">
              <button type="button" onClick={openChat} className="pe-1">{t("chat.hi")}</button>
              <button type="button" onClick={dismissHello} aria-label={t("common.close")} className="grid h-6 w-6 place-items-center rounded-full text-slate-400 hover:bg-slate-100"><Icon name="close" className="h-3.5 w-3.5" /></button>
            </div>
          )}
          <button type="button" onClick={openChat} aria-label={t("chat.title")}
            className="flex items-center gap-3 rounded-full bg-white p-1.5 pe-5 text-start shadow-[0_14px_34px_-10px_rgb(0_0_0/0.5)] transition hover:-translate-y-1">
            <span className="relative"><AgentAvatar id={avatar} className="h-12 w-12" title={name} /><OnlineDot className="absolute -bottom-0.5 -end-0.5 border-2 border-white" /></span>
            <span className="leading-tight text-slate-900"><b className="block text-base">{t("chat.needHelp")}</b><span className="block text-sm text-slate-500">{t("chat.isHere", { name })}</span></span>
          </button>
        </div>
      )}
    </>
  );
}
