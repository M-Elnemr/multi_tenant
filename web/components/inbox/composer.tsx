"use client";

import Link from "next/link";
import { AnimatePresence, motion } from "motion/react";
import { useCallback, useEffect, useRef, useState } from "react";
import { api, ApiError } from "@/lib/client";
import { uploadFile } from "../uploader";
import { useI18n } from "../i18n-provider";
import { ChannelIcon } from "../channel-icons";
import { Icon } from "../icons";
import { Popover } from "../overlay";
import { useToast } from "../toast";
import { left } from "./bits";
import type { ChatDetail, SavedReply } from "./types";

const EMOJI = ["😀", "😊", "😍", "🥰", "🙏", "👍", "👏", "🔥", "✨", "🎉", "🎁", "💯", "❤️", "🌸", "🛍️", "📦", "🚚", "✅", "👇", "👉", "⏰", "💸", "😉", "🤝", "🥳", "😎", "🤩", "💐", "🧡", "🆕"];
const VARS = ["{name}", "{shop}"];
const ASSIST = ["SUGGEST", "REWRITE", "FRIENDLIER", "SHORTER", "SELL"] as const;

/**
 * The reply box: who answers now (the AI or you), the channel it goes out on, a growing text box («/» saved replies, «$» variables, «:» emoji), a picture, AI Assist
 * (suggests or rewrites, never sends by itself), Reply / Internal note, and Ctrl+Enter to send. When the channel's window is closed, the box is closed with the reason and
 * the way to reach the customer on the platform itself.
 */
