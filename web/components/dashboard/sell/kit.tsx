"use client";

import Link from "next/link";
import { motion, useReducedMotion } from "motion/react";
import { useEffect, useRef, useState, useSyncExternalStore, type ReactNode } from "react";
import { useI18n } from "@/components/i18n-provider";
import { Icon, type IconName } from "@/components/icons";
import { Menu, Popover, Sheet, type MenuItem } from "@/components/overlay";
import { cx, Skeleton } from "@/components/ui";

/**
 * Small building blocks shared by the selling screens (orders, returns, shipping, invoices, POS hub), on top of ui.tsx / overlay.tsx.
 * They follow components/dashboard/DESIGN.md: hairline cards, ink chips, sticky table headers, filters in a popover (a sheet on phones).
 */

const subscribePhone = (cb: () => void) => { const m = window.matchMedia("(max-width: 639px)"); m.addEventListener("change", cb); return () => m.removeEventListener("change", cb); };
/** True under 640px (phones). Always false during the server render. */
export function usePhone() {
  return useSyncExternalStore(subscribePhone, () => window.matchMedia("(max-width: 639px)").matches, () => false);
}

/** A hairline card (18px radius). */
export function Panel({ children, className, as: Tag = "section" }: { children: ReactNode; className?: string; as?: "section" | "div" }) {
  return <Tag className={cx("rounded-[18px] bg-surface ring-1 ring-line", className)}>{children}</Tag>;
}

/** A card with a 15px title row, an optional icon, a side note and an action on the end side. */
export function Block({ title, icon, aside, action, children, className, flush }: { title: ReactNode; icon?: IconName; aside?: ReactNode; action?: ReactNode; children: ReactNode; className?: string; flush?: boolean }) {
  return (
    <Panel className={className}>
      <div className="flex min-h-12 items-center justify-between gap-3 px-5 pt-4">
        <h2 className="flex min-w-0 items-center gap-2 font-display text-[15px] font-semibold text-ink">
          {icon && <Icon name={icon} className="h-4 w-4 shrink-0 text-faint" />}
          <span className="truncate">{title}</span>
          {aside != null && <span className="text-[12px] font-normal text-faint tnum">{aside}</span>}
        </h2>
        {action}
      </div>
      <div className={flush ? "mt-2" : "px-5 pb-5 pt-3"}>{children}</div>
    </Panel>
  );
}

/** Label / value lines for side columns. */
export function Facts({ items }: { items: ([ReactNode, ReactNode] | false | null | undefined)[] }) {
  return (
    <dl className="divide-y divide-line text-[13.5px]">
      {items.filter(Boolean).map((it, i) => {
        const [k, v] = it as [ReactNode, ReactNode];
        return <div key={i} className="flex min-w-0 items-start justify-between gap-3 py-2 first:pt-0 last:pb-0"><dt className="shrink-0 text-muted">{k}</dt><dd className="min-w-0 break-words text-end font-medium text-ink tnum">{v}</dd></div>;
      })}
    </dl>
  );
}

/** The toolbar card that holds search, filters, sort and the date range. */
export function Bar({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cx("flex flex-wrap items-center gap-2 rounded-[16px] bg-surface p-2 ring-1 ring-line", className)}>{children}</div>;
}

/** Search as you type (applies 300 ms after the last key). */
export function SearchField({ value, onChange, placeholder, className }: { value: string; onChange: (v: string) => void; placeholder: string; className?: string }) {
  const [q, setQ] = useState(value);
  const last = useRef(value);
  useEffect(() => {
    if (q === last.current) return;
    const id = setTimeout(() => { last.current = q; onChange(q); }, 300);
    return () => clearTimeout(id);
  }, [q, onChange]);
  return (
    <label className={cx("relative block min-w-0 flex-1 basis-full sm:basis-auto sm:min-w-[220px]", className)}>
      <Icon name="search" className="pointer-events-none absolute start-3 top-1/2 h-4 w-4 -translate-y-1/2 text-faint" />
      <input type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder={placeholder}
        className="h-10 w-full rounded-[11px] bg-canvas pe-3 ps-9 text-[13.5px] text-ink outline-none ring-1 ring-line transition placeholder:text-faint focus:bg-surface focus:ring-2 focus:ring-brand/30" />
    </label>
  );
}

