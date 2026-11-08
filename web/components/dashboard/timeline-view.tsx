"use client";

import { useState } from "react";
import { useI18n } from "../i18n-provider";
import { Icon } from "../icons";
import { Empty, Tabs } from "../ui";
import { PrintRxButton, RxPhoto } from "../print-rx";
import { VisitTypeBadge } from "../visit-type";
import { CalmStatus, Paper, Pill, SageTile } from "../clinic/dash";
import { dateTime } from "@/lib/format";

export type StaffTimeline = {
  patient: { id: string; patientCode: string; firstName: string; lastName: string; dateOfBirth?: string; sex?: string; phone?: string; bloodType?: string };
  appointments: { id: string; startAt: string; status: string; doctorName: string; serviceName: string; visitType?: string | null }[];
  visits: {
    id: string; visitAt: string; doctorName: string; visitType?: string | null; chiefComplaint?: string; clinicalSummary?: string; followUpDate?: string;
    vitals?: { temperatureC?: number; heartRateBpm?: number; systolicBp?: number; diastolicBp?: number; weightKg?: number; oxygenSaturation?: number }[];
    conditions?: { name: string; status: string }[];
    notes: { id: string; noteType: string; content: string; isPatientVisible?: boolean; createdAt: string }[];
  }[];
  prescriptions: { id: string; status: string; imageFileId?: string | null; issuedAt?: string; doctorName: string; items: { medicationName: string; strength?: string; dosage?: string; frequency?: string; duration?: string }[] }[];
  labOrders: { id: string; testName: string; kind?: string; status: string; priority: string; orderedAt: string; results: { id: string; resultText?: string; fileId?: string; uploadedByPatient: boolean }[] }[];
  documents: { id: string; title: string; documentType: string; fileId?: string; patientVisible?: boolean }[];
};

type Part = "visits" | "prescriptions" | "labs" | "documents";

