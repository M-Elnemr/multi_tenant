"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useI18n } from "./i18n-provider";
import { Icon } from "./icons";

/** yyyy-mm-dd strings (the shop's days); either end may be empty (open). */
export type DateRange = { from?: string; to?: string };

const pad = (n: number) => String(n).padStart(2, "0");
const key = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const parse = (s?: string) => (s ? new Date(`${s}T00:00:00`) : null);
const addDays = (d: Date, n: number) => { const x = new Date(d); x.setDate(x.getDate() + n); return x; };
const startOfMonth = (d: Date) => new Date(d.getFullYear(), d.getMonth(), 1);
const addMonths = (d: Date, n: number) => new Date(d.getFullYear(), d.getMonth() + n, 1);

type Preset = { id: string; range: () => DateRange };
function presets(): Preset[] {
  const today = new Date();
  const t = key(today);
  return [
    { id: "today", range: () => ({ from: t, to: t }) },
    { id: "yesterday", range: () => { const y = key(addDays(today, -1)); return { from: y, to: y }; } },
    { id: "last7", range: () => ({ from: key(addDays(today, -6)), to: t }) },
    { id: "last30", range: () => ({ from: key(addDays(today, -29)), to: t }) },
    { id: "thisMonth", range: () => ({ from: key(startOfMonth(today)), to: t }) },
    { id: "lastMonth", range: () => { const s = addMonths(startOfMonth(today), -1); return { from: key(s), to: key(addDays(startOfMonth(today), -1)) }; } },
    { id: "thisYear", range: () => ({ from: `${today.getFullYear()}-01-01`, to: t }) },
  ];
}

/** Future-facing presets for schedules (next 7 days, next 30 days...). */
function futurePresets(): Preset[] {
  const today = new Date();
  const t = key(today);
  return [
    { id: "today", range: () => ({ from: t, to: t }) },
    { id: "tomorrow", range: () => { const x = key(addDays(today, 1)); return { from: x, to: x }; } },
    { id: "next7", range: () => ({ from: t, to: key(addDays(today, 6)) }) },
    { id: "next30", range: () => ({ from: t, to: key(addDays(today, 29)) }) },
    { id: "thisMonth", range: () => ({ from: key(startOfMonth(today)), to: key(addDays(addMonths(startOfMonth(today), 1), -1)) }) },
  ];
}

/**
 * The date filter used on every list: a chip that opens presets (today, last 7 days, this month...) and a two-month calendar to pick any range.
 * Works right-to-left, closes on Escape / outside click, and on a phone opens as a bottom sheet.
 */