export function Composer({ chat, canWrite, onSent, onNote, onTakeover, saved, onSummarize }: {
  chat: ChatDetail; canWrite: boolean; onSent: (text: string, imageFileId: string | null) => Promise<void>; onNote: (text: string) => Promise<void>;
  onTakeover: () => void; saved: SavedReply[]; onSummarize: () => void;
}) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const [text, setText] = useState("");
  const [mode, setMode] = useState<"reply" | "note">("reply");
  const [image, setImage] = useState<{ id: string; url: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [pop, setPop] = useState<null | "emoji" | "saved" | "vars" | "assist">(null);
  const [assisting, setAssisting] = useState(false);
  const box = useRef<HTMLTextAreaElement>(null);
  const anchor = useRef<HTMLDivElement>(null);
  const file = useRef<HTMLInputElement>(null);
  const closePop = useCallback(() => setPop(null), []);
  const w = chat.window;
  const closed = !w.canSend && mode === "reply";
  const ai = chat.aiActive && !chat.takeover;

  useEffect(() => {   // grow with the text
    const el = box.current;
    if (!el) return;
    el.style.height = "0px";
    el.style.height = Math.min(220, Math.max(44, el.scrollHeight)) + "px";
  }, [text]);
  useEffect(() => {   // «r» focuses the reply box
    const h = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement;
      if (e.key === "r" && !e.metaKey && !e.ctrlKey && !["INPUT", "TEXTAREA"].includes(el.tagName)) { e.preventDefault(); box.current?.focus(); }
    };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, []);

  const insert = (s: string) => {
    const el = box.current;
    if (!el) { setText((x) => x + s); return; }
    const a = el.selectionStart ?? text.length, b = el.selectionEnd ?? text.length;
    const before = text.slice(0, a).replace(/[/$:]$/, (m) => (pop ? "" : m));
    const next = before + s + text.slice(b);
    setText(next);
    requestAnimationFrame(() => { el.focus(); const p = before.length + s.length; el.setSelectionRange(p, p); });
    setPop(null);
  };
  const onChange = (v: string) => {
    setText(v);
    const last = v.slice(-1), prev = v.slice(-2, -1);
    if ((!prev || /\s/.test(prev)) && mode === "reply") {
      if (last === "/") setPop("saved");
      else if (last === "$") setPop("vars");
      else if (last === ":") setPop("emoji");
    }
  };
  const send = async () => {
    const v = text.trim();
    if ((!v && !image) || busy) return;
    setBusy(true);
    try {
      if (mode === "note") await onNote(v);
      else await onSent(v, image?.id ?? null);
      setText(""); setImage(null);
    } catch (e) {
      toast(e instanceof ApiError ? e.message : t("inbox.sendFailed"), { tone: "error" });
    } finally { setBusy(false); }
  };
  const attach = async (f: File) => {
    setUploading(true);
    try {
      const id = await uploadFile(f, "PRODUCT_IMAGE");
      setImage({ id, url: URL.createObjectURL(f) });
    } catch { toast(t("inbox.uploadFailed"), { tone: "error" }); } finally { setUploading(false); }
  };
  const assist = async (action: (typeof ASSIST)[number]) => {
    setPop(null);
    if (action !== "SUGGEST" && !text.trim()) { toast(t("inbox.assistNeedsDraft"), { tone: "info" }); return; }
    setAssisting(true);
    try {
      const r = await api<{ text: string }>(`store/chat/conversations/${chat.id}/assist`, { body: { action, draft: action === "SUGGEST" ? undefined : text } });
      setText(r.text);
      requestAnimationFrame(() => box.current?.focus());
    } catch (e) { toast(e instanceof ApiError ? e.message : t("inbox.assistFailed"), { tone: "error" }); } finally { setAssisting(false); }
  };

  const via = w.mode === "OWN_NUMBER" ? t("inbox.via.OWN_NUMBER") : w.mode === "CLOUD" ? t("inbox.via.CLOUD") : w.mode === "MESSENGER" ? t("inbox.via.MESSENGER") : w.mode === "INSTAGRAM" ? t("inbox.via.INSTAGRAM") : t("inbox.via.WEB");
  const remaining = left(w.endsAt, locale);

  if (!canWrite) return <div className="border-t border-line bg-surface px-5 py-4 text-center text-[13px] text-muted">{t("inbox.readOnly")}</div>;

  return (
    <div className="shrink-0 border-t border-line bg-surface px-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] pt-2.5 sm:px-5">
      {/* who answers now */}
      <div className={`mb-2 flex items-center gap-2 rounded-[11px] px-3 py-2 text-[12.5px] ${ai ? "bg-[linear-gradient(135deg,rgb(124_92_255/.08),rgb(34_211_238/.08))] text-violet-800" : chat.takeover ? "bg-violet-600/10 text-violet-800" : "bg-slate-100 text-slate-700"}`}>
        <Icon name={ai ? "sparkles" : "users"} className="h-4 w-4 shrink-0" />
        <span className="min-w-0 flex-1 font-medium">{ai ? t("inbox.mode.ai") : chat.takeover ? t("inbox.mode.you") : chat.aiChannelOn ? t("inbox.mode.paused") : t("inbox.mode.off")}</span>
        {ai && <button type="button" onClick={onTakeover} className="shrink-0 rounded-[8px] bg-violet-600 px-2.5 py-1 text-[12px] font-semibold text-white transition hover:bg-violet-700">{t("inbox.takeover")}</button>}
      </div>

      <AnimatePresence initial={false}>
        {closed ? (
          <motion.div key="closed" initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} className="rounded-[14px] border border-dashed border-line-strong bg-canvas p-4">
            <p className="flex items-center gap-2 text-[13.5px] font-semibold text-ink"><Icon name="lock" className="h-4 w-4 text-faint" />{w.reason === "NOT_CONNECTED" ? t("inbox.win.notConnected") : t("inbox.win.closed")}</p>
            <p className="mt-1 text-[12.5px] leading-relaxed text-muted">{w.reason === "NOT_CONNECTED" ? t("inbox.win.notConnectedHint") : t(`inbox.win.closedHint.${chat.channel}`)}</p>
            <div className="mt-3 flex flex-wrap items-center gap-2">
              {w.openUrl
                ? <a href={w.openUrl} target="_blank" rel="noreferrer" className="inline-flex items-center gap-2 rounded-[10px] bg-ink px-3.5 py-2 text-[13px] font-semibold text-surface transition hover:bg-ink-2"><ChannelIcon channel={chat.channel} className="h-4 w-4" />{t(`inbox.open.${w.openKind ?? chat.channel}`)}<Icon name="external" className="h-3.5 w-3.5 opacity-70" /></a>
                : <span className="text-[12px] text-faint">{t("inbox.open.none")}</span>}
              {w.reason === "NOT_CONNECTED" && <Link href="/dashboard/ai?tab=channels" className="rounded-[10px] px-3 py-2 text-[13px] font-semibold text-brand ring-1 ring-brand/30">{t("inbox.connectChannels")}</Link>}
              <button type="button" onClick={() => setMode("note")} className="rounded-[10px] px-3 py-2 text-[13px] font-semibold text-muted ring-1 ring-line hover:text-ink">{t("inbox.addNote")}</button>
            </div>
          </motion.div>
        ) : (
          <motion.div key="open" initial={false} ref={anchor} className={`rounded-[15px] ring-1 transition focus-within:ring-2 ${mode === "note" ? "bg-amber-50/70 ring-amber-300 focus-within:ring-amber-400" : "bg-surface ring-line-strong focus-within:ring-brand/40"}`}>
            {/* channel row */}
            <div className="flex items-center justify-between gap-2 px-3 pt-2.5">
              <span className="flex min-w-0 items-center gap-1.5 text-[12px] font-medium text-muted">
                {mode === "note" ? <><Icon name="lock" className="h-3.5 w-3.5 text-amber-600" /><span className="text-amber-800">{t("inbox.noteHint")}</span></>
                  : <><ChannelIcon channel={chat.channel} className="h-3.5 w-3.5" /><span className="truncate">{chat.channelName ? `${chat.channelName} · ` : ""}{via}</span>{remaining && <span className="shrink-0 rounded-full bg-canvas px-1.5 py-px text-[10.5px] text-faint ring-1 ring-line tnum">{t("inbox.windowLeft", { t: remaining })}</span>}</>}
              </span>
              {mode === "reply" && (
                <button type="button" onClick={() => setPop(pop === "assist" ? null : "assist")} disabled={assisting}
                  className="inline-flex shrink-0 items-center gap-1.5 rounded-[9px] bg-[linear-gradient(135deg,rgb(124_92_255/.12),rgb(34_211_238/.12))] px-2.5 py-1 text-[12px] font-semibold text-violet-700 transition hover:brightness-95 disabled:opacity-60">
                  {assisting ? <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-violet-300 border-t-violet-700" /> : <Icon name="sparkles" className="h-3.5 w-3.5" />}{t("inbox.aiAssist")}
                </button>
              )}
            </div>
            {image && (
              <div className="relative mx-3 mt-2 inline-block">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={image.url} alt="" className="h-20 w-20 rounded-[10px] object-cover ring-1 ring-line" />
                <button type="button" onClick={() => setImage(null)} aria-label={t("common.remove")} className="absolute -end-1.5 -top-1.5 grid h-5 w-5 place-items-center rounded-full bg-ink text-surface"><Icon name="close" className="h-3 w-3" /></button>
              </div>
            )}
            <textarea ref={box} value={text} onChange={(e) => onChange(e.target.value)} rows={1} maxLength={2000}
              onKeyDown={(e) => { if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) { e.preventDefault(); void send(); } if (e.key === "Escape") setPop(null); }}
              placeholder={mode === "note" ? t("inbox.notePh") : t("inbox.replyPh")}
              className="block w-full resize-none bg-transparent px-3 py-2 text-[14px] leading-relaxed text-ink outline-none placeholder:text-faint" />
            <div className="flex items-center justify-between gap-2 px-2 pb-2">
              <div className="flex items-center gap-0.5">
                {mode === "reply" && (
                  <>
                    <ToolBtn icon="sparkles" label={t("inbox.aiAssist")} onClick={() => setPop(pop === "assist" ? null : "assist")} />
                    <ToolBtn icon="star" label={t("inbox.emoji")} text="🙂" onClick={() => setPop(pop === "emoji" ? null : "emoji")} />
                    <ToolBtn icon="file" label={t("inbox.saved")} onClick={() => setPop(pop === "saved" ? null : "saved")} />
                    <ToolBtn icon="tag" label={t("inbox.variables")} text="$" onClick={() => setPop(pop === "vars" ? null : "vars")} />
                    <ToolBtn icon="camera" label={t("inbox.image")} busy={uploading} onClick={() => file.current?.click()} />
                    <ToolBtn icon="mic" label={t("inbox.voiceSoon")} disabled onClick={() => undefined} />
                    <input ref={file} type="file" accept="image/*" hidden onChange={(e) => { const f = e.target.files?.[0]; if (f) void attach(f); e.target.value = ""; }} />
                  </>
                )}
              </div>
              <button type="button" onClick={() => void send()} disabled={busy || (!text.trim() && !image)}
                className={`inline-flex h-9 items-center gap-1.5 rounded-[10px] px-3.5 text-[13px] font-semibold text-surface transition active:scale-[.97] disabled:opacity-40 ${mode === "note" ? "bg-amber-600 hover:bg-amber-700" : "bg-ink hover:bg-ink-2"}`}>
                {busy ? <span className="h-4 w-4 animate-spin rounded-full border-2 border-white/40 border-t-white" /> : <Icon name={mode === "note" ? "check" : "send"} className="h-4 w-4 flip-rtl" />}
                {mode === "note" ? t("inbox.saveNote") : t("inbox.send")}
              </button>
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      <Popover open={pop !== null && !closed} onClose={closePop} anchor={anchor} placement="top-start" width={pop === "emoji" ? 300 : 320} className="p-2">
        {pop === "emoji" && <div className="grid grid-cols-8 gap-0.5">{EMOJI.map((e) => <button key={e} type="button" onClick={() => insert(e)} className="grid h-9 place-items-center rounded-[8px] text-[19px] transition hover:bg-[color-mix(in_srgb,var(--ink)_6%,transparent)]">{e}</button>)}</div>}
        {pop === "vars" && (
          <div className="space-y-0.5">
            <p className="px-2 pb-1 pt-0.5 text-[11.5px] font-semibold text-faint">{t("inbox.variables")}</p>
            {VARS.map((v) => <button key={v} type="button" onClick={() => insert(v)} className="flex w-full items-center justify-between rounded-[9px] px-2.5 py-2 text-start text-[13px] hover:bg-[color-mix(in_srgb,var(--ink)_5%,transparent)]"><span className="font-mono text-[12.5px] text-brand" dir="ltr">{v}</span><span className="text-[12px] text-muted">{t(`inbox.var.${v.slice(1, -1)}`)}</span></button>)}
          </div>
        )}
        {pop === "saved" && (
          <div className="max-h-72 space-y-0.5 overflow-y-auto">
            <p className="flex items-center justify-between px-2 pb-1 pt-0.5 text-[11.5px] font-semibold text-faint">{t("inbox.saved")}<Link href="/dashboard/inbox/followups?tab=saved" className="text-brand">{t("inbox.manage")}</Link></p>
            {saved.length === 0 && <p className="px-2.5 py-4 text-center text-[12.5px] text-muted">{t("inbox.savedNone")}</p>}
            {saved.map((s) => <button key={s.id} type="button" onClick={() => insert(s.body)} className="block w-full rounded-[9px] px-2.5 py-2 text-start hover:bg-[color-mix(in_srgb,var(--ink)_5%,transparent)]"><span className="block text-[13px] font-semibold text-ink">{s.title}</span><span className="line-clamp-1 text-[12px] text-muted">{s.body}</span></button>)}
          </div>
        )}
        {pop === "assist" && (
          <div className="space-y-0.5">
            <p className="px-2 pb-1 pt-0.5 text-[11.5px] font-semibold text-faint">{t("inbox.assistTitle")}</p>
            {ASSIST.map((a) => (
              <button key={a} type="button" onClick={() => void assist(a)} disabled={a !== "SUGGEST" && !text.trim()}
                className="flex w-full items-center gap-2.5 rounded-[9px] px-2.5 py-2 text-start text-[13px] font-medium text-ink-2 transition hover:bg-[color-mix(in_srgb,var(--ink)_5%,transparent)] disabled:opacity-40">
                <Icon name={a === "SUGGEST" ? "sparkles" : a === "SELL" ? "trending" : a === "SHORTER" ? "minus" : a === "FRIENDLIER" ? "heart" : "edit"} className="h-4 w-4 text-violet-600" />{t(`inbox.assist.${a}`)}
              </button>
            ))}
            <span className="my-1 block h-px bg-line" />
            <button type="button" onClick={() => { setPop(null); onSummarize(); }} className="flex w-full items-center gap-2.5 rounded-[9px] px-2.5 py-2 text-start text-[13px] font-medium text-ink-2 hover:bg-[color-mix(in_srgb,var(--ink)_5%,transparent)]"><Icon name="list" className="h-4 w-4 text-violet-600" />{t("inbox.assist.SUMMARY")}</button>
            <p className="px-2.5 pb-1 pt-1.5 text-[11px] text-faint">{t("inbox.assistNever")}</p>
          </div>
        )}
      </Popover>

      {/* reply / internal note, summarize */}
      <div className="mt-2 flex items-center justify-between gap-2 px-1">
        <div className="flex rounded-[9px] bg-canvas p-0.5 ring-1 ring-line">
          {(["reply", "note"] as const).map((m) => (
            <button key={m} type="button" onClick={() => setMode(m)} className={`rounded-[7px] px-2.5 py-1 text-[12px] font-semibold transition ${mode === m ? (m === "note" ? "bg-amber-500 text-white" : "bg-surface text-ink shadow-sm") : "text-muted hover:text-ink"}`}>{t(`inbox.tab.${m}`)}</button>
          ))}
        </div>
        <span className="hidden text-[11px] text-faint sm:block">{t("inbox.hint")}</span>
        <button type="button" onClick={onSummarize} className="inline-flex items-center gap-1.5 text-[12px] font-semibold text-violet-700 hover:underline"><Icon name="list" className="h-3.5 w-3.5" />{t("inbox.summarize")}</button>
      </div>
    </div>
  );
}

function ToolBtn({ icon, label, onClick, text, disabled, busy }: { icon: string; label: string; onClick: () => void; text?: string; disabled?: boolean; busy?: boolean }) {
  return (
    <button type="button" onClick={onClick} disabled={disabled || busy} title={label} aria-label={label}
      className="grid h-8 w-8 place-items-center rounded-[8px] text-[15px] text-muted transition hover:bg-[color-mix(in_srgb,var(--ink)_6%,transparent)] hover:text-ink disabled:opacity-35">
      {busy ? <span className="h-4 w-4 animate-spin rounded-full border-2 border-line-strong border-t-brand" /> : text ? <span className="font-semibold leading-none">{text}</span> : <Icon name={icon} className="h-4 w-4" />}
    </button>
  );
}