/** Chronological chart of one patient (staff view), in four parts: visits, prescriptions, labs, documents. Internal items are visibly marked as internal. */
export function TimelineView({ tl }: { tl: StaffTimeline }) {
  const { t, locale, timezone } = useI18n();
  const [part, setPart] = useState<Part>("visits");
  const dt = (s?: string) => dateTime(s, locale, timezone);
  const tabs: { key: Part; label: string; count: number }[] = [
    { key: "visits", label: t("portal.visits"), count: tl.visits.length },
    { key: "prescriptions", label: t("portal.prescriptions"), count: tl.prescriptions.length },
    { key: "labs", label: t("cld.labsShort"), count: tl.labOrders.length },
    ...(tl.documents.length > 0 ? [{ key: "documents" as Part, label: t("portal.documents"), count: tl.documents.length }] : []),
  ];
  return (
    <div className="space-y-4">
      <Tabs tabs={tabs} value={part} onChange={setPart} />

      {part === "visits" && (tl.visits.length === 0 ? <Empty icon="stethoscope" compact>{t("portal.none")}</Empty> : (
        <ol className="relative space-y-3 ps-6 before:absolute before:inset-y-2 before:start-[7px] before:w-px before:bg-line">
          {tl.visits.map((v) => (
            <li key={v.id} className="relative">
              <span className="absolute -start-6 top-5 h-[15px] w-[15px] rounded-full bg-surface ring-2 ring-[var(--rail-accent)]" aria-hidden />
              <Paper className="space-y-3 p-4 text-sm sm:p-5">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <p className="font-display text-[15px] font-semibold text-ink">{dt(v.visitAt)}</p>
                  <span className="flex flex-wrap items-center gap-2 text-[13px] text-muted">{v.doctorName} <VisitTypeBadge type={v.visitType} /></span>
                </div>
                {v.chiefComplaint && <p><span className="text-muted">{t("consult.complaint")}:</span> <span className="text-ink">{v.chiefComplaint}</span></p>}
                {v.vitals && v.vitals.length > 0 && (
                  <div className="flex flex-wrap gap-1.5">
                    {v.vitals.flatMap((x) => [x.temperatureC && `${x.temperatureC}°C`, x.heartRateBpm && `${x.heartRateBpm} bpm`, x.systolicBp && `${x.systolicBp}/${x.diastolicBp}`, x.weightKg && `${x.weightKg} kg`, x.oxygenSaturation && `SpO₂ ${x.oxygenSaturation}%`].filter(Boolean) as string[]).map((s, k) => <Pill key={k} tone="sage"><span dir="ltr">{s}</span></Pill>)}
                  </div>
                )}
                {v.conditions && v.conditions.length > 0 && (
                  <div className="flex flex-wrap items-center gap-1.5"><span className="text-muted">{t("consult.conditions")}:</span>{v.conditions.map((c, k) => <Pill key={k} tone="sky">{c.name}</Pill>)}</div>
                )}
                {v.clinicalSummary && <p className="whitespace-pre-wrap rounded-[12px] bg-surface-2 p-3 leading-relaxed text-ink-2 ring-1 ring-inset ring-line">{v.clinicalSummary}</p>}
                {v.notes.map((n) => (
                  <p key={n.id} className="rounded-[12px] bg-surface-2 p-3 text-ink-2 ring-1 ring-inset ring-line">{n.content} {n.isPatientVisible ? <Pill tone="sage" className="ms-1">{t("consult.visibleToPatient")}</Pill> : <Pill className="ms-1">{t("consult.internal")}</Pill>}</p>
                ))}
                {v.followUpDate && <p className="flex items-center gap-1.5 text-xs text-muted"><Icon name="calendar" className="h-3.5 w-3.5" />{t("consult.followUp")}: <span dir="ltr" className="tnum">{v.followUpDate}</span></p>}
              </Paper>
            </li>
          ))}
        </ol>
      ))}

      {part === "prescriptions" && (tl.prescriptions.length === 0 ? <Empty icon="file" compact>{t("portal.none")}</Empty> : (
        <div className="grid gap-3 lg:grid-cols-2">
          {tl.prescriptions.map((p) => (
            <Paper key={p.id} className="space-y-3 p-4 text-sm sm:p-5">
              <div className="flex flex-wrap items-center gap-2">
                <CalmStatus status={p.status} />
                <span className="text-[13px] text-muted">{dt(p.issuedAt)} · {p.doctorName}</span>
                <span className="ms-auto flex items-center gap-1">
                  <a className="inline-flex h-8 items-center rounded-[9px] px-2.5 text-[13px] font-semibold text-[var(--rail-accent-ink)] hover:bg-surface-2" href={`/api/bff/clinic/prescriptions/${p.id}/pdf`}>{t("rx.pdf")}</a>
                  {p.status !== "DRAFT" && <PrintRxButton prescriptionId={p.id} />}
                </span>
              </div>
              <ul className="divide-y divide-line">{p.items.map((i, k) => <li key={k} className="py-2"><b className="text-ink">{i.medicationName}</b> <span className="text-muted">{[i.strength, i.dosage, i.frequency, i.duration].filter(Boolean).join(" · ")}</span></li>)}</ul>
              {p.imageFileId && <RxPhoto fileId={p.imageFileId} />}
            </Paper>
          ))}
        </div>
      ))}

      {part === "labs" && (tl.labOrders.length === 0 ? <Empty icon="flask" compact>{t("portal.none")}</Empty> : (
        <div className="grid gap-3 lg:grid-cols-2">
          {tl.labOrders.map((l) => (
            <Paper key={l.id} className="space-y-2.5 p-4 text-sm sm:p-5">
              <div className="flex items-center justify-between gap-3">
                <span className="flex min-w-0 items-center gap-2.5"><SageTile icon={l.kind === "RADIOLOGY" ? "pulse" : "flask"} className="h-8 w-8" /><b className="truncate text-ink">{l.testName}</b></span>
                <CalmStatus status={l.status === "ORDERED" ? "REQUESTED" : l.status} />
              </div>
              <p className="text-xs text-muted">{dt(l.orderedAt)}</p>
              {l.results.map((r) => (
                <p key={r.id} className="rounded-[12px] bg-surface-2 p-3 ring-1 ring-inset ring-line">
                  {r.resultText} {r.fileId && <a className="font-semibold text-[var(--rail-accent-ink)] underline" href={`/api/bff/files/${r.fileId}/content`} target="_blank" rel="noreferrer">{t("portal.openFile")}</a>} {r.uploadedByPatient && <Pill tone="sky" className="ms-1">{t("consult.byPatient")}</Pill>}
                </p>
              ))}
            </Paper>
          ))}
        </div>
      ))}

      {part === "documents" && (
        <Paper className="px-2 py-2">
          <ul className="divide-y divide-line text-sm">{tl.documents.map((d) => (
            <li key={d.id} className="flex items-center justify-between gap-3 px-3 py-3">
              <span className="flex min-w-0 items-center gap-3"><SageTile icon="file" className="h-8 w-8" /><span className="truncate font-medium text-ink">{d.title}</span></span>
              {d.fileId && <a className="shrink-0 text-[13px] font-semibold text-[var(--rail-accent-ink)] underline" href={`/api/bff/files/${d.fileId}/content`} target="_blank" rel="noreferrer">{t("portal.openFile")}</a>}
            </li>
          ))}</ul>
        </Paper>
      )}
    </div>
  );
}