/** A toggle chip (ink when on). */
export function Chip({ on, onClick, children, count }: { on: boolean; onClick: () => void; children: ReactNode; count?: number }) {
  return (
    <button type="button" onClick={onClick} aria-pressed={on}
      className={cx("inline-flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-full px-3 py-1.5 text-[12.5px] font-semibold ring-1 transition active:scale-[.97] pointer-coarse:min-h-10",
        on ? "bg-ink text-surface ring-ink" : "bg-surface text-ink-2 ring-line-strong hover:text-ink hover:ring-faint")}>
      {children}
      {count !== undefined && <span className={cx("rounded-full px-1.5 text-[11px] tnum", on ? "bg-surface/20" : "bg-[color-mix(in_srgb,var(--ink)_7%,transparent)]")}>{count}</span>}
    </button>
  );
}

/** A horizontal row of chips that scrolls on phones instead of wrapping into many lines. */
export function ChipRow({ children }: { children: ReactNode }) {
  return <div className="-mx-1 flex gap-1.5 overflow-x-auto px-1 pb-0.5 [scrollbar-width:none]">{children}</div>;
}

/** A labelled group inside the filter panel. */
export function FilterGroup({ label, children }: { label: string; children: ReactNode }) {
  return (
    <section className="py-3 first:pt-0">
      <p className="mb-2 text-[11.5px] font-semibold uppercase tracking-wide text-faint">{label}</p>
      <div className="flex flex-wrap gap-1.5">{children}</div>
    </section>
  );
}

/** "Filters" button with a count; opens a popover on desktop and a bottom sheet on phones. */
export function FilterButton({ count, onClear, children }: { count: number; onClear: () => void; children: ReactNode }) {
  const { t } = useI18n();
  const phone = usePhone();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLButtonElement>(null);
  const close = () => setOpen(false);
  const footer = (
    <div className="flex gap-2">
      <button type="button" onClick={onClear} className="h-10 flex-1 rounded-[11px] text-[13px] font-semibold text-ink-2 ring-1 ring-line-strong hover:bg-canvas">{t("filter.clearAll")}</button>
      <button type="button" onClick={close} className="h-10 flex-1 rounded-[11px] bg-ink text-[13px] font-semibold text-surface hover:bg-ink-2">{t("filter.show")}</button>
    </div>
  );
  return (
    <>
      <button ref={ref} type="button" onClick={() => setOpen((v) => !v)} aria-expanded={open}
        className={cx("inline-flex h-10 shrink-0 items-center gap-2 rounded-[11px] px-3.5 text-[13px] font-semibold ring-1 transition",
          count ? "bg-brand-soft text-brand ring-brand/30" : "bg-surface text-ink-2 ring-line-strong hover:text-ink")}>
        <Icon name="filter" className="h-4 w-4" />{t("filter.title")}
        {count > 0 && <span className="grid h-5 min-w-5 place-items-center rounded-full bg-brand px-1 text-[11px] text-white tnum">{count}</span>}
      </button>
      {phone ? (
        <Sheet open={open} onClose={close} title={t("filter.title")} footer={footer}><div className="divide-y divide-line">{children}</div></Sheet>
      ) : (
        <Popover open={open} onClose={close} anchor={ref} placement="bottom-end" width={380} label={t("filter.title")}>
          <div className="max-h-[min(70vh,560px)] divide-y divide-line overflow-y-auto px-3 pb-1 pt-3">{children}</div>
          <div className="border-t border-line p-2">{footer}</div>
        </Popover>
      )}
    </>
  );
}

