"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { api } from "@/lib/client";
import { dayKey, money, timeOnly } from "@/lib/format";
import { Page, useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { VisitTypeBadge, VisitTypePicker, type VisitType } from "@/components/visit-type";
import { Button, ErrorText, Field, Input, Loading, Modal, PageHeader, Select, StatusBadge } from "@/components/ui";

type Appt = { id: string; patientId: string; patientName: string; patientCode: string; doctorId: string; doctorName: string; branchId: string; serviceName: string; visitType?: string; startAt: string; status: string; paymentStatus: string; paymentMethod: string; priceMinor?: number; currency: string; queueNumber?: number; patientNote?: string };
type Doctor = { id: string; displayName: string };
type Branch = { id: string; name: string };
type Service = { id: string; name: string; visitType?: string | null; isActive?: boolean };
type PatientRow = { id: string; firstName: string; lastName: string; patientCode: string; phone?: string };

export default function Appointments() {
  const { t, locale, timezone, currency } = useI18n();
  const router = useRouter();
  const { can } = useMe();
  const [date, setDate] = useState(() => dayKey(new Date(), timezone));
  const [doctorId, setDoctorId] = useState("");
  const list = useApi<Page<Appt>>(`clinic/appointments?from=${date}&to=${date}&doctorId=${doctorId}&pageSize=100`);
  const doctors = useApi<Doctor[]>("clinic/doctors");
  const branches = useApi<Branch[]>("clinic/branches");
  const services = useApi<Service[]>("clinic/services");
  const act = useAction(async (id: string, action: string) => { await api(`clinic/appointments/${id}/${action}`, { body: {} }); await list.reload(); });
  const startVisit = useAction(async (a: Appt) => {
    if (a.status === "CHECKED_IN") await api(`clinic/appointments/${a.id}/start`, { body: {} });
    const e = await api<{ id: string }>("clinic/encounters", { body: { patientId: a.patientId, appointmentId: a.id } });
    router.push(`/dashboard/encounters/${e.id}`);
  });

  // new booking by reception
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState("");
  const patients = useApi<Page<PatientRow>>(open ? `clinic/patients?q=${encodeURIComponent(q)}&pageSize=8` : null);
  const [nb, setNb] = useState({ patientId: "", doctorId: "", branchId: "", serviceId: "", date, slot: "" });
  const sel = { doctorId: nb.doctorId || doctors.data?.[0]?.id || "", branchId: nb.branchId || branches.data?.[0]?.id || "", serviceId: nb.serviceId || services.data?.[0]?.id || "" };
  const slotsApi = useApi<string[]>(open && sel.doctorId && sel.branchId && sel.serviceId ? `clinic/appointments/slots?doctorId=${sel.doctorId}&branchId=${sel.branchId}&serviceId=${sel.serviceId}&date=${nb.date}` : null);
  const slots = slotsApi.data ?? [];
  const book = useAction(async () => { await api("clinic/appointments", { body: { patientId: nb.patientId, doctorId: sel.doctorId, branchId: sel.branchId, serviceId: sel.serviceId, visitType: services.data?.find((x) => x.id === sel.serviceId)?.visitType ?? undefined, startAt: nb.slot, source: "RECEPTION" } }); setOpen(false); setNb({ ...nb, slot: "", patientId: "" }); await list.reload(); });

  const actions = (a: Appt) => {
    const b = (label: string, action: string, variant: "primary" | "secondary" | "danger" | "ghost" = "secondary") => <Button key={action} size="sm" variant={variant} onClick={() => act.run(a.id, action)}>{t(label)}</Button>;
    const out: React.ReactNode[] = [];
    if (a.status === "PENDING_CONFIRMATION") out.push(b("appt.confirm", "confirm", "primary"), b("appt.reject", "reject", "ghost"));
    if (a.status === "CONFIRMED") out.push(b("appt.checkIn", "check-in", "primary"), b("appt.noShow", "no-show", "ghost"), b("orders.cancel", "cancel", "ghost"));
    if (["CHECKED_IN", "IN_PROGRESS"].includes(a.status) && can("medical_note.create")) out.push(<Button key="visit" size="sm" loading={startVisit.loading} onClick={() => startVisit.run(a)}>{t("appt.startVisit")}</Button>);
    if (a.status === "IN_PROGRESS") out.push(b("appt.complete", "complete"));
    if (a.paymentMethod === "CASH_AT_CLINIC" && a.paymentStatus !== "PAID" && ["CONFIRMED", "CHECKED_IN", "IN_PROGRESS", "COMPLETED"].includes(a.status)) out.push(b("appt.markPaid", "mark-paid"));
    return out;
  };

  return (
    <>
      <PageHeader title={t("nav.appointments")} actions={<Button onClick={() => setOpen(true)}>{t("appt.new")}</Button>} />
      <div className="mb-4 grid gap-3 sm:grid-cols-3">
        <div className="flex gap-2"><Button variant="secondary" size="sm" onClick={() => setDate(dayKey(new Date(new Date(date + "T12:00:00Z").getTime() - 86400000), "UTC"))}>‹</Button><Input type="date" value={date} onChange={(e) => setDate(e.target.value)} dir="ltr" /><Button variant="secondary" size="sm" onClick={() => setDate(dayKey(new Date(new Date(date + "T12:00:00Z").getTime() + 86400000), "UTC"))}>›</Button></div>
        <Select value={doctorId} onChange={(e) => setDoctorId(e.target.value)}><option value="">{t("appt.allDoctors")}</option>{doctors.data?.map((d) => <option key={d.id} value={d.id}>{d.displayName}</option>)}</Select>
      </div>
      <ErrorText error={list.error ?? act.error ?? startVisit.error} />
      {list.loading && !list.data ? <Loading /> : list.data?.data.length === 0 ? <p className="rounded-xl border border-dashed p-10 text-center text-sm text-slate-500">{t("clinic.noToday")}</p> : (
        <ul className="space-y-2">{list.data?.data.map((a) => (
          <li key={a.id} className="flex flex-wrap items-center justify-between gap-3 rounded-xl border bg-white p-4 text-sm">
            <div className="flex items-center gap-4"><span className="font-mono text-base font-semibold">{timeOnly(a.startAt, locale, timezone)}</span>
              <div><p className="font-medium">{a.patientName} <span className="font-mono text-xs text-slate-500">{a.patientCode}</span></p><p className="flex flex-wrap items-center gap-2 text-slate-600">{a.serviceName} <VisitTypeBadge type={a.visitType} /> · {a.doctorName}{a.priceMinor ? ` · ${money(a.priceMinor, a.currency || currency, locale)}` : ""}</p>{a.patientNote && <p className="text-xs italic text-slate-500">“{a.patientNote}”</p>}</div></div>
            <div className="flex flex-wrap items-center gap-2"><StatusBadge status={a.status} />{a.paymentStatus === "PAID" && <StatusBadge status="PAID" />}{actions(a)}</div>
          </li>
        ))}</ul>
      )}
      <Modal open={open} onClose={() => setOpen(false)} title={t("appt.new")} wide>
        <div className="space-y-3">
          <Field label={t("clinic.patient")}>
            <div className="space-y-2"><Input placeholder={t("patients.searchHint")} value={q} onChange={(e) => setQ(e.target.value)} />
              <div className="max-h-36 overflow-y-auto rounded-lg border">{patients.data?.data.map((p) => <button type="button" key={p.id} onClick={() => setNb({ ...nb, patientId: p.id })} className={`block w-full px-3 py-2 text-start text-sm ${nb.patientId === p.id ? "bg-brand text-white" : "hover:bg-slate-50"}`}>{p.firstName} {p.lastName} <span className="font-mono text-xs opacity-70">{p.patientCode}</span></button>)}</div></div>
          </Field>
          <div className="grid gap-3 sm:grid-cols-3">
            <Field label={t("book.doctor")}><Select value={sel.doctorId} onChange={(e) => setNb({ ...nb, doctorId: e.target.value, slot: "" })}>{doctors.data?.map((d) => <option key={d.id} value={d.id}>{d.displayName}</option>)}</Select></Field>
            <Field label={t("book.branch")}><Select value={sel.branchId} onChange={(e) => setNb({ ...nb, branchId: e.target.value, slot: "" })}>{branches.data?.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}</Select></Field>
            <Field label={t("book.service")}><Select value={sel.serviceId} onChange={(e) => setNb({ ...nb, serviceId: e.target.value, slot: "" })}>{services.data?.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}</Select></Field>
          </div>
          <VisitTypePicker value={(services.data?.find((x) => x.id === sel.serviceId)?.visitType as VisitType | undefined) ?? "CONSULTATION"} onChange={(v) => { const m = services.data?.find((x) => x.visitType === v && x.isActive !== false); if (m) setNb({ ...nb, serviceId: m.id, slot: "" }); }} />
          <Field label={t("book.day")}><Input type="date" value={nb.date} onChange={(e) => setNb({ ...nb, date: e.target.value, slot: "" })} dir="ltr" /></Field>
          <div className="grid grid-cols-4 gap-2 sm:grid-cols-6">{slots.map((s) => <button type="button" key={s} onClick={() => setNb({ ...nb, slot: s })} className={`rounded-lg border px-1 py-1.5 text-sm ${nb.slot === s ? "border-brand bg-brand text-white" : "hover:border-brand"}`}>{timeOnly(s, locale, timezone)}</button>)}</div>
          {slots.length === 0 && <p className="text-sm text-slate-500">{t("book.noSlots")}</p>}
          <ErrorText error={book.error} />
          <Button className="w-full" loading={book.loading} disabled={!nb.patientId || !nb.slot} onClick={() => book.run()}>{t("book.confirm")}</Button>
        </div>
      </Modal>
    </>
  );
}
