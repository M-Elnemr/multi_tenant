"use client";

import { useI18n } from "../i18n-provider";
import { Card, Empty, StatusBadge } from "../ui";
import { dateTime } from "@/lib/format";

export type StaffTimeline = {
  patient: { id: string; patientCode: string; firstName: string; lastName: string; dateOfBirth?: string; sex?: string; phone?: string; bloodType?: string };
  appointments: { id: string; startAt: string; status: string; doctorName: string; serviceName: string }[];
  visits: {
    id: string; visitAt: string; doctorName: string; chiefComplaint?: string; clinicalSummary?: string; followUpDate?: string;
    vitals?: { temperatureC?: number; heartRateBpm?: number; systolicBp?: number; diastolicBp?: number; weightKg?: number; oxygenSaturation?: number }[];
    conditions?: { name: string; status: string }[];
    notes: { id: string; noteType: string; content: string; isPatientVisible?: boolean; createdAt: string }[];
  }[];
  prescriptions: { id: string; status: string; issuedAt?: string; doctorName: string; items: { medicationName: string; strength?: string; dosage?: string; frequency?: string; duration?: string }[] }[];
  labOrders: { id: string; testName: string; status: string; priority: string; orderedAt: string; results: { id: string; resultText?: string; fileId?: string; uploadedByPatient: boolean }[] }[];
  documents: { id: string; title: string; documentType: string; fileId?: string; patientVisible?: boolean }[];
};

/** Chronological chart of one patient (staff view). Internal items are visibly marked as internal. */
export function TimelineView({ tl }: { tl: StaffTimeline }) {
  const { t, locale, timezone } = useI18n();
  const dt = (s?: string) => dateTime(s, locale, timezone);
  return (
    <div className="space-y-6">
      <section>
        <h3 className="mb-2 font-medium">{t("portal.visits")}</h3>
        {tl.visits.length === 0 ? <Empty>{t("portal.none")}</Empty> : tl.visits.map((v) => (
          <Card key={v.id} className="mb-3 space-y-2 text-sm">
            <p className="font-medium">{dt(v.visitAt)} · {v.doctorName}</p>
            {v.chiefComplaint && <p><span className="text-slate-500">{t("consult.complaint")}:</span> {v.chiefComplaint}</p>}
            {v.vitals && v.vitals.length > 0 && <p className="text-slate-600">{v.vitals.map((x) => [x.temperatureC && `${x.temperatureC}°C`, x.heartRateBpm && `${x.heartRateBpm} bpm`, x.systolicBp && `${x.systolicBp}/${x.diastolicBp}`, x.weightKg && `${x.weightKg} kg`, x.oxygenSaturation && `SpO₂ ${x.oxygenSaturation}%`].filter(Boolean).join(" · ")).join(" | ")}</p>}
            {v.conditions && v.conditions.length > 0 && <p><span className="text-slate-500">{t("consult.conditions")}:</span> {v.conditions.map((c) => c.name).join(", ")}</p>}
            {v.clinicalSummary && <p className="rounded-lg bg-slate-50 p-2">{v.clinicalSummary}</p>}
            {v.notes.map((n) => <p key={n.id} className="rounded-lg bg-slate-50 p-2">{n.content} {n.isPatientVisible ? <span className="text-xs text-emerald-700">· {t("consult.visibleToPatient")}</span> : <span className="text-xs text-slate-500">· {t("consult.internal")}</span>}</p>)}
          </Card>
        ))}
      </section>
      <section>
        <h3 className="mb-2 font-medium">{t("portal.prescriptions")}</h3>
        {tl.prescriptions.length === 0 ? <Empty>{t("portal.none")}</Empty> : tl.prescriptions.map((p) => (
          <Card key={p.id} className="mb-3 text-sm">
            <p className="mb-1 flex items-center gap-2"><StatusBadge status={p.status} /><span className="text-slate-500">{dt(p.issuedAt)} · {p.doctorName}</span></p>
            <ul>{p.items.map((i, k) => <li key={k}><b>{i.medicationName}</b> {i.strength} {i.dosage} {i.frequency} {i.duration}</li>)}</ul>
          </Card>
        ))}
      </section>
      <section>
        <h3 className="mb-2 font-medium">{t("portal.labs")}</h3>
        {tl.labOrders.length === 0 ? <Empty>{t("portal.none")}</Empty> : tl.labOrders.map((l) => (
          <Card key={l.id} className="mb-3 text-sm">
            <p className="flex items-center justify-between"><b>{l.testName}</b><StatusBadge status={l.status} /></p>
            {l.results.map((r) => <p key={r.id} className="mt-1 rounded-lg bg-slate-50 p-2">{r.resultText} {r.fileId && <a className="text-brand underline" href={`/api/bff/files/${r.fileId}/content`} target="_blank" rel="noreferrer">{t("portal.openFile")}</a>} {r.uploadedByPatient && <span className="text-xs text-slate-500">· {t("consult.byPatient")}</span>}</p>)}
          </Card>
        ))}
      </section>
      {tl.documents.length > 0 && (
        <section>
          <h3 className="mb-2 font-medium">{t("portal.documents")}</h3>
          <ul className="space-y-1 text-sm">{tl.documents.map((d) => <li key={d.id}>{d.title} {d.fileId && <a className="text-brand underline" href={`/api/bff/files/${d.fileId}/content`} target="_blank" rel="noreferrer">{t("portal.openFile")}</a>}</li>)}</ul>
        </section>
      )}
    </div>
  );
}
