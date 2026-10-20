"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "@/lib/client";
import { money } from "@/lib/format";
import { mediaUrl } from "@/lib/media";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";

type Product = { name: string; slug: string; priceMinor: number; currency: string; imageUrl?: string; imageMediaBase?: string; imageMediaExt?: string };
type Order = { orderNumber: string; totalMinor: number; currency: string; trackPath?: string; paymentPath?: string; whatsappUrl?: string; whatsappConfirmed?: boolean; test?: boolean };
type Msg = { role: "user" | "assistant"; content: string; products?: Product[]; order?: Order | null };
type Reply = { conversationId: string; reply: string; order?: Order | null; products?: Product[] };

const KEY = "chatConv";

function conversationId(): string {
  try {
    let id = localStorage.getItem(KEY);
    if (!id) { id = crypto.randomUUID(); localStorage.setItem(KEY, id); }
    return id;
  } catch { return crypto.randomUUID(); }
}

/** The conversation, shared by the storefront button and the owner's test screen (same look, different endpoint). */
export function ChatPanel({ send, load, onReset, onTeach, greeting, className = "" }: {
  send: (text: string) => Promise<Reply>; load: () => Promise<{ role: "user" | "assistant"; content: string }[]>; onReset?: () => Promise<void>; onTeach?: (question: string, answer: string) => void; greeting: string;
  className?: string;
}) {
  const { t, locale } = useI18n();
  const [msgs, setMsgs] = useState<Msg[]>([]);
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const end = useRef<HTMLDivElement>(null);
  const queue = useRef<string[]>([]);   // messages typed while the assistant is still answering are sent one after another
  const running = useRef(false);

  useEffect(() => {
    let live = true;
    load().then((h) => { if (live) setMsgs(h); }).catch(() => undefined);
    return () => { live = false; };
  }, [load]);
  useEffect(() => { end.current?.scrollIntoView({ block: "end" }); }, [msgs, busy]);

  const drain = async () => {
    if (running.current) return;
    running.current = true;
    setBusy(true);
    while (queue.current.length > 0) {
      const v = queue.current.shift() as string;
      try {
        const r = await send(v);
        setMsgs((m) => [...m, { role: "assistant", content: r.reply, products: r.products, order: r.order }]);
      } catch (e) {
        setMsgs((m) => [...m, { role: "assistant", content: (e as Error).message || t("chat.error") }]);
      }
    }
    running.current = false;
    setBusy(false);
  };

  const go = async (value: string) => {
    const v = value.trim();
    if (!v) return;
    setText("");
    setMsgs((m) => [...m, { role: "user", content: v }]);
    queue.current.push(v);
    void drain();
  };

  const quick = [t("chat.q1"), t("chat.q2"), t("chat.q3")];
  return (
    <div className={`flex min-h-0 flex-col ${className}`}>
      <div className="min-h-0 flex-1 space-y-3 overflow-y-auto p-3 text-sm">
        <Bubble role="assistant">{greeting}</Bubble>
        {msgs.length === 0 && (
          <div className="flex flex-wrap gap-2">{quick.map((q) => <button key={q} type="button" onClick={() => void go(q)} className="rounded-full border border-[var(--s-line,#e2e8f0)] bg-white px-3 py-1.5 text-xs font-semibold hover:bg-[var(--s-soft,#f1f5f9)]">{q}</button>)}</div>
        )}
        {msgs.map((m, i) => (
          <div key={i} className="space-y-2">
            <Bubble role={m.role}>{m.content}</Bubble>
            {onTeach && m.role === "assistant" && i > 0 && (
              <button type="button" onClick={() => onTeach(msgs[i - 1]?.role === "user" ? msgs[i - 1].content : "", m.content)} className="text-xs font-semibold text-brand underline">🎓 {t("ai.teachThis")}</button>
            )}
            {m.products && m.products.length > 0 && (
              <div className="flex gap-2 overflow-x-auto pb-1">
                {m.products.slice(0, 6).map((p) => (
                  <Link key={p.slug} href={`/products/${p.slug}`} className="w-32 shrink-0 overflow-hidden rounded-xl border border-[var(--s-line,#e2e8f0)] bg-white">
                    {(p.imageUrl || p.imageMediaBase) && /* eslint-disable-next-line @next/next/no-img-element */ <img src={mediaUrl({ url: p.imageUrl, mediaBase: p.imageMediaBase, mediaExt: p.imageMediaExt }, "thumb") ?? ""} alt="" className="h-24 w-full bg-[var(--s-soft,#f1f5f9)] object-cover" />}
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
        {busy && <Bubble role="assistant"><span className="inline-flex gap-1"><i className="h-1.5 w-1.5 animate-bounce rounded-full bg-slate-400" /><i className="h-1.5 w-1.5 animate-bounce rounded-full bg-slate-400 [animation-delay:120ms]" /><i className="h-1.5 w-1.5 animate-bounce rounded-full bg-slate-400 [animation-delay:240ms]" /></span></Bubble>}
        <div ref={end} />
      </div>
      <form onSubmit={(e) => { e.preventDefault(); void go(text); }} className="flex items-center gap-2 border-t border-[var(--s-line,#e2e8f0)] bg-white p-2">
        <input value={text} onChange={(e) => setText(e.target.value)} maxLength={1000} placeholder={t("chat.placeholder")} aria-label={t("chat.placeholder")}
          className="min-w-0 flex-1 rounded-full border border-[var(--s-line,#e2e8f0)] bg-white px-4 py-2.5 text-sm outline-none focus:border-[var(--brand)]" />
        <button type="submit" disabled={!text.trim()} aria-label={t("chat.send")} className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full text-white disabled:opacity-50" style={{ background: "var(--brand)" }}>
          <Icon name="arrow" className="h-4 w-4 rtl:rotate-180" />
        </button>
        {onReset && <button type="button" onClick={() => void onReset().then(() => setMsgs([]))} className="shrink-0 rounded-full px-2 text-xs text-slate-500 underline">{t("chat.reset")}</button>}
      </form>
    </div>
  );
}

function Bubble({ role, children }: { role: "user" | "assistant"; children: React.ReactNode }) {
  const mine = role === "user";
  return (
    <div className={`flex ${mine ? "justify-end" : "justify-start"}`}>
      <div className={`max-w-[85%] whitespace-pre-wrap rounded-2xl px-3.5 py-2 leading-relaxed ${mine ? "rounded-ee-md text-white" : "rounded-es-md bg-[var(--s-soft,#f1f5f9)]"}`} style={mine ? { background: "var(--brand)" } : undefined}>{children}</div>
    </div>
  );
}

/** "Live chat with customer support" on every storefront page. Hidden when the owner switches the assistant off. */
export function LiveChat() {
  const { t } = useI18n();
  const [cfg, setCfg] = useState<{ enabled: boolean; greeting: string } | null>(null);
  const [open, setOpen] = useState(false);
  const [conv, setConv] = useState("");

  useEffect(() => {
    let live = true;
    api<{ enabled: boolean; greeting: string }>("shop/chat/config").then((c) => { if (live) setCfg(c); }).catch(() => undefined);
    return () => { live = false; };
  }, []);
  const loadFn = useCallback(() => api<{ role: "user" | "assistant"; content: string }[]>(`shop/chat/history?conversationId=${conv}`), [conv]);

  if (!cfg?.enabled) return null;
  return (
    <>
      {open && (
        <section role="dialog" aria-label={t("chat.title")} className="fixed inset-x-3 bottom-20 z-40 flex h-[min(34rem,calc(100vh-7rem))] flex-col overflow-hidden rounded-3xl border border-[var(--s-line,#e2e8f0)] bg-white shadow-2xl sm:inset-x-auto sm:start-4 sm:w-96 md:bottom-6">
          <header className="flex items-center justify-between gap-3 px-4 py-3 text-white" style={{ background: "var(--brand)" }}>
            <div className="flex items-center gap-2.5">
              <span className="relative flex h-9 w-9 items-center justify-center rounded-full bg-white/20"><Icon name="chat" className="h-5 w-5" /><OnlineDot className="absolute -bottom-0.5 -end-0.5 border-2 border-white" /></span>
              <div><p className="text-sm font-bold leading-tight">{t("chat.title")}</p><p className="text-xs opacity-90">{t("chat.online")}</p></div>
            </div>
            <button type="button" onClick={() => setOpen(false)} aria-label={t("common.close")} className="rounded-full p-1.5 hover:bg-white/20">✕</button>
          </header>
          {conv && <ChatPanel className="flex-1" greeting={cfg.greeting || t("chat.greeting")} load={loadFn} send={(text) => api<Reply>("shop/chat", { body: { conversationId: conv, message: text } })} />}
        </section>
      )}
      {!open && (
        <button type="button" onClick={() => { if (!conv) setConv(conversationId()); setOpen(true); }} aria-label={t("chat.title")}
          className="fixed bottom-20 start-4 z-30 flex items-center gap-2.5 rounded-full py-3 pe-5 ps-4 text-white shadow-[0_12px_30px_-8px_rgb(0_0_0/0.45)] transition hover:-translate-y-1 md:bottom-6" style={{ background: "var(--brand)" }}>
          <span className="relative"><Icon name="chat" className="h-6 w-6" /><OnlineDot className="absolute -end-1 -top-1 border-2 border-[var(--brand)]" /></span>
          <span className="hidden text-sm font-bold sm:inline">{t("chat.title")}</span>
        </button>
      )}
    </>
  );
}

/** The always-on green "online" dot. */
function OnlineDot({ className = "" }: { className?: string }) {
  return <span className={`block h-3 w-3 rounded-full bg-emerald-500 ${className}`} aria-hidden><span className="absolute inset-0 animate-ping rounded-full bg-emerald-400 opacity-60" /></span>;
}
