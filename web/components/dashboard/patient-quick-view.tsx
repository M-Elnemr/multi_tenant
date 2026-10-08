"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { api } from "@/lib/client";
import { TimelineView, type StaffTimeline } from "@/components/dashboard/timeline-view";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Button, ErrorText, Loading, Modal } from "@/components/ui";
import { ageText } from "@/lib/format";

type Patient = {
  id: string; patientCode: string; firstName: string; lastName: string; phone?: string; sex?: string; ageYears?: number; ageMonths?: number;
  addressText?: string; bloodType?: string; notesInternal?: string;
};

/** WhatsApp wants the number without "+" or spaces: +201012345678 -> 201012345678 */
export const whatsappLink = (phone: string, message: string) => `https://wa.me/${phone.replace(/\D/g, "")}?text=${encodeURIComponent(message)}`;

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return <div className="flex justify-between gap-4 border-b border-slate-100 py-1.5 text-sm last:border-0"><span className="text-slate-500">{label}</span><span className="text-end font-medium">{children}</span></div>;
}

/** Everything about one patient in a popup, with a one-click WhatsApp message to their number (the doctor presses send in WhatsApp). */
export function PatientQuickView({ id, onClose }: { id: string | null; onClose: () => void }) {
  const { t } = useI18n();
  return (
    <Modal open={id !== null} onClose={onClose} title={t("pq.title")} wide>
      {id && <Body key={id} id={id} />}
    </Modal>
  );
}

function Body({ id }: { id: string }) {
  const { t, locale } = useI18n();
  const router = useRouter();
  const { can } = useMe();
  const clinical = can("medical_note.create") || can("prescription.create") || can("lab_order.create");
  const p = useApi<Patient>(`clinic/patients/${id}`);
  const clinic = useApi<{ clinicName?: string }>("clinic/public/profile");
  const tl = useApi<StaffTimeline>(clinical ? `clinic/patients/${id}/timeline` : null);
  const doctors = useApi<{ id: string }[]>(can("appointment.manage") ? "clinic/doctors" : null);
  const branches = useApi<{ id: string }[]>(can("appointment.manage") ? "clinic/branches" : null);
  const services = useApi<{ id: string; isActive?: boolean }[]>(can("appointment.manage") ? "clinic/services" : null);
  const [edited, setEdited] = useState<string | null>(null);
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
  const name = `${d.firstName} ${d.lastName}`.trim();
  const message = edited ?? t("pq.waHello", { name: d.firstName, clinic: clinic.data?.clinicName ?? "" });
  return (
    <div className="space-y-5">
      <div>
        <p className="text-xl font-semibold">{name}</p>
        <p className="font-mono text-xs text-slate-500">{d.patientCode}</p>
      </div>
      <div className="rounded-xl border p-3">
        <Row label={t("patients.age")}>{ageText(d.ageYears, d.ageMonths, locale)}</Row>
        <Row label={t("patients.sex")}>{d.sex === "F" ? t("patients.female") : d.sex === "M" ? t("patients.male") : "-"}</Row>
        <Row label={t("patients.mobile")}><span dir="ltr">{d.phone ?? "-"}</span></Row>
        {d.addressText && <Row label={t("pq.address")}>{d.addressText}</Row>}
        {d.bloodType && <Row label={t("pq.blood")}>{d.bloodType}</Row>}
        {d.notesInternal && <Row label={t("consult.internal")}>{d.notesInternal}</Row>}
      </div>

      {d.phone && (
        <div className="space-y-2 rounded-xl border border-emerald-200 bg-emerald-50 p-3">
          <label className="block text-sm font-medium" htmlFor="wa-msg">{t("pq.message")}</label>
          <textarea id="wa-msg" value={message} onChange={(e) => setEdited(e.target.value)} rows={3} className="w-full rounded-lg border border-slate-300 bg-white p-2 text-sm" />
          <p className="text-xs text-slate-600">{t("pq.waHint")}</p>
          <div className="flex flex-wrap gap-2">
            <a href={whatsappLink(d.phone, message)} target="_blank" rel="noopener noreferrer" className="inline-flex h-10 items-center rounded-lg bg-emerald-600 px-4 text-sm font-medium text-white hover:bg-emerald-700">{t("pq.whatsapp")}</a>
            <a href={`tel:${d.phone}`} className="inline-flex h-10 items-center rounded-lg border bg-white px-4 text-sm hover:bg-slate-50">{t("pq.call")}</a>
          </div>
        </div>
      )}

      <div className="flex flex-wrap gap-2">
        {can("appointment.manage") && <Button variant="secondary" loading={walkIn.loading} onClick={() => walkIn.run()}>{t("queue.walkIn")}</Button>}
        {can("medical_note.create") && <Button loading={start.loading} onClick={() => start.run()}>{t("appt.startVisit")}</Button>}
        <Link href={`/dashboard/patients/${id}`} className="inline-flex h-10 items-center rounded-lg border px-4 text-sm hover:bg-slate-50">{t("pq.openRecord")}</Link>
      </div>
      <ErrorText error={walkIn.error ?? start.error ?? tl.error} />

      {clinical && (tl.loading && !tl.data ? <Loading /> : tl.data && <div className="max-h-96 overflow-y-auto rounded-xl border p-3"><TimelineView tl={tl.data} /></div>)}
    </div>
  );
}