export function DateRangePicker({ value, onChange, future, label, className }: { value: DateRange; onChange: (r: DateRange) => void; future?: boolean; label?: string; className?: string }) {
  const { t, locale } = useI18n();
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState<DateRange>(value);
  const [month, setMonth] = useState(() => startOfMonth(parse(value.from) ?? new Date()));
  const box = useRef<HTMLDivElement>(null);
  const list = useMemo(() => (future ? futurePresets() : presets()), [future]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    const onDown = (e: MouseEvent) => { if (box.current && !box.current.contains(e.target as Node)) setOpen(false); };
    window.addEventListener("keydown", onKey);
    window.addEventListener("mousedown", onDown);
    return () => { window.removeEventListener("keydown", onKey); window.removeEventListener("mousedown", onDown); };
  }, [open]);

  const fmt = (s?: string) => (s ? new Intl.DateTimeFormat(locale === "ar" ? "ar-EG" : "en-GB", { day: "numeric", month: "short", year: parse(s)!.getFullYear() === new Date().getFullYear() ? undefined : "numeric" }).format(parse(s)!) : "");
  const activePreset = list.find((p) => { const r = p.range(); return r.from === value.from && r.to === value.to; });
  const text = activePreset ? t(`range.${activePreset.id}`) : value.from || value.to
    ? value.from === value.to ? fmt(value.from) : `${fmt(value.from) || "…"} – ${fmt(value.to) || "…"}` : (label ?? t("range.any"));
  const has = !!(value.from || value.to);

  const pick = (d: string) => {
    if (!draft.from || (draft.from && draft.to)) setDraft({ from: d, to: undefined });
    else if (d < draft.from) setDraft({ from: d, to: draft.from });
    else setDraft({ from: draft.from, to: d });
  };
  const apply = (r: DateRange) => { onChange(r); setOpen(false); };
  // the panel opens from the button's start edge; near the far edge of the screen it opens from the end edge instead
  const [flip, setFlip] = useState(false);
  const toggleOpen = () => {
    const r = box.current?.getBoundingClientRect();
    if (r) {
      const w = Math.min(640, window.innerWidth - 32), rtl = document.documentElement.dir === "rtl";
      setFlip(rtl ? r.right - w < 16 : r.left + w > window.innerWidth - 16);
    }
    setOpen(!open);
  };

  return (
    <div ref={box} className={`relative ${className ?? ""}`}>
      <button type="button" onClick={() => { setDraft(value); setMonth(startOfMonth(parse(value.from) ?? new Date())); toggleOpen(); }} aria-expanded={open}
        className={`inline-flex min-h-11 w-full items-center gap-2 rounded-xl border px-3.5 py-2 text-sm font-semibold shadow-sm transition sm:w-auto ${has ? "border-brand/40 bg-brand-soft text-brand-dark" : "border-slate-200 bg-white text-slate-700 hover:border-slate-300"}`}>
        <Icon name="calendar" className="h-4 w-4 shrink-0" />
        <span className="truncate">{text}</span>
        {has ? (
          <span role="button" tabIndex={0} aria-label={t("filter.clear")} onClick={(e) => { e.stopPropagation(); onChange({}); }} onKeyDown={(e) => { if (e.key === "Enter") { e.stopPropagation(); onChange({}); } }}
            className="ms-auto grid h-5 w-5 place-items-center rounded-full bg-brand/15 hover:bg-brand/25"><Icon name="close" className="h-3 w-3" /></span>
        ) : <Icon name="arrow" className="ms-auto h-3.5 w-3.5 rotate-90 opacity-50" />}
      </button>
      {open && (
        <>
          <div className="fixed inset-0 z-40 bg-slate-900/30 sm:hidden" onClick={() => setOpen(false)} />
          <div className={`fixed inset-x-0 bottom-0 z-50 max-h-[85dvh] animate-slide-up overflow-y-auto rounded-t-3xl bg-white p-4 pb-[calc(1rem+var(--safe-b))] shadow-2xl sm:absolute sm:inset-x-auto sm:bottom-auto sm:top-full sm:mt-2 sm:w-[640px] sm:max-w-[calc(100vw-2rem)] sm:animate-scale-in sm:rounded-2xl sm:pb-4 ${flip ? "ltr:sm:right-0 rtl:sm:left-0" : "ltr:sm:left-0 rtl:sm:right-0"}`}>
            <div className="flex flex-col gap-4 sm:flex-row">
              <div className="flex gap-2 overflow-x-auto pb-1 sm:w-40 sm:flex-col sm:overflow-visible sm:border-e sm:border-slate-100 sm:pe-3">
                {list.map((p) => (
                  <button key={p.id} type="button" onClick={() => apply(p.range())}
                    className={`whitespace-nowrap rounded-lg px-3 py-2 text-start text-sm font-medium transition ${activePreset?.id === p.id ? "bg-brand text-white" : "bg-slate-50 text-slate-700 hover:bg-brand-soft hover:text-brand-dark sm:bg-transparent"}`}>{t(`range.${p.id}`)}</button>
                ))}
              </div>
              <div className="min-w-0 flex-1">
                <div className="mb-2 flex items-center justify-between">
                  <button type="button" onClick={() => setMonth(addMonths(month, -1))} className="grid h-9 w-9 place-items-center rounded-full hover:bg-slate-100" aria-label={t("range.prevMonth")}><Icon name="arrow" className="h-4 w-4 ltr:rotate-180" /></button>
                  <span className="text-xs font-semibold text-slate-500">{draft.from && !draft.to ? t("range.pickEnd") : t("range.pickStart")}</span>
                  <button type="button" onClick={() => setMonth(addMonths(month, 1))} className="grid h-9 w-9 place-items-center rounded-full hover:bg-slate-100" aria-label={t("range.nextMonth")}><Icon name="arrow" className="h-4 w-4 rtl:rotate-180" /></button>
                </div>
                <div className="grid gap-4 sm:grid-cols-2">
                  <Month month={month} draft={draft} onPick={pick} locale={locale} />
                  <div className="hidden sm:block"><Month month={addMonths(month, 1)} draft={draft} onPick={pick} locale={locale} /></div>
                </div>
                <div className="mt-3 flex items-center justify-between gap-2 border-t border-slate-100 pt-3">
                  <span className="text-sm text-slate-600">{draft.from ? `${fmt(draft.from)}${draft.to && draft.to !== draft.from ? ` – ${fmt(draft.to)}` : ""}` : "—"}</span>
                  <div className="flex gap-2">
                    <button type="button" onClick={() => apply({})} className="rounded-lg px-3 py-2 text-sm font-semibold text-slate-600 hover:bg-slate-100">{t("filter.clear")}</button>
                    <button type="button" disabled={!draft.from} onClick={() => apply({ from: draft.from, to: draft.to ?? draft.from })} className="rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white disabled:opacity-40">{t("range.apply")}</button>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

function Month({ month, draft, onPick, locale }: { month: Date; draft: DateRange; onPick: (d: string) => void; locale: string }) {
  const intl = locale === "ar" ? "ar-EG" : "en-GB";
  const title = new Intl.DateTimeFormat(intl, { month: "long", year: "numeric" }).format(month);
  // Saturday first (the Egyptian week)
  const firstDow = (month.getDay() + 1) % 7;
  const days = new Date(month.getFullYear(), month.getMonth() + 1, 0).getDate();
  const names = Array.from({ length: 7 }, (_, i) => new Intl.DateTimeFormat(intl, { weekday: "narrow" }).format(new Date(2024, 0, 6 + i)));
  const today = key(new Date());
  const cells: (string | null)[] = [...Array(firstDow).fill(null), ...Array.from({ length: days }, (_, i) => key(new Date(month.getFullYear(), month.getMonth(), i + 1)))];
  return (
    <div>
      <p className="mb-2 text-center text-sm font-bold text-ink">{title}</p>
      <div className="grid grid-cols-7 gap-y-1 text-center text-[11px] font-semibold text-slate-400">{names.map((n, i) => <span key={i}>{n}</span>)}</div>
      <div className="mt-1 grid grid-cols-7 gap-y-1 text-center text-sm">
        {cells.map((d, i) => {
          if (!d) return <span key={i} />;
          const inRange = draft.from && draft.to && d > draft.from && d < draft.to;
          const edge = d === draft.from || d === draft.to;
          return (
            <button key={i} type="button" onClick={() => onPick(d)}
              className={`mx-auto grid h-9 w-full place-items-center text-sm transition ${edge ? "rounded-lg bg-brand font-bold text-white" : inRange ? "bg-brand-soft text-brand-dark" : "rounded-lg hover:bg-slate-100"} ${d === today && !edge ? "font-bold text-brand underline underline-offset-4" : ""}`}>
              {Number(d.slice(8))}
            </button>
          );
        })}
      </div>
    </div>
  );
}

/** Adds ?from=&to= to a query. */
export function rangeQuery(qs: URLSearchParams, r: DateRange) {
  if (r.from) qs.set("from", r.from);
  if (r.to) qs.set("to", r.to);
  return qs;
}
