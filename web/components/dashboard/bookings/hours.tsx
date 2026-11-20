"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { Switch } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { Button, ErrorText, Field, Input, Select, Skeleton } from "@/components/ui";
import { WEEK, type Range, type Settings } from "./common";

type Day = { open: boolean; ranges: { opens: string; closes: string }[] };

function Weekly({ canManage }: { canManage: boolean }) {
  const { t } = useI18n();
  const toast = useToast();
  const { data, reload } = useApi<Range[]>("store/booking/hours");
  const [days, setDays] = useState<Record<number, Day> | null>(null);
  const cur: Record<number, Day> = days ?? Object.fromEntries(WEEK.map((w) => {
    const r = (data ?? []).filter((x) => x.weekday === w).map((x) => ({ opens: x.opens, closes: x.closes }));
    return [w, { open: r.length > 0, ranges: r.length ? r : [{ opens: "10:00", closes: "18:00" }] }];
  }));
  const set = (w: number, d: Day) => setDays({ ...cur, [w]: d });
  const save = useAction(async () => {
    const ranges = WEEK.flatMap((w) => cur[w].open ? cur[w].ranges.map((r) => ({ weekday: w, opens: r.opens, closes: r.closes })) : []);
    await api("store/booking/hours", { method: "PUT", body: { ranges } });
    toast(t("common.saved"));
    setDays(null);
    await reload();
  });
  if (!data) return <Skeleton className="h-64 w-full !rounded-[16px]" />;
  return (
    <section className="space-y-3 rounded-[18px] bg-surface p-4 ring-1 ring-line sm:p-5">
      <div><h3 className="font-display text-[15px] font-semibold text-ink">{t("bk.hours.title")}</h3><p className="text-[13px] text-muted">{t("bk.hours.sub")}</p></div>
      <ul className="divide-y divide-line">
        {WEEK.map((w) => (
          <li key={w} className="flex flex-wrap items-start gap-x-4 gap-y-2 py-3">
            <div className="flex w-32 items-center gap-3 pt-1.5"><Switch checked={cur[w].open} disabled={!canManage} onChange={(v) => set(w, { ...cur[w], open: v })} label={t(`bk.day.${w}`)} /><span className="text-sm font-semibold text-ink">{t(`bk.day.${w}`)}</span></div>
            {cur[w].open ? (
              <div className="min-w-0 flex-1 space-y-2">
                {cur[w].ranges.map((r, i) => (
                  <div key={i} className="flex items-center gap-2">
                    <Input type="time" dir="ltr" className="!w-28" disabled={!canManage} value={r.opens} onChange={(e) => set(w, { ...cur[w], ranges: cur[w].ranges.map((x, j) => j === i ? { ...x, opens: e.target.value } : x) })} />
                    <span className="text-muted">–</span>
                    <Input type="time" dir="ltr" className="!w-28" disabled={!canManage} value={r.closes} onChange={(e) => set(w, { ...cur[w], ranges: cur[w].ranges.map((x, j) => j === i ? { ...x, closes: e.target.value } : x) })} />
                    {canManage && cur[w].ranges.length > 1 && <button type="button" aria-label={t("common.remove")} onClick={() => set(w, { ...cur[w], ranges: cur[w].ranges.filter((_, j) => j !== i) })} className="grid h-9 w-9 place-items-center rounded-[9px] text-faint hover:bg-red-50 hover:text-red-600 pointer-coarse:h-11 pointer-coarse:w-11"><Icon name="close" className="h-4 w-4" /></button>}
                  </div>
                ))}
                {canManage && <button type="button" onClick={() => set(w, { ...cur[w], ranges: [...cur[w].ranges, { opens: "16:00", closes: "21:00" }] })} className="text-[12.5px] font-semibold text-brand hover:underline">+ {t("bk.hours.addBreak")}</button>}
              </div>
            ) : <span className="pt-1.5 text-sm text-muted">{t("bk.hours.closed")}</span>}
          </li>
        ))}
      </ul>
      <ErrorText error={save.error} />
      {canManage && <Button variant="ink" loading={save.loading} onClick={() => void save.run()}>{t("common.save")}</Button>}
    </section>
  );
}

