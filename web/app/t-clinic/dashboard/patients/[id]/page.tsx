"use client";

import { useRouter } from "next/navigation";
import { use, useState } from "react";
import { api } from "@/lib/client";
import { TimelineView, type StaffTimeline } from "@/components/dashboard/timeline-view";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Button, Card, ErrorText, Field, Input, Loading, Modal, PageHeader, SecretBox, Select, Textarea } from "@/components/ui";
import { PhoneInput } from "@/components/inputs";
import { WhatsAppButton } from "@/components/whatsapp-button";
import { usePatientPortal } from "@/components/portal-flag";
import { ageText } from "@/lib/format";
import { VisitTypePicker, type VisitType } from "@/components/visit-type";

type Patient = { id: string; patientCode: string; firstName: string; lastName: string; phone?: string; ageYears?: number; ageMonths?: number; sex?: string; hasPortal: boolean; notesInternal?: string; addressText?: string; bloodType?: string };
const BLOOD = ["A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"];

export default function PatientPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { t, locale } = useI18n();
  const router = useRouter();
  const { can } = useMe();
  const portal = usePatientPortal();
  const clinical = can("medical_note.create") || can("prescription.create") || can("lab_order.create");
  const p = useApi<Patient>(`clinic/patients/${id}`);
  const clinic = useApi<{ clinicName?: string }>("clinic/public/profile");
  const [editOpen, setEditOpen] = useState(false);
  const [form, setForm] = useState({ name: "", phone: "", ageYears: "", ageMonths: "0", sex: "", addressText: "", bloodType: "", notes: "" });
  const saveEdit = useAction(async () => {
    await api(`clinic/patients/${id}`, { method: "PATCH", body: { firstName: form.name.trim(), lastName: "", phone: form.phone, ageYears: Number(form.ageYears), ageMonths: Number(form.ageMonths || 0), sex: form.sex || undefined, addressText: form.addressText, bloodType: form.bloodType, notesInternal: form.notes } });
    setEditOpen(false);
    await p.reload();
  });
  const tl = useApi<StaffTimeline>(clinical ? `clinic/patients/${id}/timeline` : null);
  const [pwOpen, setPwOpen] = useState(false);
  const [newPw, setNewPw] = useState("");
  const [pwDone, setPwDone] = useState(false);
  const setPassword = useAction(async () => { await api(`clinic/patients/${id}/set-password`, { body: { password: newPw } }); setPwDone(true); });
  const doctors = useApi<{ id: string; displayName: string }[]>(can("appointment.manage") ? "clinic/doctors" : null);
  const branches = useApi<{ id: string }[]>(can("appointment.manage") ? "clinic/branches" : null);
  const [queueOpen, setQueueOpen] = useState(false);
  const [visitType, setVisitType] = useState<VisitType>("CONSULTATION");
  const [queueDoctor, setQueueDoctor] = useState("");
  // Suggest "follow-up" when the last visit was within two weeks; the person at the desk can always change it.
  const lastVisit = tl.data?.visits[0]?.visitAt;
  const [now] = useState(() => Date.now());
  const suggestFollowUp = !!lastVisit && now - new Date(lastVisit).getTime() < 14 * 86_400_000;
  const walkIn = useAction(async () => {
    await api("clinic/appointments/walk-in", { body: { patientId: id, doctorId: queueDoctor || doctors.data?.[0]?.id, branchId: branches.data?.[0]?.id, visitType } });
    setQueueOpen(false);
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
        subtitle={[d.patientCode, d.sex === "F" ? t("patients.female") : d.sex === "M" ? t("patients.male") : "", d.ageYears === undefined ? "" : ageText(d.ageYears, d.ageMonths, locale), d.phone ?? ""].filter(Boolean).join(" · ")}
        actions={<>
          <WhatsAppButton phone={d.phone} message={t("pq.waHello", { name: d.firstName, clinic: clinic.data?.clinicName ?? "" })} size={40} />
          {can("patient.update") && <Button variant="secondary" onClick={() => { setForm({ name: `${d.firstName} ${d.lastName}`.trim(), phone: d.phone ?? "", ageYears: d.ageYears === undefined ? "" : String(d.ageYears), ageMonths: String(d.ageMonths ?? 0), sex: d.sex ?? "", addressText: d.addressText ?? "", bloodType: d.bloodType ?? "", notes: d.notesInternal ?? "" }); setEditOpen(true); }}>{t("patients.edit")}</Button>}
          {can("appointment.manage") && <Button variant="secondary" onClick={() => { setVisitType(suggestFollowUp ? "FOLLOW_UP" : "CONSULTATION"); setQueueOpen(true); }}>{t("queue.walkIn")}</Button>}
          {portal && can("patient.update") && d.hasPortal && <Button variant="secondary" onClick={() => { setPwDone(false); setNewPw(""); setPwOpen(true); }}>{t("patients.setPassword")}</Button>}
          {can("patient.export") && clinical && <a className="inline-flex h-10 items-center rounded-lg border px-3 text-sm hover:bg-slate-50" href={`/api/bff/clinic/patients/${id}/export`}>{t("record.exportStaff")}</a>}
          {can("medical_note.create") && <Button loading={start.loading} onClick={() => start.run()}>{t("appt.startVisit")}</Button>}
        </>}
      />
      <Modal open={queueOpen} onClose={() => setQueueOpen(false)} title={t("queue.addTitle")}>
        <div className="space-y-4">
          <p className="font-semibold">{p.data ? `${p.data.firstName} ${p.data.lastName}` : ""}</p>
          <VisitTypePicker value={visitType} onChange={setVisitType} />
          {suggestFollowUp && visitType === "FOLLOW_UP" && <p className="text-xs text-slate-500">{t("visit.suggested")}</p>}
          {(doctors.data?.length ?? 0) > 1 && <Field label={t("book.doctor")}><Select value={queueDoctor || doctors.data?.[0]?.id} onChange={(e) => setQueueDoctor(e.target.value)}>{doctors.data?.map((x) => <option key={x.id} value={x.id}>{x.displayName}</option>)}</Select></Field>}
          <ErrorText error={walkIn.error} />
          <Button className="w-full" loading={walkIn.loading} onClick={() => walkIn.run()}>{t("queue.add")}</Button>
        </div>
      </Modal>
      <ErrorText error={start.error ?? tl.error} />
      {portal && !d.hasPortal && <div className="mb-4"><Alert tone="blue">{t("patients.noPortal")}</Alert></div>}
      <Card className="mb-4">
        <h2 className="mb-3 font-medium">{t("patients.detailsTitle")}</h2>
        <dl className="grid gap-x-8 gap-y-2 text-sm sm:grid-cols-2">
          {([
            [t("patients.name"), `${d.firstName} ${d.lastName}`.trim()],
            [t("patients.code"), d.patientCode],
            [t("patients.mobile"), d.phone ?? "-"],
            [t("patients.age"), ageText(d.ageYears, d.ageMonths, locale)],
            [t("patients.sex"), d.sex === "F" ? t("patients.female") : d.sex === "M" ? t("patients.male") : "-"],
            [t("patients.blood"), d.bloodType || "-"],
            [t("patients.address"), d.addressText || "-"],
          ] as const).map(([k, v]) => <div key={k} className="flex justify-between gap-4 border-b border-slate-100 py-1"><dt className="text-slate-500">{k}</dt><dd className="text-end font-medium" dir={k === t("patients.mobile") || k === t("patients.code") ? "ltr" : undefined}>{v}</dd></div>)}
        </dl>
        {d.notesInternal && <div className="mt-3 text-sm"><p className="mb-1 text-xs font-medium text-slate-500">{t("patients.notes")}</p><p className="whitespace-pre-wrap">{d.notesInternal}</p></div>}
      </Card>
      {!clinical ? <Alert tone="blue">{t("patients.noClinicalAccess")}</Alert> : tl.loading && !tl.data ? <Loading /> : tl.data && <TimelineView tl={tl.data} />}
      <Modal open={editOpen} onClose={() => setEditOpen(false)} title={t("patients.editTitle")}>
        <form onSubmit={(e) => { e.preventDefault(); void saveEdit.run(); }} className="space-y-3">
          <Field label={t("patients.name")}><Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required maxLength={100} /></Field>
          <Field label={t("patients.mobile")}><PhoneInput value={form.phone} onValue={(phone) => setForm({ ...form, phone })} required /></Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label={t("patients.years")}><Input type="number" inputMode="numeric" min={0} max={130} step={1} value={form.ageYears} onChange={(e) => setForm({ ...form, ageYears: e.target.value })} required dir="ltr" /></Field>
            <Field label={t("patients.months")}><Input type="number" inputMode="numeric" min={0} max={11} step={1} value={form.ageMonths} onChange={(e) => setForm({ ...form, ageMonths: String(Math.min(11, Math.max(0, Math.floor(Number(e.target.value) || 0)))) })} dir="ltr" /></Field>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <Field label={t("patients.sex")}><Select value={form.sex} onChange={(e) => setForm({ ...form, sex: e.target.value })}><option value="">-</option><option value="F">{t("patients.female")}</option><option value="M">{t("patients.male")}</option></Select></Field>
            <Field label={t("patients.blood")}><Select value={form.bloodType} onChange={(e) => setForm({ ...form, bloodType: e.target.value })}><option value="">-</option>{BLOOD.map((b) => <option key={b} value={b}>{b}</option>)}</Select></Field>
          </div>
          <Field label={t("patients.address")}><Input value={form.addressText} onChange={(e) => setForm({ ...form, addressText: e.target.value })} maxLength={300} /></Field>
          <Field label={t("patients.notes")}><Textarea value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} /></Field>
          <ErrorText error={saveEdit.error} />
          <Button type="submit" loading={saveEdit.loading} className="w-full">{t("common.save")}</Button>
        </form>
      </Modal>
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
