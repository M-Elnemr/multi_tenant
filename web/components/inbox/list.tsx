"use client";

import Link from "next/link";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { useCallback, useEffect, useRef, useState } from "react";
import { useI18n } from "../i18n-provider";
import { Icon } from "../icons";
import { Checkbox, Menu, Popover, Sheet } from "../overlay";
import { AiPill, ChatAvatar, StatusTag, when } from "./bits";
import { ActiveChips, FilterPanel } from "./filters";
import type { ChatRow, Counts, Filters, Status } from "./types";

/** The conversations column: the Conversations / Comments switch, search, quick status tabs, the filter panel, sort, selection with bulk actions, and the rows. */
export function ConversationList(props: {
  rows: ChatRow[] | null; total: number; hasMore: boolean; loadingMore: boolean; onMore: () => void; filters: Filters; setFilters: (f: Filters) => void; activeFilters: number;
  selected: string | null; onSelect: (id: string) => void; counts: Counts | null; canWrite: boolean; onBulk: (ids: string[], action: string) => Promise<void>; phone: boolean;
}) {
  const { rows, total, filters, setFilters, selected, onSelect, counts, canWrite, phone } = props;
  const { t, locale } = useI18n();
  const reduce = useReducedMotion();
  const [q, setQ] = useState(filters.q);
  const [filterOpen, setFilterOpen] = useState(false);
  const [selecting, setSelecting] = useState(false);
  const [picked, setPicked] = useState<string[]>([]);
  const filterBtn = useRef<HTMLButtonElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const sentinel = useRef<HTMLDivElement>(null);
  const closeFilter = useCallback(() => setFilterOpen(false), []);

  // search as you type (debounced)
  useEffect(() => {
    if (q === filters.q) return;
    const id = setTimeout(() => setFilters({ ...filters, q }), 320);
    return () => clearTimeout(id);
  }, [q, filters, setFilters]);
  // «/» focuses the search
  useEffect(() => {
    const h = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement;
      if (e.key === "/" && !["INPUT", "TEXTAREA"].includes(el.tagName) && !el.isContentEditable) { e.preventDefault(); searchRef.current?.focus(); }
    };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, []);
  // load the next page when the end of the list scrolls into view
  useEffect(() => {
    const el = sentinel.current;
    if (!el || !props.hasMore) return;
    const io = new IntersectionObserver((e) => { if (e[0].isIntersecting) props.onMore(); }, { rootMargin: "200px" });
    io.observe(el);
    return () => io.disconnect();
  }, [props.hasMore, props.onMore, props]);

  const quick: { key: string; label: string; n?: number; status?: Status }[] = [
    { key: "all", label: t("inbox.all") },
    { key: "NEEDS_REPLY", label: t("inbox.st.NEEDS_REPLY"), n: counts?.needsReply, status: "NEEDS_REPLY" },
    { key: "WAITING", label: t("inbox.st.WAITING"), n: counts?.waiting, status: "WAITING" },
    { key: "UNHAPPY", label: t("inbox.st.UNHAPPY"), n: counts?.unhappy, status: "UNHAPPY" },
    { key: "TAKEOVER", label: t("inbox.st.TAKEOVER"), status: "TAKEOVER" },
  ];
  const quickOn = (k: string, s?: Status) => (s ? filters.status.length === 1 && filters.status[0] === s : filters.status.length === 0);
  const sortItems = (["newest", "oldest", "unread", "priority"] as const).map((s) => ({ label: t(`inbox.sort.${s}`), checked: filters.sort === s, onSelect: () => setFilters({ ...filters, sort: s }) }));
  const num = (n: number) => n.toLocaleString(locale === "ar" ? "ar-EG" : "en-US");

  return (
    <div className="flex h-full min-h-0 flex-col bg-surface">
      {/* header */}
      <div className="shrink-0 space-y-3 px-4 pb-3 pt-4">
        <div className="flex items-center justify-between gap-2">
          <div className="flex rounded-[11px] bg-canvas p-0.5 ring-1 ring-line">
            <span className="rounded-[9px] bg-surface px-3 py-1.5 text-[13px] font-semibold text-ink shadow-sm">{t("inbox.conversations")}</span>
            <Link href="/dashboard/inbox/comments" className="flex items-center gap-1.5 rounded-[9px] px-3 py-1.5 text-[13px] font-medium text-muted transition hover:text-ink">
              {t("inbox.comments")}{(counts?.unrepliedComments ?? 0) > 0 && <span className="rounded-full bg-brand px-1.5 text-[10px] font-bold text-white tnum">{num(counts!.unrepliedComments)}</span>}
            </Link>
          </div>
          <div className="flex items-center gap-0.5">
            <button ref={filterBtn} type="button" onClick={() => setFilterOpen((v) => !v)} aria-label={t("inbox.filters")} aria-expanded={filterOpen}
              className={`relative grid h-9 w-9 place-items-center rounded-[10px] transition ${props.activeFilters > 0 ? "bg-brand-soft text-brand" : "text-muted hover:bg-[color-mix(in_srgb,var(--ink)_6%,transparent)] hover:text-ink"}`}>
              <Icon name="filter" className="h-[17px] w-[17px]" />
              {props.activeFilters > 0 && <span className="absolute -end-0.5 -top-0.5 grid h-4 min-w-4 place-items-center rounded-full bg-brand px-1 text-[9.5px] font-bold text-white">{props.activeFilters}</span>}
            </button>
            {canWrite && (
              <button type="button" onClick={() => { setSelecting((v) => !v); setPicked([]); }} aria-pressed={selecting} aria-label={t("inbox.select")}
                className={`grid h-9 w-9 place-items-center rounded-[10px] transition ${selecting ? "bg-ink text-surface" : "text-muted hover:bg-[color-mix(in_srgb,var(--ink)_6%,transparent)] hover:text-ink"}`}><Icon name="list" className="h-[17px] w-[17px]" /></button>
            )}
            <Menu label={t("inbox.sortBy")} items={sortItems} trigger={({ toggle }) => (
              <button type="button" onClick={toggle} aria-label={t("inbox.sortBy")} className="grid h-9 w-9 place-items-center rounded-[10px] text-muted transition hover:bg-[color-mix(in_srgb,var(--ink)_6%,transparent)] hover:text-ink"><Icon name="sort" className="h-[17px] w-[17px]" /></button>
            )} />
          </div>
        </div>
        <label className="flex h-10 items-center gap-2 rounded-[11px] bg-canvas px-3 ring-1 ring-line transition focus-within:bg-surface focus-within:ring-2 focus-within:ring-brand/30">
          <Icon name="search" className="h-4 w-4 text-faint" />
          <input ref={searchRef} value={q} onChange={(e) => setQ(e.target.value)} placeholder={t("inbox.searchPh")} className="min-w-0 flex-1 bg-transparent text-[13.5px] outline-none placeholder:text-faint" />
          {q ? <button type="button" onClick={() => setQ("")} aria-label={t("common.clear")} className="text-faint hover:text-ink"><Icon name="xcircle" className="h-4 w-4" /></button> : <span className="kbd hidden lg:inline-flex">/</span>}
        </label>
        <div className="-mx-4 flex gap-1.5 overflow-x-auto px-4 pb-0.5 [scrollbar-width:none]">
          {quick.map((c) => {
            const on = quickOn(c.key, c.status);
            return (
              <button key={c.key} type="button" onClick={() => setFilters({ ...filters, status: c.status ? (on ? [] : [c.status]) : [] })}
                className={`inline-flex shrink-0 items-center gap-1.5 rounded-full px-3 py-1.5 text-[12.5px] font-semibold transition ${on ? "bg-ink text-surface" : "bg-canvas text-muted ring-1 ring-line hover:text-ink"}`}>
                {c.label}{c.n != null && c.n > 0 && <span className={`rounded-full px-1.5 text-[10.5px] tnum ${on ? "bg-white/20" : "bg-surface text-ink-2 ring-1 ring-line"}`}>{num(c.n)}</span>}
              </button>
            );
          })}
        </div>
        <ActiveChips value={filters} onChange={setFilters} />
      </div>

      {phone
        ? <Sheet open={filterOpen} onClose={closeFilter} title={t("inbox.filters")}><FilterPanel value={filters} onChange={setFilters} total={total} /></Sheet>
        : <Popover open={filterOpen} onClose={closeFilter} anchor={filterBtn} placement="bottom-end" width={360} label={t("inbox.filters")} className="p-4"><FilterPanel value={filters} onChange={setFilters} total={total} /></Popover>}

      <div className="flex items-center justify-between border-y border-line bg-canvas/60 px-4 py-1.5 text-[11.5px] text-faint">
        <span className="tnum">{rows ? t("inbox.count", { n: num(total) }) : "…"}</span>
        <span>{t(`inbox.sort.${filters.sort}`)}</span>
      </div>

      {/* rows */}
      <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain">
        {!rows && <div className="space-y-1 p-2">{Array.from({ length: 8 }, (_, i) => <div key={i} className="flex gap-3 rounded-[12px] p-3"><div className="skeleton h-10 w-10 rounded-full" /><div className="flex-1 space-y-2"><div className="skeleton h-3.5 w-2/5 rounded" /><div className="skeleton h-3 w-4/5 rounded" /></div></div>)}</div>}
        {rows && rows.length === 0 && (
          <div className="flex flex-col items-center px-6 py-16 text-center">
            <span className="mb-4 grid h-14 w-14 place-items-center rounded-[18px] bg-[linear-gradient(135deg,rgb(124_92_255/.12),rgb(34_211_238/.12))] text-violet-600"><Icon name="inbox" className="h-6 w-6" /></span>
            <p className="font-display text-[15px] font-semibold text-ink">{props.activeFilters || filters.q ? t("inbox.noMatch") : t("inbox.empty")}</p>
            <p className="mt-1 max-w-xs text-[13px] text-muted">{props.activeFilters || filters.q ? t("inbox.noMatchHint") : t("inbox.emptyHint")}</p>
            {props.activeFilters || filters.q
              ? <button type="button" onClick={() => { setQ(""); setFilters({ ...filters, status: [], ai: [], channel: [], customer: [], since: "", q: "" }); }} className="mt-4 rounded-[10px] bg-ink px-4 py-2 text-[13px] font-semibold text-surface">{t("inbox.clearAll")}</button>
              : <Link href="/dashboard/ai?tab=channels" className="mt-4 rounded-[10px] bg-ink px-4 py-2 text-[13px] font-semibold text-surface">{t("inbox.connectChannels")}</Link>}
          </div>
        )}
        <ul className="p-1.5">
          <AnimatePresence initial={false}>
            {rows?.map((r) => {
              const on = r.id === selected, chosen = picked.includes(r.id);
              const tags = r.statuses.filter((s) => s !== "OPEN");
              return (
                <motion.li key={r.id} layout={!reduce} initial={reduce ? false : { opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} transition={{ duration: 0.18 }}>
                  <button type="button" onClick={() => (selecting ? setPicked((x) => (chosen ? x.filter((i) => i !== r.id) : [...x, r.id])) : onSelect(r.id))}
                    className={`group relative flex w-full gap-3 rounded-[13px] px-2.5 py-3 text-start transition ${on ? "bg-[color-mix(in_srgb,var(--brand)_8%,var(--surface))] ring-1 ring-brand/20" : "hover:bg-[color-mix(in_srgb,var(--ink)_3.5%,transparent)]"}`}>
                    {selecting && <span className="self-center"><Checkbox checked={chosen} onChange={() => setPicked((x) => (chosen ? x.filter((i) => i !== r.id) : [...x, r.id]))} label={r.customerName ?? ""} /></span>}
                    <ChatAvatar name={r.customerName} channel={r.channel} unread={r.unread && !on} />
                    <span className="min-w-0 flex-1">
                      <span className="flex items-baseline justify-between gap-2">
                        <span className={`truncate text-[14px] ${r.unread ? "font-bold text-ink" : "font-semibold text-ink-2"}`}>{r.customerName || r.customerPhone || t("inbox.guest")}</span>
                        <span className={`shrink-0 text-[11px] tnum ${r.unread ? "font-semibold text-brand" : "text-faint"}`}>{when(r.updatedAt, locale, t("inbox.yesterday"))}</span>
                      </span>
                      <span className="mt-0.5 flex items-center gap-1.5">
                        <Icon name={r.lastFromCustomer ? "arrow" : "send"} className={`h-3 w-3 shrink-0 ${r.lastFromCustomer ? "rotate-[135deg] text-faint rtl:rotate-45" : "text-faint flip-rtl"}`} />
                        <span className={`line-clamp-1 text-[12.5px] ${r.unread ? "text-ink-2" : "text-muted"}`}>
                          {!r.lastFromCustomer && <span className="font-medium text-faint">{r.lastKind === "FOLLOWUP" ? t("inbox.followupShort") : r.lastByOwner ? t("inbox.you") : "AI"}: </span>}
                          {r.lastMessage}
                        </span>
                        {r.unreadCount > 0 && !on && <span className="ms-auto grid h-[18px] min-w-[18px] shrink-0 place-items-center rounded-full bg-brand px-1 text-[10px] font-bold text-white tnum">{r.unreadCount > 9 ? "9+" : num(r.unreadCount)}</span>}
                      </span>
                      {(tags.length > 0 || !r.aiActive) && (
                        <span className="mt-1.5 flex flex-wrap items-center gap-1">
                          {tags.map((s) => <StatusTag key={s} status={s} label={t(`inbox.st.${s}`)} size="xs" />)}
                          {!r.takeover && !r.aiActive && <AiPill active={false} label={t("inbox.aiPaused")} size="xs" />}
                        </span>
                      )}
                    </span>
                    {r.priority === "HIGH" && <span className="absolute inset-y-3 start-0 w-[3px] rounded-full bg-rose-500" aria-label={t("inbox.priorityHigh")} />}
                  </button>
                </motion.li>
              );
            })}
          </AnimatePresence>
        </ul>
        {props.hasMore && <div ref={sentinel} className="flex justify-center py-4">{props.loadingMore && <span className="h-5 w-5 animate-spin rounded-full border-2 border-line-strong border-t-brand" />}</div>}
      </div>

      {/* bulk actions */}
      <AnimatePresence>
        {selecting && picked.length > 0 && (
          <motion.div initial={{ y: 40, opacity: 0 }} animate={{ y: 0, opacity: 1 }} exit={{ y: 40, opacity: 0 }} className="flex items-center gap-1.5 border-t border-line bg-surface px-3 py-2.5">
            <span className="me-auto text-[12.5px] font-semibold tnum">{t("inbox.selected", { n: num(picked.length) })}</span>
            {(["read", "takeover", "resume", "close"] as const).map((a) => (
              <button key={a} type="button" onClick={async () => { await props.onBulk(picked, a); setPicked([]); setSelecting(false); }}
                className="rounded-[9px] px-2.5 py-1.5 text-[12px] font-semibold text-ink-2 ring-1 ring-line transition hover:bg-[color-mix(in_srgb,var(--ink)_5%,transparent)]">{t(`inbox.bulk.${a}`)}</button>
            ))}
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
