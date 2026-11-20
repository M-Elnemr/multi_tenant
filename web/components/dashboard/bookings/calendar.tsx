"use client";

import { useMemo, useState } from "react";
import { api } from "@/lib/client";
import { money } from "@/lib/format";
import { useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { Menu } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { Button, ErrorText, Loading } from "@/components/ui";
import { errText } from "@/components/dashboard/marketing/kit";
import { BookingStatus, type Booking } from "./common";
import { Decide } from "./appointments";

type View = "day" | "week" | "month";

const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
const parse = (s: string) => { const [y, m, d] = s.split("-").map(Number); return new Date(y, m - 1, d); };
const addDays = (s: string, n: number) => { const d = parse(s); d.setDate(d.getDate() + n); return iso(d); };
/** The Egyptian week starts on Saturday. */
const weekStart = (s: string) => { const d = parse(s); const back = (d.getDay() + 1) % 7; d.setDate(d.getDate() - back); return iso(d); };
const monthRange = (s: string) => { const d = parse(s); const first = new Date(d.getFullYear(), d.getMonth(), 1), last = new Date(d.getFullYear(), d.getMonth() + 1, 0); return { first: iso(first), last: iso(last) }; };

const TONE: Record<string, string> = { PENDING: "border-amber-300 bg-amber-50", CONFIRMED: "border-emerald-300 bg-emerald-50", COMPLETED: "border-sky-300 bg-sky-50", NO_SHOW: "border-violet-300 bg-violet-50" };

/** The diary: one day, a week or a month of appointments with what the owner does most (confirm, move, cancel, done, no-show). Day and week run as one list per day, which is also what works on a phone. */
export function Calendar({ canManage, onChanged }: { canManage: boolean; onChanged: () => void }) {
  const { t, locale, timezone, currency } = useI18n();
  const toast = useToast();
  const todayIso = new Intl.DateTimeFormat("en-CA", { timeZone: timezone }).format(new Date());
  const [view, setView] = useState<View>("day");
  const [anchor, setAnchor] = useState(todayIso);
  const [dlg, setDlg] = useState<{ b: Booking; mode: "decline" | "cancel" | "reschedule" } | null>(null);
  const range = useMemo(() => {
    if (view === "day") return { from: anchor, to: anchor, days: [anchor] };
    if (view === "week") { const s = weekStart(anchor); return { from: s, to: addDays(s, 6), days: Array.from({ length: 7 }, (_, i) => addDays(s, i)) }; }
    const { first, last } = monthRange(anchor); const s = weekStart(first); const end = addDays(weekStart(last), 6);
    const n = Math.round((parse(end).getTime() - parse(s).getTime()) / 86400000) + 1;
    return { from: s, to: end, days: Array.from({ length: n }, (_, i) => addDays(s, i)) };
  }, [view, anchor]);
  const { data, loading, error, reload } = useApi<Booking[]>(`store/bookings/calendar?from=${range.from}&to=${range.to}`);
  const by = useMemo(() => { const m: Record<string, Booking[]> = {}; (data ?? []).forEach((b) => { (m[b.date] ??= []).push(b); }); return m; }, [data]);
  const after = () => { void reload(); onChanged(); };
  const act = useAction(async (id: string, what: string) => {
    try { await api(`store/bookings/${id}/${what}`, { body: {} }); toast(t("common.saved")); after(); } catch (e) { toast(errText(e, t("inbox.saveFailed")), { tone: "error" }); }
  });
  const step = (dir: number) => setAnchor(view === "day" ? addDays(anchor, dir) : view === "week" ? addDays(anchor, 7 * dir) : (() => { const d = parse(anchor); d.setMonth(d.getMonth() + dir, 1); return iso(d); })());
  const fmt = (s: string, o: Intl.DateTimeFormatOptions) => new Intl.DateTimeFormat(locale === "ar" ? "ar-EG" : "en-GB", { ...o, timeZone: "UTC" }).format(new Date(s + "T00:00:00Z"));
  const title = view === "day" ? fmt(anchor, { weekday: "long", day: "numeric", month: "long" }) : view === "week" ? `${fmt(range.from, { day: "numeric", month: "short" })} – ${fmt(range.to, { day: "numeric", month: "short" })}` : fmt(anchor, { month: "long", year: "numeric" });

  const card = (b: Booking) => (
    <li key={b.id} className={`rounded-xl border-s-4 p-3 ${TONE[b.status] ?? "border-line bg-surface"}`}>
      <div className="flex items-start gap-2">
        <div className="min-w-0 flex-1">
          <p className="flex flex-wrap items-center gap-x-2 text-[14px] font-bold text-ink"><span className="tnum" dir="ltr">{b.time}–{b.endTime}</span><span className="truncate">{b.serviceName}</span></p>
          <p className="truncate text-[13px] text-muted">{b.customerName} · <span dir="ltr">{b.phone}</span></p>
          <p className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-[11.5px] text-faint"><BookingStatus status={b.status} /><span>{t(`bk.src.${b.source}`)}</span><span className="tnum">{money(b.priceMinor, b.currency || currency, locale)}</span>{b.locationMode === "HOME" && <span>🏠 {b.address}</span>}</p>
        </div>
        {canManage && (b.status === "PENDING" || b.status === "CONFIRMED") && (
          <div className="flex shrink-0 items-center gap-1">
            {b.status === "PENDING" && <Button size="sm" variant="ink" onClick={() => void act.run(b.id, "confirm")}><Icon name="check" className="h-4 w-4" /></Button>}
            <Menu label={t("common.more")} items={[
              ...(b.status === "PENDING" ? [{ label: t("bk.decline"), icon: "close" as const, onSelect: () => setDlg({ b, mode: "decline" }) }] : []),
              ...(b.status === "CONFIRMED" ? [{ label: t("bk.complete"), icon: "check" as const, onSelect: () => void act.run(b.id, "complete") }, { label: t("bk.noShow"), icon: "alert" as const, onSelect: () => void act.run(b.id, "no-show") }] : []),
              { label: t("bk.reschedule"), icon: "calendar" as const, onSelect: () => setDlg({ b, mode: "reschedule" }) },
              { label: t("bk.cancel"), icon: "close" as const, onSelect: () => setDlg({ b, mode: "cancel" }) },
            ]} trigger={({ toggle }) => <button type="button" onClick={toggle} aria-label={t("common.more")} className="grid h-11 w-11 place-items-center rounded-[10px] text-muted hover:bg-black/5"><Icon name="more" className="h-5 w-5" /></button>} />
          </div>
        )}
      </div>
    </li>
  );

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <div className="flex items-center gap-1">
          <button type="button" aria-label={t("cal.prev")} onClick={() => step(-1)} className="grid h-11 w-11 place-items-center rounded-full ring-1 ring-line hover:bg-black/5"><Icon name="chevron" className="h-4 w-4 rotate-180 rtl:rotate-0" /></button>
          <button type="button" aria-label={t("cal.next")} onClick={() => step(1)} className="grid h-11 w-11 place-items-center rounded-full ring-1 ring-line hover:bg-black/5"><Icon name="chevron" className="h-4 w-4 rtl:rotate-180" /></button>
          <Button variant="secondary" onClick={() => setAnchor(todayIso)}>{t("cal.today")}</Button>
        </div>
        <p className="min-w-0 flex-1 text-center font-display text-[15px] font-semibold text-ink sm:text-start">{title}</p>
        <div className="flex gap-1" role="tablist">{(["day", "week", "month"] as const).map((v) => <button key={v} type="button" role="tab" aria-selected={view === v} onClick={() => setView(v)} className={`chip !min-h-11 ${view === v ? "chip-on" : ""}`}>{t(`cal.${v}`)}</button>)}</div>
      </div>
      <ErrorText error={error} />
      {loading && !data ? <Loading variant="table" /> : view === "month" ? (
        <div>
          <div className="grid grid-cols-7 gap-px overflow-hidden rounded-[14px] bg-line text-center text-[11px] font-semibold text-muted">{range.days.slice(0, 7).map((d) => <div key={d} className="bg-surface-2 py-1.5">{fmt(d, { weekday: "short" })}</div>)}</div>
          <div className="grid grid-cols-7 gap-px overflow-hidden rounded-b-[14px] bg-line">
            {range.days.map((d) => {
              const list = by[d] ?? []; const inMonth = d.slice(0, 7) === anchor.slice(0, 7); const pending = list.filter((b) => b.status === "PENDING").length;
              return (
                <button key={d} type="button" onClick={() => { setAnchor(d); setView("day"); }} className={`flex min-h-16 flex-col items-center gap-1 bg-surface p-1.5 text-[12px] hover:bg-canvas sm:min-h-20 ${inMonth ? "" : "opacity-40"} ${d === todayIso ? "ring-2 ring-inset ring-[var(--brand)]" : ""}`}>
                  <span className="tnum font-semibold text-ink">{parse(d).getDate()}</span>
                  {list.length > 0 && <span className="rounded-full bg-brand-soft px-2 text-[11px] font-bold text-brand tnum">{list.length}</span>}
                  {pending > 0 && <span className="rounded-full bg-amber-100 px-2 text-[10px] font-bold text-amber-800 tnum">{pending}!</span>}
                </button>
              );
            })}
          </div>
        </div>
      ) : (
        <div className="space-y-5">
          {range.days.map((d) => {
            const list = by[d] ?? [];
            return (
              <section key={d} aria-label={d}>
                {view === "week" && <h3 className={`mb-2 flex items-center gap-2 text-[13px] font-bold ${d === todayIso ? "text-brand" : "text-ink-2"}`}>{fmt(d, { weekday: "long", day: "numeric", month: "short" })}<span className="rounded-full bg-canvas px-2 text-[11px] text-muted tnum">{list.length}</span></h3>}
                {list.length === 0 ? (view === "day" ? <p className="rounded-[14px] border border-dashed border-line-strong p-8 text-center text-sm text-muted">{t("cal.none")}</p> : <p className="text-[12.5px] text-faint">{t("cal.free")}</p>) : <ul className="space-y-2">{list.map(card)}</ul>}
              </section>
            );
          })}
        </div>
      )}
      <Decide b={dlg?.b ?? null} mode={dlg?.mode ?? "cancel"} onClose={() => setDlg(null)} onDone={() => { setDlg(null); after(); }} />
    </div>
  );
}
