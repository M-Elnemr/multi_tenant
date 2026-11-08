"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { useFieldErrors } from "@/lib/use-field-errors";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Switch } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { Icon } from "@/components/icons";
import { Eyebrow } from "@/components/lawyer/kit";
import { Button, ErrorText, Field, Input, Loading, PageHeader, Section, Select } from "@/components/ui";
import { ChangePasswordCard } from "@/components/change-password";
import { WhatsAppReportCard } from "@/components/lawyer/whatsapp-report";

type P = { slotMinutes: number; bufferMinutes: number; minNoticeHours: number; maxDaysAhead: number; maxPerDay: number | null; autoApprove: boolean; bookingEnabled: boolean; reminderDaysBefore: number; dailyDigest: boolean };

/** Booking rules and reminders. */
export default function Settings() {
  const { t } = useI18n();
  const toast = useToast();
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
    setF(null); setSaved(true); await reload(); toast(t("matrix.saved"));
  });
  if (loading && !data) return <Loading />;
  if (!v) return <ErrorText error={error} />;
  const toggle = (k: keyof P, title: string, hint: string) => (
    <div className="flex items-center justify-between gap-4 rounded-[14px] bg-surface-2 p-4 ring-1 ring-line"><span className="min-w-0"><b className="block text-[14px] font-semibold text-ink">{title}</b><span className="text-[13px] text-muted">{hint}</span></span>
      <Switch disabled={!canWrite("settings")} label={title} checked={!!v[k]} onChange={(on) => set({ [k]: on } as Partial<P>)} /></div>
  );
  const num = (k: keyof P, label: string, hint?: string, opts?: number[]) => (
    <Field name={k} error={fe.err(k)} label={label} hint={hint}>
      {opts ? <Select value={String(v[k] ?? "")} onChange={(e) => set({ [k]: Number(e.target.value) } as Partial<P>)}>{opts.map((o) => <option key={o} value={o}>{o}</option>)}</Select>
        : <Input dir="ltr" inputMode="numeric" value={v[k] == null ? "" : String(v[k])} onChange={(e) => set({ [k]: e.target.value === "" ? null : Number(e.target.value.replace(/[^0-9]/g, "")) } as Partial<P>)} />}
    </Field>
  );
  return (
    <div className="space-y-5">
      <PageHeader eyebrow={<Eyebrow>{t("lw.eb.setup")}</Eyebrow>} title={t("nav.settings")} subtitle={t("lw.set.subtitle")} />
      <Section title={t("lw.set.booking")} icon="calendar">
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
      <Section title={t("lw.set.reminders")} icon="bell">
        <div className="space-y-3">
          <p className="flex items-start gap-2 rounded-[12px] bg-surface-2 p-3 text-[13px] text-ink-2 ring-1 ring-line"><Icon name="bell" className="mt-0.5 h-4 w-4 shrink-0 text-muted" />{t("lw.set.hourNote")}</p>
          {num("reminderDaysBefore", t("lw.set.early"), t("lw.set.earlyHint"), [0, 1, 2, 3, 7])}
          {toggle("dailyDigest", t("lw.set.digest"), t("lw.set.digestHint"))}
        </div>
      </Section>
      {canWrite("settings") && (
        <div className="sticky bottom-3 z-10 flex items-center gap-3 rounded-[16px] bg-surface/90 p-2 shadow-card backdrop-blur">
          {fe.any ? <p className="ps-2 text-sm font-medium text-red-700">{t("form.fixMarked")}</p> : saved && !f ? <span className="ps-2 inline-flex items-center gap-1 text-[13px] font-semibold text-emerald-700"><Icon name="check" className="h-4 w-4" />{t("matrix.saved")}</span> : <ErrorText error={save.error} />}
          <Button className="ms-auto" loading={save.loading} disabled={!f} onClick={() => save.run()}>{t("common.save")}</Button>
        </div>
      )}
      <Section title={t("lw.wa.title")} icon="chat"><WhatsAppReportCard write={canWrite("settings")} /></Section>
      <div id="password"><ChangePasswordCard /></div>
    </div>
  );
}