function Rules({ canManage }: { canManage: boolean }) {
  const { t } = useI18n();
  const toast = useToast();
  const { data, reload } = useApi<Settings>("store/booking/settings");
  const [f, setF] = useState<Partial<Settings>>({});
  const v = { ...data, ...f } as Settings;
  const save = useAction(async () => { await api("store/booking/settings", { method: "PUT", body: f }); toast(t("common.saved")); setF({}); await reload(); });
  if (!data) return <Skeleton className="h-64 w-full !rounded-[16px]" />;
  return (
    <section className="space-y-4 rounded-[18px] bg-surface p-4 ring-1 ring-line sm:p-5">
      <div><h3 className="font-display text-[15px] font-semibold text-ink">{t("bk.rules.title")}</h3><p className="text-[13px] text-muted">{t("bk.rules.sub", { tz: data.timezone })}</p></div>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label={t("bk.rules.stations")} hint={t("bk.rules.stationsHint")}><Input type="number" min={1} max={50} dir="ltr" disabled={!canManage} value={v.stations} onChange={(e) => setF({ ...f, stations: Number(e.target.value) })} /></Field>
        <Field label={t("bk.rules.step")} hint={t("bk.rules.stepHint")}>
          <Select disabled={!canManage} value={v.slotStepMinutes} onChange={(e) => setF({ ...f, slotStepMinutes: Number(e.target.value) })}>{[15, 30, 45, 60].map((m) => <option key={m} value={m}>{m} {t("bk.min")}</option>)}</Select>
        </Field>
        <Field label={t("bk.rules.notice")} hint={t("bk.rules.noticeHint")}>
          <Select disabled={!canManage} value={v.minNoticeMinutes} onChange={(e) => setF({ ...f, minNoticeMinutes: Number(e.target.value) })}>{[0, 60, 120, 240, 720, 1440, 2880].map((m) => <option key={m} value={m}>{m === 0 ? t("bk.rules.noNotice") : m < 1440 ? t("bk.rules.hours", { n: m / 60 }) : t("bk.rules.days", { n: m / 1440 })}</option>)}</Select>
        </Field>
        <Field label={t("bk.rules.ahead")} hint={t("bk.rules.aheadHint")}><Input type="number" min={1} max={365} dir="ltr" disabled={!canManage} value={v.maxDaysAhead} onChange={(e) => setF({ ...f, maxDaysAhead: Number(e.target.value) })} /></Field>
      </div>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label={t("bk.rules.expire")} hint={t("bk.rules.expireHint")}>
          <Select disabled={!canManage || v.autoConfirm} value={v.pendingExpiresMinutes ?? 0} onChange={(e) => setF({ ...f, pendingExpiresMinutes: Number(e.target.value) || undefined })}>{[[0, t("bk.rules.never")], [60, "1h"], [180, "3h"], [720, "12h"], [1440, "24h"]].map(([m, l]) => <option key={String(m)} value={Number(m)}>{String(l)}</option>)}</Select>
        </Field>
        <Field label={t("bk.rules.deadline")} hint={t("bk.rules.deadlineHint")}>
          <Select disabled={!canManage} value={v.cancelDeadlineHours ?? 0} onChange={(e) => setF({ ...f, cancelDeadlineHours: Number(e.target.value) })}>{[0, 2, 6, 12, 24, 48].map((h) => <option key={h} value={h}>{h === 0 ? t("bk.rules.anytime") : `${h}h`}</option>)}</Select>
        </Field>
      </div>
      <div className="flex items-start justify-between gap-4 rounded-[12px] bg-canvas p-3 ring-1 ring-line">
        <span><span className="block text-sm font-semibold text-ink-2">{t("bk.rules.auto")}</span><span className="block text-[12.5px] text-muted">{t("bk.rules.autoHint")}</span></span>
        <Switch checked={v.autoConfirm} disabled={!canManage} onChange={(x) => setF({ ...f, autoConfirm: x })} label={t("bk.rules.auto")} />
      </div>
      <ErrorText error={save.error} />
      {canManage && <Button variant="ink" loading={save.loading} disabled={Object.keys(f).length === 0} onClick={() => void save.run()}>{t("common.save")}</Button>}
    </section>
  );
}

function DaysOff({ canManage }: { canManage: boolean }) {
  const { t } = useI18n();
  const toast = useToast();
  const { data, reload } = useApi<{ date: string; reason?: string }[]>("store/booking/closures");
  const [date, setDate] = useState("");
  const [to, setTo] = useState("");
  const [reason, setReason] = useState("");
  const add = useAction(async () => { await api("store/booking/closures", { body: { date, to: to || undefined, reason } }); setDate(""); setTo(""); setReason(""); toast(t("common.saved")); await reload(); });
  const remove = useAction(async (d: string) => { await api(`store/booking/closures/${d}`, { method: "DELETE" }); await reload(); });
  return (
    <section className="space-y-3 rounded-[18px] bg-surface p-4 ring-1 ring-line sm:p-5">
      <div><h3 className="font-display text-[15px] font-semibold text-ink">{t("bk.off.title")}</h3><p className="text-[13px] text-muted">{t("bk.off.sub")}</p></div>
      {canManage && (
        <form className="flex flex-wrap items-end gap-2" onSubmit={(e) => { e.preventDefault(); if (date) void add.run(); }}>
          <Field label={t("bk.off.from")} className="w-44"><Input type="date" dir="ltr" min={new Date().toISOString().slice(0, 10)} value={date} onChange={(e) => setDate(e.target.value)} /></Field>
          <Field label={`${t("bk.off.to")} (${t("ch.optional")})`} hint={t("bk.off.toHint")} className="w-44"><Input type="date" dir="ltr" min={date || new Date().toISOString().slice(0, 10)} value={to} onChange={(e) => setTo(e.target.value)} /></Field>
          <Field label={`${t("bk.reason")} (${t("ch.optional")})`} className="min-w-0 flex-1"><Input value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
          <Button type="submit" variant="secondary" loading={add.loading} disabled={!date}>{t("bk.off.add")}</Button>
        </form>
      )}
      <ErrorText error={add.error} />
      {data && data.length > 0 && (
        <ul className="flex flex-wrap gap-2">
          {data.map((c) => <li key={c.date} className="inline-flex items-center gap-2 rounded-full bg-canvas py-1 pe-1 ps-3 text-[13px] ring-1 ring-line"><span className="tnum font-semibold text-ink">{c.date}</span>{c.reason && <span className="text-muted">{c.reason}</span>}{canManage && <button type="button" aria-label={t("common.remove")} onClick={() => void remove.run(c.date)} className="grid h-7 w-7 place-items-center rounded-full text-faint hover:bg-red-50 hover:text-red-600"><Icon name="close" className="h-3.5 w-3.5" /></button>}</li>)}
        </ul>
      )}
    </section>
  );
}

