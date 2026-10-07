"use client";

import { useRouter } from "next/navigation";
import { use, useState } from "react";
import { api } from "@/lib/client";
import { TimelineView, type StaffTimeline } from "@/components/dashboard/timeline-view";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Button, Card, ErrorText, Field, Input, Loading, Modal, PageHeader, SecretBox } from "@/components/ui";
import { dateOnly } from "@/lib/format";

type Patient = { id: string; patientCode: string; firstName: string; lastName: string; phone?: string; dateOfBirth?: string; sex?: string; hasPortal: boolean; notesInternal?: string };

export default function PatientPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { t, locale } = useI18n();
  const router = useRouter();
  const { can } = useMe();
  const clinical = can("medical_note.create") || can("prescription.create") || can("lab_order.create");
  const p = useApi<Patient>(`clinic/patients/${id}`);
  const tl = useApi<StaffTimeline>(clinical ? `clinic/patients/${id}/timeline` : null);
  const [pin, setPin] = useState<{ label: string; value: string } | null>(null);
  const reissue = useAction(async () => {
    const r = await api<{ activationPin?: string; linkPin?: string }>(`clinic/patients/${id}/access-pin`, { body: {} });
    setPin(r.activationPin ? { label: t("patients.activationPin"), value: r.activationPin } : { label: t("patients.linkPin"), value: r.linkPin! });
  });
  const [pwOpen, setPwOpen] = useState(false);
  const [newPw, setNewPw] = useState("");
  const [pwDone, setPwDone] = useState(false);
  const setPassword = useAction(async () => { await api(`clinic/patients/${id}/set-password`, { body: { password: newPw } }); setPwDone(true); });
  const doctors = useApi<{ id: string }[]>(can("appointment.manage") ? "clinic/doctors" : null);
  const branches = useApi<{ id: string }[]>(can("appointment.manage") ? "clinic/branches" : null);
  const services = useApi<{ id: string; isActive?: boolean }[]>(can("appointment.manage") ? "clinic/services" : null);
  const walkIn = useAction(async () => {
    await api("clinic/appointments/walk-in", { body: { patientId: id, doctorId: doctors.data?.[0]?.id, branchId: branches.data?.[0]?.id, serviceId: services.data?.find((x) => x.isActive !== false)?.id } });
    router.push("/dashboard/queue");
  });
  const start = useAction(async () => {
    const e = await api<{ id: string }>("clinic/encounters", { body: { patientId: id } });
    router.push(`/dashboard/encounters/${e.id}`);
  });
  if (p.loading && !p.data) return <Loading />;
  if (!p.data) return <ErrorText error={p.error} />;
  const d = p.data;
  return (
    <>
      <PageHeader
        title={`${d.firstName} ${d.lastName}`}
        subtitle={`${d.patientCode} · ${d.sex ?? ""} · ${dateOnly(d.dateOfBirth, locale)} · ${d.phone ?? ""}`}
        actions={<>
          {can("patient.update") && d.phone && <Button variant="secondary" loading={reissue.loading} onClick={() => reissue.run()}>{t("patients.newPin")}</Button>}
          {can("appointment.manage") && <Button variant="secondary" loading={walkIn.loading} onClick={() => walkIn.run()}>{t("queue.walkIn")}</Button>}
          {can("patient.update") && d.hasPortal && <Button variant="secondary" onClick={() => { setPwDone(false); setNewPw(""); setPwOpen(true); }}>{t("patients.setPassword")}</Button>}
          {can("medical_note.create") && <Button loading={start.loading} onClick={() => start.run()}>{t("appt.startVisit")}</Button>}
        </>}
      />
      <ErrorText error={reissue.error ?? start.error ?? walkIn.error ?? tl.error} />
      {pin && <div className="mb-4"><SecretBox label={pin.label} value={pin.value} /></div>}
      {!d.hasPortal && <div className="mb-4"><Alert tone="blue">{t("patients.noPortal")}</Alert></div>}
      {d.notesInternal && <Card className="mb-4 text-sm"><p className="mb-1 text-xs font-medium text-slate-500">{t("consult.internal")}</p>{d.notesInternal}</Card>}
      {!clinical ? <Alert tone="blue">{t("patients.noClinicalAccess")}</Alert> : tl.loading && !tl.data ? <Loading /> : tl.data && <TimelineView tl={tl.data} />}
      <Modal open={pwOpen} onClose={() => setPwOpen(false)} title={t("patients.setPassword")}>
        <div className="space-y-3">
          <p className="text-sm text-slate-600">{t("patients.setPasswordHelp")}</p>
          {pwDone ? <SecretBox label={t("patients.passwordSet")} value={newPw} /> : (
            <>
              <Field label={t("login.newPassword")} hint={t("login.passwordRule")}><Input value={newPw} onChange={(e) => setNewPw(e.target.value)} minLength={8} dir="ltr" autoComplete="off" /></Field>
              <ErrorText error={setPassword.error} />
              <Button className="w-full" loading={setPassword.loading} disabled={newPw.length < 8} onClick={() => setPassword.run()}>{t("common.save")}</Button>
            </>
          )}
        </div>
      </Modal>
    </>
  );
}
