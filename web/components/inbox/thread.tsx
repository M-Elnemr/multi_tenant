"use client";

import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { useLayoutEffect, useMemo, useRef, useState } from "react";
import { useI18n } from "../i18n-provider";
import { ChannelIcon } from "../channel-icons";
import { Icon } from "../icons";
import { Menu } from "../overlay";
import { AiPill, ChatAvatar, StatusTag, dayLabel, left } from "./bits";
import type { ChatDetail, ChatEvent, Msg, Note } from "./types";

type Item = ({ at: string; kind: "msg"; m: Msg } | { at: string; kind: "event"; e: ChatEvent } | { at: string; kind: "note"; n: Note }) & { newDay?: boolean };

/** One conversation: the header with the controls, then customer / AI / team messages, system events and the team's notes in time order. */
export function Thread({ chat, onBack, panelOpen, onTogglePanel, actions, canWrite, onOlder, olderLoading, composer, onDeleteNote }: {
  chat: ChatDetail; onBack?: () => void; panelOpen: boolean; onTogglePanel: () => void; canWrite: boolean;
  actions: { takeover: () => void; resume: () => void; close: () => void; reopen: () => void; unread: () => void; unhappy: (on: boolean) => void; prev?: () => void; next?: () => void };
  onOlder: () => void; olderLoading: boolean; composer: React.ReactNode; onDeleteNote: (id: number) => void;
}) {
  const { t, locale } = useI18n();
  const reduce = useReducedMotion();
  const scroller = useRef<HTMLDivElement>(null);
  const atBottom = useRef(true);
  const lastId = useRef<number | null>(null);
  const [newBelow, setNewBelow] = useState(false);

  const items = useMemo<Item[]>(() => {
    const out: Item[] = [
      ...chat.messages.map((m) => ({ at: m.createdAt, kind: "msg" as const, m })),
      ...chat.events.map((e) => ({ at: e.createdAt, kind: "event" as const, e })),
      ...chat.notes.map((n) => ({ at: n.createdAt, kind: "note" as const, n })),
    ];
    out.sort((a, b) => new Date(a.at).getTime() - new Date(b.at).getTime());
    return out.map((it, i) => ({ ...it, newDay: i === 0 || new Date(it.at).toDateString() !== new Date(out[i - 1].at).toDateString() }));
  }, [chat.messages, chat.events, chat.notes]);

  // stay at the newest message (unless the owner scrolled up to read; then show «new messages»)
  useLayoutEffect(() => {
    const el = scroller.current;
    if (!el) return;
    const newest = chat.messages[chat.messages.length - 1]?.id ?? null;
    const changed = newest !== lastId.current;
    if (lastId.current === null || atBottom.current) el.scrollTop = el.scrollHeight;
    else if (changed) setNewBelow(true);
    lastId.current = newest;
  }, [chat.messages, chat.notes.length, chat.events.length]);

  const name = chat.customerName || chat.customerPhone || t("inbox.guest");
  const remaining = left(chat.window.endsAt, locale);
  const statusTags = chat.statuses.filter((s) => s !== "OPEN");

  return (
    <div className="flex h-full min-h-0 flex-col bg-canvas">
      {/* header */}
      <div className="flex shrink-0 items-center gap-2.5 border-b border-line bg-surface px-3 py-2.5 sm:px-5">
        {onBack && <button type="button" onClick={onBack} aria-label={t("common.back")} className="grid h-10 w-10 shrink-0 place-items-center rounded-[10px] text-ink-2 hover:bg-[color-mix(in_srgb,var(--ink)_6%,transparent)]"><Icon name="arrow" className="h-5 w-5 rotate-180 rtl:rotate-0" /></button>}
        <button type="button" onClick={onTogglePanel} className="flex min-w-0 flex-1 items-center gap-3 text-start">
          <ChatAvatar name={chat.customerName} channel={chat.channel} size={38} />
          <span className="min-w-0">
            <span className="flex items-center gap-2">
              <span className="truncate font-display text-[15.5px] font-semibold text-ink">{name}</span>
              <span className="hidden items-center gap-1 sm:flex">{statusTags.slice(0, 2).map((s) => <StatusTag key={s} status={s} label={t(`inbox.st.${s}`)} size="xs" />)}</span>
            </span>
            <span className="flex items-center gap-1.5 text-[12px] text-muted">
              <ChannelIcon channel={chat.channel} className="h-3 w-3" />{t(`inbox.ch.${chat.channel}`)}
              {chat.customerPhone && <><span className="text-faint">·</span><span dir="ltr" className="tnum">{chat.customerPhone}</span></>}
              {remaining && chat.window.mode !== "WEB" && chat.window.mode !== "OWN_NUMBER" && <><span className="text-faint">·</span><span className="inline-flex items-center gap-1 tnum"><Icon name="clock" className="h-3 w-3" />{remaining}</span></>}
            </span>
          </span>
        </button>
        <div className="flex shrink-0 items-center gap-1">
          {actions.prev && <IconBtn icon="chevron" label={t("inbox.prev")} onClick={actions.prev} className="rotate-180 rtl:rotate-0" hide />}
          {actions.next && <IconBtn icon="chevron" label={t("inbox.next")} onClick={actions.next} className="rtl:rotate-180" hide />}
          {canWrite && (chat.takeover
            ? <button type="button" onClick={actions.resume} className="inline-flex h-9 items-center gap-1.5 rounded-[10px] bg-[linear-gradient(135deg,#7c5cff,#22d3ee)] px-3 text-[12.5px] font-semibold text-white shadow-[0_6px_16px_-6px_rgb(124_92_255/.6)] transition hover:brightness-110"><Icon name="sparkles" className="h-4 w-4" /><span className="hidden sm:inline">{t("inbox.resume")}</span></button>
            : <button type="button" onClick={actions.takeover} className="inline-flex h-9 items-center gap-1.5 rounded-[10px] bg-ink px-3 text-[12.5px] font-semibold text-surface transition hover:bg-ink-2"><Icon name="users" className="h-4 w-4" /><span className="hidden sm:inline">{t("inbox.takeover")}</span></button>)}
          {canWrite && (
            <Menu label={t("inbox.more")} items={[
              chat.status === "OPEN" ? { label: t("inbox.close"), icon: "check", onSelect: actions.close, hint: "E" } : { label: t("inbox.reopen"), icon: "return", onSelect: actions.reopen },
              { label: t("inbox.markUnread"), icon: "eye", onSelect: actions.unread },
              chat.unhappy ? { label: t("inbox.notUnhappy"), icon: "heart", onSelect: () => actions.unhappy(false) } : { label: t("inbox.markUnhappy"), icon: "alert", onSelect: () => actions.unhappy(true) },
              "divider",
              ...(chat.window.openUrl ? [{ label: t(`inbox.open.${chat.window.openKind ?? chat.channel}`), icon: "external", onSelect: () => window.open(chat.window.openUrl, "_blank", "noopener") }] : []),
            ]} trigger={({ toggle }) => <IconBtn icon="menu" label={t("inbox.more")} onClick={toggle} />} />
          )}
          <IconBtn icon="layout" label={panelOpen ? t("inbox.hideDetails") : t("inbox.showDetails")} onClick={onTogglePanel} active={panelOpen} />
        </div>
      </div>

      {/* unhappy banner */}
      <AnimatePresence initial={false}>
        {chat.unhappy && (
          <motion.div initial={{ height: 0, opacity: 0 }} animate={{ height: "auto", opacity: 1 }} exit={{ height: 0, opacity: 0 }} className="overflow-hidden">
            <div className="flex items-center gap-2 border-b border-rose-200 bg-rose-50 px-5 py-2 text-[12.5px] text-rose-800">
              <Icon name="alert" className="h-4 w-4 shrink-0" />
              <span className="min-w-0 flex-1"><span className="font-semibold">{t("inbox.unhappyBanner")}</span>{chat.unhappyReason && <span className="text-rose-700/80"> · {chat.unhappyReason}</span>}</span>
              {canWrite && <button type="button" onClick={() => actions.unhappy(false)} className="shrink-0 font-semibold underline-offset-2 hover:underline">{t("inbox.notUnhappy")}</button>}
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      {/* messages */}
      <div ref={scroller} onScroll={(e) => { const el = e.currentTarget; atBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80; if (atBottom.current) setNewBelow(false); }}
        className="relative min-h-0 flex-1 overflow-y-auto overscroll-contain px-3 py-4 sm:px-6">
        {chat.hasOlder && <div className="mb-3 flex justify-center"><button type="button" onClick={onOlder} disabled={olderLoading} className="rounded-full bg-surface px-3 py-1 text-[12px] font-semibold text-muted ring-1 ring-line hover:text-ink">{olderLoading ? "…" : t("inbox.older")}</button></div>}
        <div className="mx-auto max-w-3xl space-y-1.5">
          {items.map((it, i) => {
            const sep = it.newDay ? (<div key={`d${i}`} className="sticky top-0 z-[1] flex justify-center py-2"><span className="rounded-full bg-surface/90 px-3 py-1 text-[11.5px] font-semibold text-muted shadow-sm ring-1 ring-line backdrop-blur">{dayLabel(it.at, locale, t("inbox.today"), t("inbox.yesterday"))}</span></div>) : null;
            return (
              <div key={`${it.kind}-${it.kind === "msg" ? it.m.id : it.kind === "event" ? it.e.id : it.n.id}`}>
                {sep}
                {it.kind === "msg" && <Bubble m={it.m} prev={items[i - 1]} reduce={!!reduce} locale={locale} t={t} />}
                {it.kind === "event" && <EventPill e={it.e} locale={locale} t={t} />}
                {it.kind === "note" && <NoteCard n={it.n} locale={locale} t={t} onDelete={canWrite ? () => onDeleteNote(it.n.id) : undefined} />}
              </div>
            );
          })}
          {chat.delivery === "FAILED" && chat.deliveryNote && <p className="mx-auto mt-2 max-w-md rounded-[10px] bg-red-50 px-3 py-2 text-center text-[12px] text-red-700">{chat.deliveryNote}</p>}
        </div>
        <AnimatePresence>
          {newBelow && (
            <motion.button type="button" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} onClick={() => { scroller.current?.scrollTo({ top: scroller.current.scrollHeight, behavior: "smooth" }); setNewBelow(false); }}
              className="sticky bottom-2 mx-auto flex items-center gap-1.5 rounded-full bg-ink px-3.5 py-1.5 text-[12px] font-semibold text-surface shadow-lg">
              <Icon name="arrow" className="h-3.5 w-3.5 rotate-90" />{t("inbox.newMessages")}
            </motion.button>
          )}
        </AnimatePresence>
      </div>

      {composer}
    </div>
  );
}

function Bubble({ m, prev, reduce, locale, t }: { m: Msg; prev?: Item; reduce: boolean; locale: string; t: (k: string, p?: Record<string, string | number>) => string }) {
  const mine = m.role === "assistant";
  const who = m.kind === "FOLLOWUP" ? "followup" : m.byOwner ? "team" : mine ? "ai" : "customer";
  const sameAsPrev = prev?.kind === "msg" && prev.m.role === m.role && prev.m.byOwner === m.byOwner && new Date(m.createdAt).getTime() - new Date(prev.m.createdAt).getTime() < 5 * 60_000;
  const time = new Date(m.createdAt).toLocaleTimeString(locale === "ar" ? "ar-EG" : "en-GB", { hour: "numeric", minute: "2-digit" });
  const cls = who === "customer" ? "bg-surface text-ink ring-1 ring-line rounded-ss-[6px]"
    : who === "team" ? "bg-ink text-surface rounded-se-[6px]"
    : who === "followup" ? "bg-sky-50 text-sky-950 ring-1 ring-sky-200 rounded-se-[6px]"
    : "bg-[linear-gradient(135deg,rgb(124_92_255/.10),rgb(34_211_238/.10))] text-ink ring-1 ring-violet-200/70 rounded-se-[6px]";
  return (
    <motion.div initial={reduce ? false : { opacity: 0, y: 8, scale: 0.98 }} animate={{ opacity: 1, y: 0, scale: 1 }} transition={{ duration: 0.22, ease: [0.2, 0.8, 0.2, 1] }}
      className={`flex ${mine ? "justify-end" : "justify-start"} ${sameAsPrev ? "" : "pt-2"}`}>
      <div className={`max-w-[85%] sm:max-w-[72%] ${mine ? "items-end" : "items-start"} flex flex-col`}>
        {!sameAsPrev && mine && (
          <span className="mb-1 flex items-center gap-1 px-1 text-[11px] font-semibold text-faint">
            {who === "ai" && <><Icon name="sparkles" className="h-3 w-3 text-violet-500" />{t("inbox.who.ai")}</>}
            {who === "team" && <><Icon name="users" className="h-3 w-3" />{t("inbox.who.team")}</>}
            {who === "followup" && <><Icon name="clock" className="h-3 w-3 text-sky-500" />{t("inbox.who.followup")}</>}
          </span>
        )}
        <div className={`rounded-[18px] px-3.5 py-2.5 text-[14px] leading-relaxed shadow-[0_1px_1px_rgb(16_18_24/.04)] ${cls}`}>
          {m.mediaUrl && /* eslint-disable-next-line @next/next/no-img-element */ <img src={m.mediaUrl} alt="" className="mb-1.5 max-h-60 rounded-[12px] object-cover" />}
          {m.content && <p className="whitespace-pre-wrap break-words" dir="auto">{m.content}</p>}
          <span className={`mt-1 flex items-center justify-end gap-1 text-[10.5px] tnum ${who === "team" ? "text-surface/60" : "text-faint"}`}>
            {time}{mine && <Icon name="check" className="h-3 w-3" />}
          </span>
        </div>
      </div>
    </motion.div>
  );
}

function EventPill({ e, locale, t }: { e: ChatEvent; locale: string; t: (k: string, p?: Record<string, string | number>) => string }) {
  const time = new Date(e.createdAt).toLocaleTimeString(locale === "ar" ? "ar-EG" : "en-GB", { hour: "numeric", minute: "2-digit" });
  const icon = e.kind === "TAKEOVER" ? "users" : e.kind === "RESUME" ? "sparkles" : e.kind.startsWith("UNHAPPY") ? "alert" : e.kind.startsWith("FOLLOWUP") ? "clock" : e.kind === "CLOSED" ? "check" : "dot";
  let meta: { step?: number } = {};
  try { meta = e.meta ? JSON.parse(e.meta) : {}; } catch { /* plain */ }
  return (
    <div className="flex justify-center py-1.5">
      <span className="inline-flex max-w-full items-center gap-2 rounded-full bg-surface px-3 py-1.5 text-[12px] text-muted ring-1 ring-line">
        <Icon name={icon} className="h-3.5 w-3.5 shrink-0" />
        <span className="truncate">{t(`inbox.ev.${e.kind}`, { who: e.actor || t("inbox.who.system"), step: meta.step ?? "" })}</span>
        <span className="shrink-0 text-faint tnum">{time}</span>
      </span>
    </div>
  );
}

function NoteCard({ n, locale, t, onDelete }: { n: Note; locale: string; t: (k: string) => string; onDelete?: () => void }) {
  const time = new Date(n.createdAt).toLocaleTimeString(locale === "ar" ? "ar-EG" : "en-GB", { hour: "numeric", minute: "2-digit" });
  return (
    <div className="flex justify-center py-1.5">
      <div className="group w-full max-w-md rounded-[14px] border border-amber-200 bg-amber-50 px-3.5 py-2.5 text-[13px] text-amber-950">
        <p className="mb-1 flex items-center gap-1.5 text-[11px] font-semibold text-amber-700"><Icon name="lock" className="h-3 w-3" />{t("inbox.noteBy")} {n.author || ""} · {time}
          {onDelete && <button type="button" onClick={onDelete} className="ms-auto opacity-0 transition group-hover:opacity-100" aria-label={t("common.delete")}><Icon name="close" className="h-3.5 w-3.5" /></button>}
        </p>
        <p className="whitespace-pre-wrap" dir="auto">{n.body}</p>
      </div>
    </div>
  );
}

function IconBtn({ icon, label, onClick, active, className = "", hide }: { icon: string; label: string; onClick: () => void; active?: boolean; className?: string; hide?: boolean }) {
  return (
    <button type="button" onClick={onClick} aria-label={label} title={label}
      className={`${hide ? "hidden lg:grid" : "grid"} h-9 w-9 place-items-center rounded-[10px] transition ${active ? "bg-brand-soft text-brand" : "text-muted hover:bg-[color-mix(in_srgb,var(--ink)_6%,transparent)] hover:text-ink"}`}>
      <Icon name={icon} className={`h-[17px] w-[17px] ${className}`} />
    </button>
  );
}

export { AiPill };
