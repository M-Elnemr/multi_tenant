"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { useFieldErrors } from "@/lib/use-field-errors";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Button, ErrorText, Field, Input, Loading, PageHeader, Section, Select } from "@/components/ui";

type P = { slotMinutes: number; bufferMinutes: number; minNoticeHours: number; maxDaysAhead: number; maxPerDay: number | null; autoApprove: boolean; bookingEnabled: boolean; reminderDaysBefore: number; dailyDigest: boolean };

/** Booking rules and reminders. */
export default function Settings() {
  const { t } = useI18n();
  const { canWrite } = useMe();
  const { data, loading, error, reload } = useApi<P>("lawyer/profile");
  const [f, setF] = useState<P | null>(null);
  const [saved, setSaved] = useState(false);
  const fe = useFieldErrors();
  const v = f ?? data;
  const set = (x: Partial<P>) => { setF({ ...(v as P), ...x }); setSaved(false); for (const k of Object.keys(x)) fe.clear(k); };
  const save = useAction(async () => {
    if (!v) return;
    try { await api("lawyer/profile", { method: "PATCH", body: { slotMinutes: v.slotMinutes, bufferMinutes: v.bufferMinutes, minNoticeHours: v.minNoticeHours, maxDaysAhead: v.maxDaysAhead, maxPerDay: v.maxPerDay, autoApprove: v.autoApprove, bookingEnabled: v.bookingEnabled, reminderDaysBefore: v.reminderDaysBefore, dailyDigest: v.dailyDigest } }); }
    catch (e) { if (!fe.fromError(e)) throw e; return; }
    setF(null); setSaved(true); await reload();
  });
  if (loading && !data) return <Loading />;
  if (!v) return <ErrorText error={error} />;
  const toggle = (k: keyof P, title: string, hint: string) => (
    <label className="flex items-center justify-between gap-3 rounded-xl bg-slate-50 p-4"><span><b className="block text-slate-800">{title}</b><span className="text-sm text-slate-500">{hint}</span></span>
      <input type="checkbox" disabled={!canWrite("settings")} className="h-6 w-6 accent-[var(--brand)]" checked={!!v[k]} onChange={(e) => set({ [k]: e.target.checked } as Partial<P>)} /></label>
  );
  const num = (k: keyof P, label: string, hint?: string, opts?: number[]) => (
    <Field name={k} error={fe.err(k)} label={label} hint={hint}>
      {opts ? <Select value={String(v[k] ?? "")} onChange={(e) => set({ [k]: Number(e.target.value) } as Partial<P>)}>{opts.map((o) => <option key={o} value={o}>{o}</option>)}</Select>
        : <Input dir="ltr" inputMode="numeric" value={v[k] == null ? "" : String(v[k])} onChange={(e) => set({ [k]: e.target.value === "" ? null : Number(e.target.value.replace(/[^0-9]/g, "")) } as Partial<P>)} />}
    </Field>
  );
  return (
    <>
      <PageHeader title={t("nav.settings")} subtitle={t("lw.set.subtitle")} />
      <div className="space-y-5">
        <Section title={t("lw.set.booking")}>
          <div className="space-y-3">
            {toggle("bookingEnabled", t("lw.set.bookingOn"), t("lw.set.bookingOnHint"))}
            {toggle("autoApprove", t("lw.set.auto"), t("lw.set.autoHint"))}
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
              {num("slotMinutes", t("lw.set.slot"), undefined, [15, 20, 30, 45, 60])}
              {num("bufferMinutes", t("lw.set.buffer"), undefined, [0, 5, 10, 15, 30])}
              {num("minNoticeHours", t("lw.set.notice"), t("lw.set.noticeHint"))}
              {num("maxDaysAhead", t("lw.set.ahead"))}
              {num("maxPerDay", t("lw.set.perDay"), t("offer.optional"))}
            </div>
          </div>
        </Section>
        <Section title={t("lw.set.reminders")}>
          <div className="space-y-3">
            <p className="rounded-xl bg-indigo-50 p-3 text-sm text-indigo-800">🔔 {t("lw.set.hourNote")}</p>
            {num("reminderDaysBefore", t("lw.set.early"), t("lw.set.earlyHint"), [0, 1, 2, 3, 7])}
            {toggle("dailyDigest", t("lw.set.digest"), t("lw.set.digestHint"))}
          </div>
        </Section>
        {saved && <Alert tone="green">{t("matrix.saved")}</Alert>}
        {fe.any ? <p className="rounded-xl bg-red-50 px-4 py-3 text-sm font-medium text-red-700">{t("form.fixMarked")}</p> : <ErrorText error={save.error} />}
        {canWrite("settings") && <Button loading={save.loading} onClick={() => save.run()}>{t("common.save")}</Button>}
      </div>
    </>
  );
}
