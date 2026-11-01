"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { dateTime } from "@/lib/format";
import { useFieldErrors } from "@/lib/use-field-errors";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { Alert, Button, ErrorText, Field, Input, Loading, PageHeader, Section } from "@/components/ui";

type H = { weekday: number; start: string; end: string };
type Res = { hours: H[]; timeOff: { id: string; startsAt: string; endsAt: string; reason: string }[] };
const ORDER = [6, 7, 1, 2, 3, 4, 5];   // Saturday first

export default function Hours() {
  const { t, locale } = useI18n();
  const { canWrite } = useMe();
  const write = canWrite(["schedule", "settings"]);
  const { data, loading, error, reload } = useApi<Res>("lawyer/hours");
  const [hours, setHours] = useState<H[] | null>(null);
  const [saved, setSaved] = useState(false);
  const fe = useFieldErrors();
  const list = hours ?? data?.hours ?? [];
  const intl = locale === "ar" ? "ar-EG" : "en-GB";
  const dayName = (w: number) => new Intl.DateTimeFormat(intl, { weekday: "long" }).format(new Date(2024, 0, w === 7 ? 7 : w));
  const save = useAction(async () => {
    setSaved(false);
    try { await api("lawyer/hours", { method: "PUT", body: { hours: list } }); } catch (e) { if (!fe.fromError(e)) throw e; return; }
    setHours(null); setSaved(true); await reload();
  });
  const [off, setOff] = useState({ start: "", end: "", reason: "" });
  const addOff = useAction(async () => {
    if (!fe.check({ startsAt: !off.start && t("srv.Choose when it starts"), endsAt: !off.end && t("srv.Choose when it ends") })) return;
    try { await api("lawyer/time-off", { body: { startsAt: new Date(off.start).toISOString(), endsAt: new Date(off.end).toISOString(), reason: off.reason } }); } catch (e) { if (!fe.fromError(e)) throw e; return; }
    setOff({ start: "", end: "", reason: "" }); await reload();
  });
  const delOff = useAction(async (id: string) => { await api(`lawyer/time-off/${id}`, { method: "DELETE" }); await reload(); });
  if (loading && !data) return <Loading />;
  const set = (n: H[]) => { setHours(n); setSaved(false); };
  return (
    <>
      <PageHeader title={t("lw.nav.hours")} subtitle={t("lw.hours.subtitle")} />
      <ErrorText error={error} />
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)]">
        <Section title={t("lw.hours.week")}>
          <div className="space-y-2">
            {ORDER.map((w) => {
              const rows = list.map((h, i) => ({ h, i })).filter((x) => x.h.weekday === w);
              return (
                <div key={w} className="flex flex-wrap items-center gap-2 rounded-xl bg-slate-50 p-3">
                  <label className="flex w-32 items-center gap-2 font-semibold"><input type="checkbox" disabled={!write} className="h-5 w-5 accent-[var(--brand)]" checked={rows.length > 0} onChange={(e) => set(e.target.checked ? [...list, { weekday: w, start: "11:00", end: "17:00" }] : list.filter((x) => x.weekday !== w))} />{dayName(w)}</label>
                  {rows.length === 0 ? <span className="text-sm text-slate-400">{t("lw.hours.closed")}</span> : rows.map(({ h, i }) => (
                    <div key={i} data-field={`hours[${i}].start`} className={`flex items-center gap-1 rounded-lg bg-white p-1 ${fe.errUnder(`hours[${i}]`) ? "ring-2 ring-red-400" : ""}`}>
                      <Input type="time" disabled={!write} className="!w-28 !py-1.5" value={h.start} onChange={(e) => set(list.map((x, k) => (k === i ? { ...x, start: e.target.value } : x)))} />
                      <span>–</span>
                      <Input type="time" disabled={!write} className="!w-28 !py-1.5" value={h.end} onChange={(e) => set(list.map((x, k) => (k === i ? { ...x, end: e.target.value } : x)))} />
                      {write && <button type="button" className="grid h-8 w-8 place-items-center rounded-full text-slate-400 hover:text-red-500" onClick={() => set(list.filter((_, k) => k !== i))}><Icon name="close" className="h-4 w-4" /></button>}
                    </div>
                  ))}
                  {write && rows.length > 0 && <button type="button" className="text-xs font-semibold text-brand" onClick={() => set([...list, { weekday: w, start: "18:00", end: "21:00" }])}>+ {t("lw.hours.addWindow")}</button>}
                  {rows.map(({ i }) => fe.errUnder(`hours[${i}]`) && <p key={"e" + i} className="w-full text-xs font-medium text-red-600">{fe.errUnder(`hours[${i}]`)}</p>)}
                </div>
              );
            })}
          </div>
          {saved && <div className="mt-3"><Alert tone="green">{t("matrix.saved")}</Alert></div>}
          {!fe.any && <ErrorText error={save.error} />}
          {write && <Button className="mt-4" loading={save.loading} onClick={() => save.run()}>{t("common.save")}</Button>}
        </Section>
        <Section title={t("lw.hours.off")}>
          <p className="mb-3 text-sm text-slate-500">{t("lw.hours.offHint")}</p>
          {write && <div className="space-y-2">
            <div className="grid gap-2 sm:grid-cols-2">
              <Field name="startsAt" error={fe.err("startsAt")} label={t("offer.startsAt")}><Input type="datetime-local" value={off.start} onChange={(e) => setOff({ ...off, start: e.target.value })} /></Field>
              <Field name="endsAt" error={fe.err("endsAt")} label={t("offer.endsAt")}><Input type="datetime-local" value={off.end} onChange={(e) => setOff({ ...off, end: e.target.value })} /></Field>
            </div>
            <Input placeholder={t("lw.hours.reasonPh")} value={off.reason} onChange={(e) => setOff({ ...off, reason: e.target.value })} />
            <Button size="sm" loading={addOff.loading} onClick={() => addOff.run()}>+ {t("lw.hours.addOff")}</Button>
          </div>}
          <ul className="mt-4 space-y-2">{data?.timeOff.map((o) => (
            <li key={o.id} className="flex items-center justify-between gap-2 rounded-xl bg-amber-50 p-3 text-sm"><span><b>{o.reason || t("lw.hours.away")}</b><span className="block text-xs text-slate-600">{dateTime(o.startsAt, locale)} → {dateTime(o.endsAt, locale)}</span></span>
              {write && <button type="button" onClick={() => delOff.run(o.id)} className="text-slate-400 hover:text-red-500"><Icon name="close" className="h-4 w-4" /></button>}</li>
          ))}</ul>
        </Section>
      </div>
    </>
  );
}
