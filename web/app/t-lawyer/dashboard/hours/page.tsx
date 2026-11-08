"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { dateTime } from "@/lib/format";
import { useFieldErrors } from "@/lib/use-field-errors";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { ConfirmDialog, Switch } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { Eyebrow } from "@/components/lawyer/kit";
import { Button, Empty, ErrorText, Field, Input, Loading, PageHeader, Section } from "@/components/ui";

type H = { weekday: number; start: string; end: string };
type Res = { hours: H[]; timeOff: { id: string; startsAt: string; endsAt: string; reason: string }[] };
const ORDER = [6, 7, 1, 2, 3, 4, 5];   // Saturday first

export default function Hours() {
  const { t, locale } = useI18n();
  const toast = useToast();
  const [offDel, setOffDel] = useState<string | null>(null);
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
    setHours(null); setSaved(true); await reload(); toast(t("matrix.saved"));
  });
  const [off, setOff] = useState({ start: "", end: "", reason: "" });
  const addOff = useAction(async () => {
    if (!fe.check({ startsAt: !off.start && t("srv.Choose when it starts"), endsAt: !off.end && t("srv.Choose when it ends") })) return;
    try { await api("lawyer/time-off", { body: { startsAt: new Date(off.start).toISOString(), endsAt: new Date(off.end).toISOString(), reason: off.reason } }); } catch (e) { if (!fe.fromError(e)) throw e; return; }
    setOff({ start: "", end: "", reason: "" }); await reload(); toast(t("common.saved"));
  });
  const delOff = useAction(async (id: string) => { await api(`lawyer/time-off/${id}`, { method: "DELETE" }); await reload(); setOffDel(null); toast(t("lw.toast.deleted")); });
  if (loading && !data) return <Loading />;
  const set = (n: H[]) => { setHours(n); setSaved(false); };
  return (
    <div className="space-y-4">
      <PageHeader eyebrow={<Eyebrow>{t("lw.eb.setup")}</Eyebrow>} title={t("lw.nav.hours")} subtitle={t("lw.hours.subtitle")} />
      <ErrorText error={error} />
      <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)]">
        <Section title={t("lw.hours.week")} icon="clock">
          <div className="space-y-2">
            {ORDER.map((w) => {
              const rows = list.map((h, i) => ({ h, i })).filter((x) => x.h.weekday === w);
              return (
                <div key={w} className="flex flex-wrap items-center gap-x-3 gap-y-2 rounded-[14px] bg-surface-2 p-3 ring-1 ring-line">
                  <span className="flex w-36 items-center gap-2.5 text-[13.5px] font-semibold text-ink"><Switch size="sm" disabled={!write} label={dayName(w)} checked={rows.length > 0} onChange={(on) => set(on ? [...list, { weekday: w, start: "11:00", end: "17:00" }] : list.filter((x) => x.weekday !== w))} />{dayName(w)}</span>
                  {rows.length === 0 ? <span className="text-sm text-faint">{t("lw.hours.closed")}</span> : rows.map(({ h, i }) => (
                    <div key={i} data-field={`hours[${i}].start`} className={`flex items-center gap-1 rounded-[10px] bg-surface p-1 ring-1 ring-line ${fe.errUnder(`hours[${i}]`) ? "!ring-2 !ring-red-400" : ""}`}>
                      <Input type="time" dir="ltr" disabled={!write} className="!w-[6.5rem] !py-1.5" value={h.start} onChange={(e) => set(list.map((x, k) => (k === i ? { ...x, start: e.target.value } : x)))} />
                      <span className="text-faint">–</span>
                      <Input type="time" dir="ltr" disabled={!write} className="!w-[6.5rem] !py-1.5" value={h.end} onChange={(e) => set(list.map((x, k) => (k === i ? { ...x, end: e.target.value } : x)))} />
                      {write && <button type="button" aria-label={t("common.remove")} className="grid h-8 w-8 place-items-center rounded-full text-muted hover:text-red-600 pointer-coarse:h-11 pointer-coarse:w-11" onClick={() => set(list.filter((_, k) => k !== i))}><Icon name="close" className="h-4 w-4" /></button>}
                    </div>
                  ))}
                  {write && rows.length > 0 && <button type="button" className="inline-flex items-center gap-1 text-xs font-semibold text-ink-2 hover:text-ink" onClick={() => set([...list, { weekday: w, start: "18:00", end: "21:00" }])}><Icon name="plus" className="h-3.5 w-3.5" />{t("lw.hours.addWindow")}</button>}
                  {rows.map(({ i }) => fe.errUnder(`hours[${i}]`) && <p key={"e" + i} className="w-full text-xs font-medium text-red-600">{fe.errUnder(`hours[${i}]`)}</p>)}
                </div>
              );
            })}
          </div>
          {!fe.any && <div className="mt-3"><ErrorText error={save.error} /></div>}
          {write && <div className="sticky bottom-3 mt-4 flex items-center gap-3 rounded-[14px] bg-surface/90 p-2 shadow-card backdrop-blur"><Button loading={save.loading} disabled={!hours} onClick={() => save.run()}>{t("common.save")}</Button>{saved && !hours && <span className="inline-flex items-center gap-1 text-[13px] font-semibold text-emerald-700"><Icon name="check" className="h-4 w-4" />{t("matrix.saved")}</span>}</div>}
        </Section>
        <Section title={t("lw.hours.off")} icon="calendar">
          <p className="mb-3 text-[13px] leading-relaxed text-muted">{t("lw.hours.offHint")}</p>
          {write && <div className="space-y-2.5 rounded-[14px] bg-surface-2 p-3.5 ring-1 ring-line">
            <div className="grid gap-2.5 sm:grid-cols-2">
              <Field name="startsAt" error={fe.err("startsAt")} label={t("offer.startsAt")}><Input type="datetime-local" value={off.start} onChange={(e) => setOff({ ...off, start: e.target.value })} /></Field>
              <Field name="endsAt" error={fe.err("endsAt")} label={t("offer.endsAt")}><Input type="datetime-local" value={off.end} onChange={(e) => setOff({ ...off, end: e.target.value })} /></Field>
            </div>
            <Input placeholder={t("lw.hours.reasonPh")} value={off.reason} onChange={(e) => setOff({ ...off, reason: e.target.value })} />
            <Button size="sm" loading={addOff.loading} onClick={() => addOff.run()}><Icon name="plus" className="h-3.5 w-3.5" />{t("lw.hours.addOff")}</Button>
          </div>}
          {data && data.timeOff.length === 0 ? <div className="mt-4"><Empty compact icon="calendar" /></div> : (
            <ul className="mt-4 space-y-2">{data?.timeOff.map((o) => (
              <li key={o.id} className="flex items-center justify-between gap-2 rounded-[12px] border-s-[3px] border-amber-400 bg-amber-50 p-3 text-sm text-amber-950 dark:bg-amber-500/10 dark:text-amber-100"><span className="min-w-0"><b className="block truncate font-semibold">{o.reason || t("lw.hours.away")}</b><span className="block text-xs opacity-75 tnum">{dateTime(o.startsAt, locale)} → {dateTime(o.endsAt, locale)}</span></span>
                {write && <button type="button" aria-label={t("offer.delete")} onClick={() => setOffDel(o.id)} className="grid h-9 w-9 shrink-0 place-items-center rounded-full opacity-60 hover:opacity-100 pointer-coarse:h-11 pointer-coarse:w-11"><Icon name="close" className="h-4 w-4" /></button>}</li>
            ))}</ul>
          )}
        </Section>
      </div>
      <ConfirmDialog open={!!offDel} onClose={() => setOffDel(null)} onConfirm={() => offDel && void delOff.run(offDel)} loading={delOff.loading} danger title={t("lw.confirm.deleteTitle")} confirmLabel={t("offer.delete")} cancelLabel={t("common.cancel")} />
    </div>
  );
}