/** A date with its own hours (a short Friday, Ramadan hours): it replaces that weekday's hours on that date only. */
function SpecialDays({ canManage }: { canManage: boolean }) {
  const { t } = useI18n();
  const toast = useToast();
  const { data, reload } = useApi<{ date: string; opens: string; closes: string }[]>("store/booking/special-days");
  const [date, setDate] = useState("");
  const [ranges, setRanges] = useState([{ opens: "12:00", closes: "16:00" }]);
  const save = useAction(async () => { await api("store/booking/special-days", { method: "PUT", body: { date, ranges } }); setDate(""); setRanges([{ opens: "12:00", closes: "16:00" }]); toast(t("common.saved")); await reload(); });
  const clear = useAction(async (d: string) => { await api("store/booking/special-days", { method: "PUT", body: { date: d, ranges: [] } }); await reload(); });
  const days = Array.from(new Set((data ?? []).map((x) => x.date)));
  return (
    <section className="space-y-3 rounded-[18px] bg-surface p-4 ring-1 ring-line sm:p-5">
      <div><h3 className="font-display text-[15px] font-semibold text-ink">{t("bk.special.title")}</h3><p className="text-[13px] text-muted">{t("bk.special.sub")}</p></div>
      {canManage && (
        <div className="space-y-2">
          <Field label={t("bk.date")} className="w-44"><Input type="date" dir="ltr" min={new Date().toISOString().slice(0, 10)} value={date} onChange={(e) => setDate(e.target.value)} /></Field>
          {ranges.map((r, i) => (
            <div key={i} className="flex items-center gap-2">
              <Input type="time" dir="ltr" className="!w-28" value={r.opens} onChange={(e) => setRanges(ranges.map((x, j) => (j === i ? { ...x, opens: e.target.value } : x)))} /><span className="text-muted">–</span>
              <Input type="time" dir="ltr" className="!w-28" value={r.closes} onChange={(e) => setRanges(ranges.map((x, j) => (j === i ? { ...x, closes: e.target.value } : x)))} />
              {ranges.length > 1 && <button type="button" aria-label={t("common.remove")} onClick={() => setRanges(ranges.filter((_, j) => j !== i))} className="grid h-9 w-9 place-items-center rounded-full text-red-600 hover:bg-red-50">✕</button>}
            </div>
          ))}
          <div className="flex items-center gap-3"><button type="button" onClick={() => setRanges([...ranges, { opens: "17:00", closes: "21:00" }])} className="text-[12.5px] font-semibold text-brand hover:underline">+ {t("bk.hours.addBreak")}</button><Button variant="secondary" loading={save.loading} disabled={!date} onClick={() => void save.run()}>{t("bk.special.add")}</Button></div>
        </div>
      )}
      <ErrorText error={save.error ?? clear.error} />
      {days.length > 0 && (
        <ul className="space-y-1.5">
          {days.map((d) => <li key={d} className="flex items-center gap-2 rounded-[10px] bg-canvas px-3 py-2 text-[13px]"><span className="tnum font-semibold text-ink" dir="ltr">{d}</span><span className="min-w-0 flex-1 truncate tnum text-muted" dir="ltr">{(data ?? []).filter((x) => x.date === d).map((x) => `${x.opens}–${x.closes}`).join("  ·  ")}</span>{canManage && <button type="button" aria-label={t("common.remove")} onClick={() => void clear.run(d)} className="grid h-9 w-9 place-items-center rounded-full text-red-600 hover:bg-red-50">✕</button>}</li>)}
        </ul>
      )}
    </section>
  );
}

export function HoursAndRules({ canManage }: { canManage: boolean }) {
  return <div className="space-y-5"><Weekly canManage={canManage} /><SpecialDays canManage={canManage} /><Rules canManage={canManage} /><DaysOff canManage={canManage} /></div>;
}
