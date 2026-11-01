"use client";

import { useEffect, useState, type ReactNode } from "react";
import { useI18n } from "../i18n-provider";
import { Icon } from "../icons";

const cx = (...c: (string | false | null | undefined)[]) => c.filter(Boolean).join(" ");

export type ChipOption = { value: string; label: string; count?: number; dot?: string };

/** One-tap filter chips. multi: several may be on (the value is a list); otherwise tapping the active chip turns it off. */
export function Chips({ options, value, onChange, multi, allLabel }: { options: ChipOption[]; value: string[]; onChange: (v: string[]) => void; multi?: boolean; allLabel?: string }) {
  const toggle = (v: string) => {
    const on = value.includes(v);
    onChange(multi ? (on ? value.filter((x) => x !== v) : [...value, v]) : on ? [] : [v]);
  };
  return (
    <div className="-mx-1 flex gap-1.5 overflow-x-auto px-1 pb-1 [scrollbar-width:none]">
      {allLabel && (
        <button type="button" onClick={() => onChange([])} className={cx("chip", value.length === 0 && "chip-on")}>{allLabel}</button>
      )}
      {options.map((o) => {
        const on = value.includes(o.value);
        return (
          <button key={o.value} type="button" aria-pressed={on} onClick={() => toggle(o.value)} className={cx("chip", on && "chip-on")}>
            {o.dot && <span className="h-2 w-2 rounded-full" style={{ background: o.dot }} />}
            {o.label}
            {o.count !== undefined && <span className={cx("rounded-full px-1.5 text-[11px]", on ? "bg-white/25" : "bg-slate-200/80 text-slate-600")}>{o.count}</span>}
          </button>
        );
      })}
    </div>
  );
}

/** "More filters" button with a count, opening a side drawer (bottom sheet on a phone). */
export function MoreFilters({ count, children, onClear }: { count: number; children: ReactNode; onClear: () => void }) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  useEffect(() => {
    if (!open) return;
    const h = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [open]);
  return (
    <>
      <button type="button" onClick={() => setOpen(true)} className={cx("inline-flex min-h-11 items-center gap-2 rounded-xl border px-3.5 py-2 text-sm font-semibold shadow-sm transition", count ? "border-brand/40 bg-brand-soft text-brand-dark" : "border-slate-200 bg-white text-slate-700 hover:border-slate-300")}>
        <Icon name="settings" className="h-4 w-4" />{t("filter.more")}
        {count > 0 && <span className="grid h-5 min-w-5 place-items-center rounded-full bg-brand px-1 text-[11px] text-white">{count}</span>}
      </button>
      {open && (
        <div className="fixed inset-0 z-50 flex animate-fade-in items-end justify-end bg-slate-900/40 backdrop-blur-sm sm:items-stretch" onClick={() => setOpen(false)}>
          <div onClick={(e) => e.stopPropagation()} className="flex max-h-[88dvh] w-full animate-slide-up flex-col rounded-t-3xl bg-white shadow-2xl sm:max-h-none sm:w-[400px] sm:rounded-none sm:animate-fade-in">
            <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4">
              <h2 className="text-lg font-bold text-ink">{t("filter.title")}</h2>
              <button type="button" onClick={() => setOpen(false)} className="grid h-10 w-10 place-items-center rounded-full hover:bg-slate-100" aria-label="Close"><Icon name="close" className="h-5 w-5" /></button>
            </div>
            <div className="flex-1 space-y-5 overflow-y-auto px-5 py-4">{children}</div>
            <div className="flex gap-2 border-t border-slate-100 px-5 py-4 pb-[calc(1rem+var(--safe-b))]">
              <button type="button" onClick={() => { onClear(); }} className="flex-1 rounded-xl border border-slate-200 px-4 py-2.5 text-sm font-semibold text-slate-700 hover:bg-slate-50">{t("filter.clearAll")}</button>
              <button type="button" onClick={() => setOpen(false)} className="flex-1 rounded-xl bg-brand px-4 py-2.5 text-sm font-semibold text-white">{t("filter.show")}</button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}

/** A labelled group inside the drawer. */
export function FilterGroup({ label, children }: { label: string; children: ReactNode }) {
  return <div><p className="mb-2 text-xs font-bold uppercase tracking-wider text-slate-500">{label}</p>{children}</div>;
}

/** The filters that are on, as removable chips, plus "clear all". */
export function ActiveFilters({ items, onClearAll }: { items: { key: string; label: string; onRemove: () => void }[]; onClearAll: () => void }) {
  const { t } = useI18n();
  if (items.length === 0) return null;
  return (
    <div className="mb-3 flex flex-wrap items-center gap-2 animate-fade-in">
      {items.map((i) => (
        <span key={i.key} className="inline-flex items-center gap-1 rounded-full bg-brand-soft py-1 pe-1 ps-3 text-xs font-semibold text-brand-dark">
          {i.label}
          <button type="button" onClick={i.onRemove} className="grid h-5 w-5 place-items-center rounded-full hover:bg-brand/20" aria-label={t("filter.clear")}><Icon name="close" className="h-3 w-3" /></button>
        </span>
      ))}
      <button type="button" onClick={onClearAll} className="text-xs font-semibold text-slate-500 underline-offset-4 hover:underline">{t("filter.clearAll")}</button>
    </div>
  );
}

/** Search box with an icon, used at the start of every filter row. */
export function SearchBox({ value, onChange, placeholder }: { value: string; onChange: (v: string) => void; placeholder: string }) {
  return (
    <label className="relative block min-w-0 flex-1 sm:min-w-[220px]">
      <Icon name="search" className="pointer-events-none absolute top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400 ltr:left-3.5 rtl:right-3.5" />
      <input value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder} type="search"
        className="min-h-11 w-full rounded-xl border border-slate-200 bg-white py-2.5 text-sm shadow-sm outline-none transition placeholder:text-slate-400 hover:border-slate-300 focus:border-brand focus:ring-4 focus:ring-brand/15 ltr:pl-10 ltr:pr-3 rtl:pl-3 rtl:pr-10" />
    </label>
  );
}