/** A compact button that opens a menu (sort, more actions). */
export function MenuButton({ icon, label, items, text, placement }: { icon: IconName; label: string; items: MenuItem[]; text?: ReactNode; placement?: "bottom-start" | "bottom-end" }) {
  return (
    <Menu label={label} items={items} placement={placement} trigger={({ toggle, open }) => (
      <button type="button" onClick={toggle} aria-label={text ? undefined : label} aria-expanded={open}
        className={cx("inline-flex h-10 shrink-0 items-center gap-2 rounded-[11px] text-[13px] font-semibold text-ink-2 ring-1 ring-line-strong transition hover:text-ink", text ? "px-3.5" : "w-10 justify-center", open && "bg-canvas")}>
        <Icon name={icon} className="h-4 w-4" />{text && <span className="max-w-[10rem] truncate">{text}</span>}
      </button>
    )} />
  );
}

/** The "⋯" actions of one table row (the click does not reach the row). */
export function RowMenu({ items, label }: { items: MenuItem[]; label: string }) {
  return (
    <span onClick={(e) => e.stopPropagation()} className="inline-flex">
      <Menu label={label} items={items} trigger={({ toggle, open }) => (
        <button type="button" onClick={toggle} aria-label={label} aria-expanded={open}
          className={cx("grid h-8 w-8 place-items-center rounded-[9px] text-muted transition hover:bg-[color-mix(in_srgb,var(--ink)_6%,transparent)] hover:text-ink pointer-coarse:h-10 pointer-coarse:w-10", open && "bg-canvas text-ink")}>
          <Icon name="menu" className="h-4 w-4" />
        </button>
      )} />
    </span>
  );
}

/** The filters that are on, as removable chips. */
export function ActiveChips({ items, onClearAll }: { items: { key: string; label: ReactNode; onRemove: () => void }[]; onClearAll: () => void }) {
  const { t } = useI18n();
  if (items.length === 0) return null;
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      {items.map((c) => (
        <span key={c.key} className="inline-flex animate-scale-in items-center gap-1 rounded-full bg-brand-soft py-0.5 pe-1 ps-2.5 text-[11.5px] font-semibold text-brand">
          {c.label}
          <button type="button" onClick={c.onRemove} aria-label={t("common.remove")} className="grid h-5 w-5 place-items-center rounded-full hover:bg-brand/15"><Icon name="close" className="h-3 w-3" /></button>
        </span>
      ))}
      <button type="button" onClick={onClearAll} className="px-1 text-[11.5px] font-semibold text-muted hover:text-ink">{t("filter.clearAll")}</button>
    </div>
  );
}

export type Col = { label: ReactNode; end?: boolean; className?: string };

/**
 * A list table: the header sticks under the top bar on wide screens, rows highlight on hover and can be clicked, and under 640px every row
 * turns into a stacked card whose cells are labelled with the column names (data-label, from the header text).
 */
