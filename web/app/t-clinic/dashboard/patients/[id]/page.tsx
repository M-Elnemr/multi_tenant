"use client";

import { localPhone } from "@/lib/phone";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { use, useState } from "react";
import { api } from "@/lib/client";
import { TimelineView, type StaffTimeline } from "@/components/dashboard/timeline-view";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { Menu, Sheet, type MenuItem } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { Alert, Avatar, Button, ErrorText, Field, Input, Loading, PageHeader, SecretBox, Select, Textarea } from "@/components/ui";
import { PhoneInput } from "@/components/inputs";
import { WhatsAppButton } from "@/components/whatsapp-button";
import { usePatientPortal } from "@/components/portal-flag";
import { ageText } from "@/lib/format";
import { PatientIdCard } from "@/components/patient-id-card";
import { VisitTypePicker, type VisitType } from "@/components/visit-type";
import { Facts, Paper, PaperHead, Pill, Rise } from "@/components/clinic/dash";

type Patient = { id: string; patientCode: string; firstName: string; lastName: string; phone?: string; ageYears?: number; ageMonths?: number; sex?: string; hasPortal: boolean; publicId?: number; notesInternal?: string; addressText?: string; bloodType?: string; guardianPatientId?: string; guardianName?: string };
type Kid = { id: string; patientCode: string; firstName: string; lastName: string; ageYears?: number; ageMonths?: number };
const BLOOD = ["A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"];

