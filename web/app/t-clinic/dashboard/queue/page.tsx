"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/client";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Badge, Button, Card, Empty, ErrorText, Loading, PageHeader, Select } from "@/components/ui";
import { timeOnly } from "@/lib/format";

type Entry = {
  id: string; status: string; queueNumber?: number; calledAt?: string; startAt: string; waitedMinutes?: number;
  doctorId: string; doctorName: string; serviceName: string; patientId: string; patientName: string; patientCode: string;
};
type Queue = { inProgress: Entry[]; waiting: Entry[] };
type Doctor = { id: string; displayName: string };

/** The waiting room: who is with the doctor now and who is next, in arrival order. Refreshes by itself every 10 seconds. */
export default function QueuePage() {
  const { t, locale, timezone } = useI18n();
  const router = useRouter();
  const { can } = useMe();
  const [doctorId, setDoctorId] = useState("");
  const doctors = useApi<Doctor[]>("clinic/doctors");
  const queue = useApi<Queue>(`clinic/queue?doctorId=${doctorId}`);
  const reload = queue.reload;

  useEffect(() => {
    const h = setInterval(() => { void reload(); }, 10_000);
    return () => clearInterval(h);
  }, [reload]);

  const act = useAction(async (id: string, action: string) => { await api(`clinic/appointments/${id}/${action}`, { body: {} }); await reload(); });
  const startVisit = useAction(async (e: Entry) => {
    await api(`clinic/appointments/${e.id}/start`, { body: {} });
    const enc = await api<{ id: string }>("clinic/encounters", { body: { patientId: e.patientId, appointmentId: e.id } });
    router.push(`/dashboard/encounters/${enc.id}`);
  });
  // The secretary only moves the patient into the room; the doctor opens the exam from "Current exam".
  const [confirm, setConfirm] = useState<Entry | null>(null);
  const sendIn = useAction(async (e: Entry) => { await api(`clinic/appointments/${e.id}/start`, { body: {} }); setConfirm(null); await reload(); });
  const trySendIn = (e: Entry) => {
    const busy = queue.data?.inProgress.find((x) => x.doctorId === e.doctorId);
    if (busy && confirm?.id !== e.id) setConfirm(e); else void sendIn.run(e);
  };
  const openVisit = useAction(async (e: Entry) => {
    const enc = await api<{ id: string }>("clinic/encounters", { body: { patientId: e.patientId, appointmentId: e.id } });
    router.push(`/dashboard/encounters/${enc.id}`);
  });

  if (queue.loading && !queue.data) return <Loading />;
  const q = queue.data;
  const next = q?.waiting[0];
  const clinical = can("medical_note.create");

  return (
    <>
      <PageHeader
        title={t("nav.queue")}
        subtitle={t("queue.hint")}
        actions={(doctors.data?.length ?? 0) > 1 ? <Select value={doctorId} onChange={(e) => setDoctorId(e.target.value)}><option value="">{t("appt.allDoctors")}</option>{doctors.data?.map((d) => <option key={d.id} value={d.id}>{d.displayName}</option>)}</Select> : undefined}
      />
      <ErrorText error={queue.error ?? act.error ?? startVisit.error ?? openVisit.error ?? sendIn.error} />
      {confirm && (
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-amber-300 bg-amber-50 p-4 text-sm">
          <p>{t("queue.sendInConfirm", { name: queue.data?.inProgress.find((x) => x.doctorId === confirm.doctorId)?.patientName ?? "", next: confirm.patientName })}</p>
          <div className="flex gap-2"><Button size="sm" loading={sendIn.loading} onClick={() => sendIn.run(confirm)}>{t("queue.sendInYes")}</Button><Button size="sm" variant="ghost" onClick={() => setConfirm(null)}>{t("common.cancel")}</Button></div>
        </div>
      )}

      <div className="mb-6 grid gap-4 lg:grid-cols-2">
        <Card className="border-2 border-brand/40">
          <p className="mb-2 text-sm font-medium text-slate-500">{t("queue.nowServing")}</p>
          {q?.inProgress.length ? q.inProgress.map((e) => (
            <div key={e.id} className="flex items-center justify-between gap-3">
              <div>
                <p className="text-2xl font-bold">{e.patientName}</p>
                <p className="text-sm text-slate-500">#{e.queueNumber} · {e.serviceName} · {e.doctorName}</p>
              </div>
              {clinical && <Button onClick={() => openVisit.run(e)} loading={openVisit.loading}>{t("queue.openVisit")}</Button>}
            </div>
          )) : <p className="text-slate-400">{t("queue.nobody")}</p>}
        </Card>
        <Card className="bg-brand/5">
          <p className="mb-2 text-sm font-medium text-slate-500">{t("queue.next")}</p>
          {next ? (
            <div className="flex items-center justify-between gap-3">
              <div>
                <p className="text-2xl font-bold">{next.patientName}</p>
                <p className="text-sm text-slate-500">#{next.queueNumber} · {t("queue.waited", { n: next.waitedMinutes ?? 0 })}</p>
              </div>
              <div className="flex flex-col gap-2">
                <Button onClick={() => act.run(next.id, "call")} loading={act.loading} variant={next.calledAt ? "secondary" : "primary"}>{next.calledAt ? t("queue.callAgain") : t("queue.call")}</Button>
                {clinical && <Button variant="secondary" onClick={() => startVisit.run(next)} loading={startVisit.loading}>{t("appt.startVisit")}</Button>}
                {!clinical && can("appointment.manage") && <Button variant="secondary" onClick={() => trySendIn(next)} loading={sendIn.loading}>{t("queue.sendIn")}</Button>}
              </div>
            </div>
          ) : <p className="text-slate-400">{t("queue.empty")}</p>}
        </Card>
      </div>

      <h2 className="mb-2 font-medium">{t("queue.waiting")} ({q?.waiting.length ?? 0})</h2>
      {q?.waiting.length === 0 ? <Empty>{t("queue.empty")}</Empty> : (
        <ol className="space-y-2">
          {q?.waiting.map((e, i) => (
            <li key={e.id} className="flex flex-wrap items-center justify-between gap-3 rounded-xl border bg-white p-4 text-sm">
              <div className="flex items-center gap-4">
                <span className="flex h-10 w-10 items-center justify-center rounded-full bg-slate-900 text-lg font-bold text-white">{e.queueNumber}</span>
                <div>
                  <p className="font-medium">{e.patientName} <span className="font-mono text-xs text-slate-500">{e.patientCode}</span></p>
                  <p className="text-slate-500">{e.serviceName} · {e.doctorName} · {t("queue.booked", { time: timeOnly(e.startAt, locale, timezone) })}</p>
                </div>
              </div>
              <div className="flex flex-wrap items-center gap-2">
                <Badge tone={(e.waitedMinutes ?? 0) > 30 ? "red" : (e.waitedMinutes ?? 0) > 15 ? "amber" : "slate"}>{t("queue.waited", { n: e.waitedMinutes ?? 0 })}</Badge>
                {e.calledAt && <Badge tone="blue">{t("queue.called")}</Badge>}
                {i > 0 && <Button size="sm" variant="ghost" onClick={() => act.run(e.id, "call")}>{t("queue.call")}</Button>}
                {i > 0 && !clinical && can("appointment.manage") && <Button size="sm" variant="secondary" onClick={() => trySendIn(e)}>{t("queue.sendIn")}</Button>}
                <Button size="sm" variant="ghost" onClick={() => act.run(e.id, "no-show")}>{t("appt.noShow")}</Button>
              </div>
            </li>
          ))}
        </ol>
      )}
    </>
  );
}