export function DataTable({ cols, children, loading, skeletonRows = 6 }: { cols: Col[]; children?: ReactNode; loading?: boolean; skeletonRows?: number }) {
  const ref = useRef<HTMLTableElement>(null);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const labels = Array.from(el.tHead?.rows[0]?.cells ?? []).map((c) => c.textContent?.trim() ?? "");
    el.querySelectorAll("tbody > tr").forEach((tr) => Array.from((tr as HTMLTableRowElement).cells).forEach((td, i) => td.setAttribute("data-label", labels[i] ?? "")));
  });
  return (
    <div className="overflow-x-auto rounded-[18px] bg-surface ring-1 ring-line xl:overflow-visible">
      <table ref={ref} className="table-cards w-full text-start text-[13.5px]">
        <thead>
          <tr>
            {cols.map((c, i) => (
              <th key={i} className={cx("sticky top-0 z-10 whitespace-nowrap bg-surface-2 px-4 py-2.5 text-[11.5px] font-semibold text-muted shadow-[inset_0_-1px_0_var(--line)] first:rounded-ss-[18px] first:ps-5 last:rounded-se-[18px] last:pe-5 xl:top-14",
                c.end ? "text-end" : "text-start", c.className)}>{c.label}</th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-line [&>tr>td:first-child]:ps-5 [&>tr>td:last-child]:pe-5">
          {loading
            ? Array.from({ length: skeletonRows }).map((_, r) => (
              <tr key={`sk${r}`} aria-hidden style={{ opacity: 1 - r * 0.12 }}>
                {cols.map((c, i) => <td key={i} className="px-4 py-3.5"><Skeleton className={cx("h-3", i === 0 ? "w-28" : c.end ? "ms-auto w-12" : "w-20")} /></td>)}
              </tr>
            ))
            : children}
        </tbody>
      </table>
    </div>
  );
}

/** A clickable table row (Enter opens it too). */
export function Row({ children, onOpen, className }: { children: ReactNode; onOpen?: () => void; className?: string }) {
  return (
    <tr onClick={onOpen} onKeyDown={onOpen ? (e) => { if (e.key === "Enter" && e.target === e.currentTarget) onOpen(); } : undefined} tabIndex={onOpen ? 0 : undefined}
      className={cx("transition-colors hover:bg-[color-mix(in_srgb,var(--ink)_3%,transparent)] focus-visible:bg-[color-mix(in_srgb,var(--ink)_4%,transparent)] focus-visible:outline-none", onOpen && "cursor-pointer", className)}>
      {children}
    </tr>
  );
}

export const Cell = ({ children, className, end }: { children?: ReactNode; className?: string; end?: boolean }) => <td className={cx("px-4 py-3 align-middle", end && "text-end", className)}>{children}</td>;

/** Fades and lifts content in once (no motion when the visitor asked for less). */
export function Rise({ children, className, delay = 0 }: { children: ReactNode; className?: string; delay?: number }) {
  const reduce = useReducedMotion();
  return <motion.div className={className} initial={reduce ? false : { opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.22, delay: reduce ? 0 : delay, ease: [0.2, 0.8, 0.2, 1] }}>{children}</motion.div>;
}

/** An outlined link styled like a secondary button. */
export function LinkButton({ href, icon, children, primary, external }: { href: string; icon?: IconName; children: ReactNode; primary?: boolean; external?: boolean }) {
  const cls = cx("inline-flex h-10 shrink-0 items-center gap-1.5 rounded-[11px] px-3.5 text-[13px] font-semibold transition", primary ? "bg-ink text-surface hover:bg-ink-2" : "text-ink-2 ring-1 ring-line-strong hover:bg-surface hover:text-ink");
  const body = <>{icon && <Icon name={icon} className="h-4 w-4" />}{children}</>;
  return external ? <a href={href} target="_blank" rel="noreferrer" className={cls}>{body}</a> : <Link href={href} className={cls}>{body}</Link>;
}

/** A thin attention strip (amber by default) with an icon, a sentence and an optional action. */
export function Notice({ tone = "amber", icon = "alert", children, action, className }: { tone?: "amber" | "red" | "sky" | "emerald"; icon?: IconName; children: ReactNode; action?: ReactNode; className?: string }) {
  const tones = { amber: "bg-amber-50 text-amber-900 ring-amber-600/20", red: "bg-red-50 text-red-800 ring-red-600/15", sky: "bg-sky-50 text-sky-900 ring-sky-600/15", emerald: "bg-emerald-50 text-emerald-900 ring-emerald-600/15" }[tone];
  return (
    <div className={cx("flex flex-wrap items-center gap-x-3 gap-y-2 rounded-[14px] px-4 py-3 text-[13px] ring-1 ring-inset", tones, className)}>
      <Icon name={icon} className="h-4 w-4 shrink-0" />
      <div className="min-w-0 flex-1">{children}</div>
      {action}
    </div>
  );
}
