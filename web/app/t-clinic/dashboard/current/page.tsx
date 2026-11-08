"use client";

import Link from "next/link";
import { VisitTypeBadge, VisitTypeEditable } from "@/components/visit-type";
import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/client";
import { WhatsAppButton } from "@/components/whatsapp-button";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { useToast } from "@/components/toast";
import { Avatar, Button, Empty, ErrorText, Loading, PageHeader } from "@/components/ui";
import { Facts, Paper, PaperHead, Pill, Rise, linkBtn } from "@/components/clinic/dash";
import { ageText } from "@/lib/format";

type Entry = {
  id: string; status: string; queueNumber?: number; calledAt?: string; waitedMinutes?: number; statusMinutes?: number;
  doctorId: string; doctorName: string; serviceName: string; visitType?: string;
  patientId: string; patientName: string; patientCode: string; patientPhone?: string; patientAgeYears?: number; patientAgeMonths?: number;
};
type Queue = { inProgress: Entry[]; waiting: Entry[] };

/** "الكشف الحالي": the patient the secretary sent in. One click opens the exam where the doctor records vitals, notes, prescription and tests. */
export default function CurrentExam() {
  const { t, locale } = useI18n();
  const router = useRouter();
  const toast = useToast();
  const { can } = useMe();
  const clinical = can("medical_note.create");
  const me = useApi<{ id: string }>("clinic/doctors/me");   // not a doctor (403): the screen then follows every doctor
  const resolved = !me.loading || me.data !== null || me.error !== null;
  const queue = useApi<Queue>(resolved ? `clinic/queue?doctorId=${me.data?.id ?? ""}` : null);
  const reload = queue.reload;

  // the secretary sends patients in from another screen: look for new arrivals every 5 seconds
  useEffect(() => {
    const h = setInterval(() => { void reload(); }, 5_000);
    return () => clearInterval(h);
  }, [reload]);

  const open = useAction(async (e: Entry) => {
    const enc = await api<{ id: string }>("clinic/encounters", { body: { patientId: e.patientId, appointmentId: e.id } });
    router.push(`/dashboard/encounters/${enc.id}`);
  });
  const call = useAction(async (e: Entry) => { await api(`clinic/appointments/${e.id}/call`, { body: {} }); await reload(); toast(t("cld.calledOk", { name: e.patientName })); });

  if (!resolved || (queue.loading && !queue.data)) return <Loading variant="detail" />;
  const q = queue.data;
  const [current, ...others] = q?.inProgress ?? [];
  const next = q?.waiting[0];

  return (
    <>
      <PageHeader eyebrow={t("rail.today")} title={t("current.title")} subtitle={t("current.hint")} />
      <div className="space-y-5">
        <ErrorText error={queue.error ?? open.error ?? call.error} />
        {current ? (
          <Rise>
            <Paper className="overflow-hidden ring-1 ring-[color-mix(in_srgb,var(--rail-accent)_35%,transparent)]">
              <div className="grid gap-6 bg-[linear-gradient(160deg,color-mix(in_srgb,var(--rail-accent)_9%,var(--surface)),var(--surface)_60%)] p-5 sm:p-7 lg:grid-cols-[1fr_auto]">
                <div className="min-w-0">
                  <p className="mb-3 flex items-center gap-2 text-xs font-semibold text-muted"><span className="live-dot h-2 w-2 rounded-full bg-[var(--rail-accent)]" />{t("cl.inRoom")}</p>
                  <div className="flex items-center gap-4">
                    <Avatar name={current.patientName} className="h-16 w-16 text-xl" />
                    <div className="min-w-0">
                      <p className="truncate font-display text-[1.7rem] font-semibold leading-tight text-ink sm:text-[2rem]">{current.patientName}</p>
                      <p className="mt-1 flex flex-wrap items-center gap-2 text-sm text-muted"><span className="font-mono text-xs">{current.patientCode}</span> <Pill>#{current.queueNumber}</Pill> <Pill tone="sky">{t("current.since", { n: current.statusMinutes ?? 0 })}</Pill></p>
                    </div>
                  </div>
                  <div className="mt-5 max-w-xl">
                    <Facts rows={[
                      { label: t("patients.age"), value: ageText(current.patientAgeYears, current.patientAgeMonths, locale) },
                      { label: t("patients.mobile"), ltr: true, value: <span className="inline-flex items-center gap-2">{current.patientPhone ?? "-"}<WhatsAppButton phone={current.patientPhone} size={26} /></span> },
                      { label: t("book.service"), value: current.serviceName },
                      { label: t("visit.pick"), value: <VisitTypeEditable appointmentId={current.id} type={current.visitType} onChanged={queue.reload} /> },
                    ]} />
                  </div>
                </div>
                <div className="flex flex-col gap-2 lg:w-52 lg:justify-end">
                  {clinical && <Button size="lg" onClick={() => open.run(current)} loading={open.loading}><Icon name="stethoscope" className="h-4 w-4" />{t("current.open")}</Button>}
                  <Link href={`/dashboard/patients/${current.patientId}`} className={linkBtn.secondary}>{t("pq.openRecord")}</Link>
                </div>
              </div>
            </Paper>
          </Rise>
        ) : (
          <Empty icon="stethoscope" title={t("current.empty")}>{t("cld.currentEmptyHint")}</Empty>
        )}

        {others.length > 0 && (
          <Paper>
            <PaperHead icon="users" title={t("current.others")} />
            <ul className="divide-y divide-line px-2 pb-2">
              {others.map((e) => (
                <li key={e.id} className="flex items-center justify-between gap-3 rounded-[12px] px-3 py-3 text-sm">
                  <span className="flex min-w-0 items-center gap-3"><Avatar name={e.patientName} className="h-9 w-9 text-xs" /><span className="truncate font-semibold text-ink">{e.patientName}</span><Pill>#{e.queueNumber}</Pill></span>
                  {clinical && <Button size="sm" variant="secondary" onClick={() => open.run(e)}>{t("current.open")}</Button>}
                </li>
              ))}
            </ul>
          </Paper>
        )}

        <Paper>
          <PaperHead icon="queue" title={`${t("current.next")} (${q?.waiting.length ?? 0})`} />
          <div className="px-5 pb-5">
            {next ? (
              <div className="flex flex-wrap items-center justify-between gap-3 rounded-[14px] bg-[color-mix(in_srgb,var(--rail-accent)_7%,var(--surface))] p-4">
                <div className="flex min-w-0 items-center gap-3">
                  <Avatar name={next.patientName} className="h-11 w-11 text-sm" />
                  <div className="min-w-0">
                    <p className="flex flex-wrap items-center gap-2 font-display text-lg font-semibold text-ink">{next.patientName} <VisitTypeBadge type={next.visitType} /></p>
                    <p className="text-[13px] text-muted">#{next.queueNumber} · {t("queue.waited", { n: next.waitedMinutes ?? 0 })}</p>
                  </div>
                </div>
                {can("appointment.manage") && <Button variant={next.calledAt ? "secondary" : "primary"} onClick={() => call.run(next)} loading={call.loading}>{next.calledAt ? t("queue.callAgain") : t("queue.call")}</Button>}
              </div>
            ) : <p className="text-sm text-muted">{t("current.nobodyWaiting")}</p>}
          </div>
        </Paper>
      </div>
    </>
  );
}
