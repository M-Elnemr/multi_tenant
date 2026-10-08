"use client";

import Link from "next/link";
import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/client";
import { WhatsAppButton } from "@/components/whatsapp-button";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Badge, Button, Card, ErrorText, Loading, PageHeader } from "@/components/ui";
import { ageText } from "@/lib/format";

type Entry = {
  id: string; status: string; queueNumber?: number; calledAt?: string; waitedMinutes?: number; statusMinutes?: number;
  doctorId: string; doctorName: string; serviceName: string;
  patientId: string; patientName: string; patientCode: string; patientPhone?: string; patientAgeYears?: number; patientAgeMonths?: number;
};
type Queue = { inProgress: Entry[]; waiting: Entry[] };

/** "الكشف الحالي": the patient the secretary sent in. One click opens the exam where the doctor records vitals, notes, prescription and tests. */
export default function CurrentExam() {
  const { t, locale } = useI18n();
  const router = useRouter();
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
  const call = useAction(async (e: Entry) => { await api(`clinic/appointments/${e.id}/call`, { body: {} }); await reload(); });

  if (!resolved || (queue.loading && !queue.data)) return <Loading />;
  const q = queue.data;
  const [current, ...others] = q?.inProgress ?? [];
  const next = q?.waiting[0];

  return (
    <>
      <PageHeader title={t("current.title")} subtitle={t("current.hint")} />
      <ErrorText error={queue.error ?? open.error ?? call.error} />
      {current ? (
        <Card className="border-2 border-brand/50">
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div>
              <p className="text-3xl font-bold">{current.patientName}</p>
              <p className="mt-1 font-mono text-sm text-slate-500">{current.patientCode} · #{current.queueNumber}</p>
              <dl className="mt-3 grid gap-x-8 gap-y-1 text-sm sm:grid-cols-2">
                <div className="flex gap-2"><dt className="text-slate-500">{t("patients.age")}:</dt><dd className="font-medium">{ageText(current.patientAgeYears, current.patientAgeMonths, locale)}</dd></div>
                <div className="flex items-center gap-2"><dt className="text-slate-500">{t("patients.mobile")}:</dt><dd className="font-medium" dir="ltr">{current.patientPhone ?? "-"}</dd><WhatsAppButton phone={current.patientPhone} size={26} /></div>
                <div className="flex gap-2"><dt className="text-slate-500">{t("book.service")}:</dt><dd className="font-medium">{current.serviceName}</dd></div>
              </dl>
              <div className="mt-3"><Badge tone="blue">{t("current.since", { n: current.statusMinutes ?? 0 })}</Badge></div>
            </div>
            <div className="flex flex-col gap-2">
              {clinical && <Button onClick={() => open.run(current)} loading={open.loading}>{t("current.open")}</Button>}
              <Link href={`/dashboard/patients/${current.patientId}`} className="inline-flex h-10 items-center justify-center rounded-lg border px-4 text-sm hover:bg-slate-50">{t("pq.openRecord")}</Link>
            </div>
          </div>
        </Card>
      ) : (
        <Card className="text-center">
          <p className="py-6 text-lg text-slate-500">{t("current.empty")}</p>
        </Card>
      )}

      {others.length > 0 && (
        <div className="mt-4 space-y-2">
          <p className="text-sm font-medium text-slate-500">{t("current.others")}</p>
          {others.map((e) => (
            <Card key={e.id} className="flex items-center justify-between gap-3 text-sm">
              <span className="font-medium">{e.patientName} <span className="font-mono text-xs text-slate-500">#{e.queueNumber}</span></span>
              {clinical && <Button size="sm" variant="secondary" onClick={() => open.run(e)}>{t("current.open")}</Button>}
            </Card>
          ))}
        </div>
      )}

      <div className="mt-6">
        <p className="mb-2 text-sm font-medium text-slate-500">{t("current.next")} ({q?.waiting.length ?? 0})</p>
        {next ? (
          <Card className="flex items-center justify-between gap-3 bg-brand/5">
            <div>
              <p className="text-lg font-semibold">{next.patientName}</p>
              <p className="text-sm text-slate-500">#{next.queueNumber} · {t("queue.waited", { n: next.waitedMinutes ?? 0 })}</p>
            </div>
            {can("appointment.manage") && <Button variant={next.calledAt ? "secondary" : "primary"} onClick={() => call.run(next)} loading={call.loading}>{next.calledAt ? t("queue.callAgain") : t("queue.call")}</Button>}
          </Card>
        ) : <p className="text-sm text-slate-400">{t("current.nobodyWaiting")}</p>}
      </div>
    </>
  );
}