export default function PatientPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { t, locale } = useI18n();
  const router = useRouter();
  const toast = useToast();
  const { can } = useMe();
  const portal = usePatientPortal();
  const clinical = can("medical_note.create") || can("prescription.create") || can("lab_order.create") || can("medical_note.read") || can("prescription.read") || can("lab_order.read");
  const p = useApi<Patient>(`clinic/patients/${id}`);
  const clinic = useApi<{ clinicName?: string }>("clinic/public/profile");
  const [editOpen, setEditOpen] = useState(false);
  const [form, setForm] = useState({ name: "", phone: "", ageYears: "", ageMonths: "0", sex: "", addressText: "", bloodType: "", notes: "" });
  const saveEdit = useAction(async () => {
    await api(`clinic/patients/${id}`, { method: "PATCH", body: { firstName: form.name.trim(), lastName: "", phone: form.phone, ageYears: Number(form.ageYears), ageMonths: Number(form.ageMonths || 0), sex: form.sex || undefined, addressText: form.addressText, bloodType: form.bloodType, notesInternal: form.notes } });
    setEditOpen(false);
    await p.reload();
    toast(t("common.saved"));
  });
  const tl = useApi<StaffTimeline>(clinical ? `clinic/patients/${id}/timeline` : null);
  // Children of this patient (clinic list nests them under the parent; search by this patient's code finds the parent row with them).
  const kids = useApi<{ data: { id: string; dependents?: Kid[] }[] }>(portal && p.data?.hasPortal ? `clinic/patients?q=${encodeURIComponent(p.data.patientCode)}` : null);
  const children = kids.data?.data.find((x) => x.id === id)?.dependents ?? [];
  const [depOpen, setDepOpen] = useState(false);
  const [dep, setDep] = useState({ name: "", ageYears: "", ageMonths: "0", sex: "" });
  const addDep = useAction(async () => {
    await api("clinic/patients", { body: { firstName: dep.name.trim(), guardianPatientId: id, relationship: "PARENT", ageYears: Number(dep.ageYears), ageMonths: Number(dep.ageMonths || 0), sex: dep.sex || undefined } });
    setDepOpen(false); setDep({ name: "", ageYears: "", ageMonths: "0", sex: "" });
    await kids.reload();
    toast(t("cld.patientAdded"));
  });
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
  if (p.loading && !p.data) return <Loading variant="detail" />;
  if (!p.data) return <ErrorText error={p.error} />;
  const d = p.data;
  const fullName = `${d.firstName} ${d.lastName}`.trim();
  const sexText = d.sex === "F" ? t("patients.female") : d.sex === "M" ? t("patients.male") : "";

  const openEdit = () => { setForm({ name: fullName, phone: localPhone(d.phone), ageYears: d.ageYears === undefined ? "" : String(d.ageYears), ageMonths: String(d.ageMonths ?? 0), sex: d.sex ?? "", addressText: d.addressText ?? "", bloodType: d.bloodType ?? "", notes: d.notesInternal ?? "" }); setEditOpen(true); };
  const openQueue = () => { setVisitType(suggestFollowUp ? "FOLLOW_UP" : "CONSULTATION"); setQueueOpen(true); };
  const more: MenuItem[] = [];
  if (can("patient.update")) more.push({ label: t("patients.edit"), icon: "edit", onSelect: openEdit });
  if (can("appointment.manage")) more.push({ label: t("queue.walkIn"), icon: "queue", onSelect: openQueue });
  if (portal && can("patient.update") && d.hasPortal) more.push({ label: t("patients.setPassword"), icon: "lock", onSelect: () => { setPwDone(false); setNewPw(""); setPwOpen(true); } });
  if (can("patient.export") && clinical) more.push({ label: t("record.exportStaff"), icon: "file", onSelect: () => { window.open(`/api/bff/clinic/patients/${id}/export`, "_self"); } });

  return (
    <>
      <PageHeader
        eyebrow={<Link href="/dashboard/patients" className="inline-flex items-center gap-1 hover:text-ink"><Icon name="chevron" className="h-3 w-3 rotate-180 flip-rtl" />{t("nav.patients")}</Link>}
        title={fullName}
        subtitle={[d.patientCode, sexText, d.ageYears === undefined ? "" : ageText(d.ageYears, d.ageMonths, locale), localPhone(d.phone)].filter(Boolean).join(" · ")}
        actions={<>
          <WhatsAppButton phone={d.phone} message={t("pq.waHello", { name: d.firstName, clinic: clinic.data?.clinicName ?? "" })} size={40} />
          {can("medical_note.create") && <Button loading={start.loading} onClick={() => start.run()}><Icon name="stethoscope" className="h-4 w-4" />{t("appt.startVisit")}</Button>}
          {more.length > 0 && <Menu label={t("cld.moreActions")} items={more} trigger={({ toggle }) => <Button variant="secondary" onClick={toggle}>{t("common.more")}<Icon name="chevron" className="h-3.5 w-3.5 rotate-90" /></Button>} />}
        </>}
      />
      <div className="space-y-4">
        <ErrorText error={start.error ?? tl.error} />
        {portal && !d.hasPortal && !d.guardianPatientId && <Alert tone="blue">{t("patients.noPortal")}</Alert>}
        {d.guardianPatientId && <Alert tone="blue">{t("dep.childOf", { name: "" }).trim()} <Link href={`/dashboard/patients/${d.guardianPatientId}`} className="font-semibold underline">{d.guardianName}</Link></Alert>}

        <div className="grid items-start gap-5 lg:grid-cols-[1fr_21rem]">
          {/* Main: the chart (visits, prescriptions, labs, documents) */}
          <div className="min-w-0 order-2 space-y-4 lg:order-1">
            {!clinical ? <Alert tone="blue">{t("patients.noClinicalAccess")}</Alert> : tl.loading && !tl.data ? <Loading variant="list" /> : tl.data && <Rise><TimelineView tl={tl.data} /></Rise>}
          </div>

          {/* Side: who this is */}
          <aside className="order-1 space-y-4 lg:order-2 lg:sticky lg:top-4">
            <Paper className="overflow-hidden">
              <div className="flex items-center gap-3 bg-[linear-gradient(160deg,color-mix(in_srgb,var(--rail-accent)_11%,var(--surface)),var(--surface)_70%)] p-5">
                <Avatar name={fullName} className="h-14 w-14 text-lg" />
                <div className="min-w-0">
                  <p className="truncate font-display text-lg font-semibold text-ink">{fullName}</p>
                  <div className="mt-1 flex flex-wrap gap-1.5">{d.bloodType && <Pill tone="rose">{d.bloodType}</Pill>}{sexText && <Pill>{sexText}</Pill>}{d.hasPortal && portal && <Pill tone="sage">{t("patients.portal")}</Pill>}</div>
                </div>
              </div>
              <div className="px-5 pb-4">
                <Facts rows={[
                  { label: t("patients.code"), value: d.patientCode, ltr: true },
                  { label: t("patients.mobile"), value: localPhone(d.phone) || "-", ltr: true },
                  { label: t("patients.age"), value: ageText(d.ageYears, d.ageMonths, locale) },
                  { label: t("patients.blood"), value: d.bloodType || "-" },
                  { label: t("patients.address"), value: d.addressText || "-" },
                ]} />
              </div>
            </Paper>
            {d.notesInternal && (
              <Paper className="p-5">
                <p className="mb-2 flex items-center gap-2 text-[13px] font-semibold text-ink-2"><Icon name="lock" className="h-3.5 w-3.5 text-muted" />{t("patients.notes")}</p>
                <p className="whitespace-pre-wrap text-sm leading-relaxed text-ink-2">{d.notesInternal}</p>
              </Paper>
            )}
            {d.publicId && !d.guardianPatientId && <PatientIdCard publicId={d.publicId} name={fullName} compact />}
            {portal && d.hasPortal && !d.guardianPatientId && (
              <Paper>
                <PaperHead icon="users" title={t("dep.title")} action={can("patient.create") && <Button size="sm" variant="secondary" onClick={() => setDepOpen(true)}><Icon name="plus" className="h-3.5 w-3.5" />{t("dep.add")}</Button>} />
                <div className="px-3 pb-3">
                  {children.length === 0 ? <p className="px-2 pb-2 text-sm text-muted">{t("dep.none")}</p> : <ul className="divide-y divide-line text-sm">{children.map((c) => (
                    <li key={c.id}><Link href={`/dashboard/patients/${c.id}`} className="flex items-center gap-3 rounded-[12px] px-2 py-2.5 hover:bg-surface-2"><Avatar name={`${c.firstName} ${c.lastName}`} className="h-8 w-8 text-xs" /><span className="min-w-0 flex-1 truncate font-semibold text-ink">{c.firstName} {c.lastName}</span><span className="text-xs text-muted">{ageText(c.ageYears, c.ageMonths, locale)}</span></Link></li>
                  ))}</ul>}
                </div>
              </Paper>
            )}
          </aside>
        </div>
      </div>

      <Sheet open={queueOpen} onClose={() => setQueueOpen(false)} title={t("queue.addTitle")}
        footer={<><ErrorText error={walkIn.error} /><Button className="mt-2 w-full" loading={walkIn.loading} onClick={() => walkIn.run()}>{t("queue.add")}</Button></>}>
        <div className="space-y-4">
          <p className="font-display text-lg font-semibold text-ink">{fullName}</p>
          <VisitTypePicker value={visitType} onChange={setVisitType} />
          {suggestFollowUp && visitType === "FOLLOW_UP" && <p className="text-xs text-muted">{t("visit.suggested")}</p>}
          {(doctors.data?.length ?? 0) > 1 && <Field label={t("book.doctor")}><Select value={queueDoctor || doctors.data?.[0]?.id} onChange={(e) => setQueueDoctor(e.target.value)}>{doctors.data?.map((x) => <option key={x.id} value={x.id}>{x.displayName}</option>)}</Select></Field>}
        </div>
      </Sheet>

      <Sheet open={depOpen} onClose={() => setDepOpen(false)} title={t("dep.addTitle", { name: d.firstName })}>
        <form onSubmit={(e) => { e.preventDefault(); void addDep.run(); }} className="space-y-4">
          <p className="rounded-[12px] bg-surface-2 p-3 text-sm text-muted ring-1 ring-inset ring-line">{t("dep.noPhoneHint")}</p>
          <Field label={t("patients.name")}><Input value={dep.name} onChange={(e) => setDep({ ...dep, name: e.target.value })} required maxLength={100} /></Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label={t("patients.years")}><Input type="number" inputMode="numeric" min={0} max={130} step={1} value={dep.ageYears} onChange={(e) => setDep({ ...dep, ageYears: e.target.value })} required dir="ltr" /></Field>
            <Field label={t("patients.months")}><Input type="number" inputMode="numeric" min={0} max={11} step={1} value={dep.ageMonths} onChange={(e) => setDep({ ...dep, ageMonths: String(Math.min(11, Math.max(0, Math.floor(Number(e.target.value) || 0)))) })} dir="ltr" /></Field>
          </div>
          <Field label={t("patients.sex")}><Select value={dep.sex} onChange={(e) => setDep({ ...dep, sex: e.target.value })}><option value="">-</option><option value="F">{t("patients.female")}</option><option value="M">{t("patients.male")}</option></Select></Field>
          <ErrorText error={addDep.error} />
          <Button type="submit" loading={addDep.loading} className="w-full">{t("common.save")}</Button>
        </form>
      </Sheet>

      <Sheet open={editOpen} onClose={() => setEditOpen(false)} title={t("patients.editTitle")}>
        <form onSubmit={(e) => { e.preventDefault(); void saveEdit.run(); }} className="space-y-4">
          <Field label={t("patients.name")}><Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required maxLength={100} /></Field>
          {!d.guardianPatientId && <Field label={t("patients.mobile")}><PhoneInput value={form.phone} onValue={(phone) => setForm({ ...form, phone })} required /></Field>}
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
      </Sheet>

      <Sheet open={pwOpen} onClose={() => setPwOpen(false)} title={t("patients.setPassword")}>
        <div className="space-y-4">
          <p className="text-sm text-muted">{t("patients.setPasswordHelp")}</p>
          {pwDone ? <SecretBox label={t("patients.passwordSet")} value={newPw} /> : (
            <>
              <Field label={t("login.newPassword")} hint={t("login.passwordRule")}><Input value={newPw} onChange={(e) => setNewPw(e.target.value)} minLength={8} dir="ltr" autoComplete="off" /></Field>
              <ErrorText error={setPassword.error} />
              <Button className="w-full" loading={setPassword.loading} disabled={newPw.length < 8} onClick={() => setPassword.run()}>{t("common.save")}</Button>
            </>
          )}
        </div>
      </Sheet>
    </>
  );
}
