"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { dateTime } from "@/lib/format";
import { useAction } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Badge, Button, ErrorText } from "../ui";

export type LabResult = { id: string; fileId?: string | null; resultText?: string | null; uploadedByPatient?: boolean; uploadedAt?: string; reviewedAt?: string | null; patientVisible?: boolean };
export type LabOrder = { id: string; testName: string; kind?: string; status: string; priority: string; instructions?: string; results?: LabResult[] };

/** What the patient (or the clinic) uploaded for one requested test, with the review buttons. The picture is loaded with the doctor's own login. */
export function LabResults({ lab, canReview, onChanged }: { lab: LabOrder; canReview: boolean; onChanged: () => void }) {
  const { t, locale, timezone } = useI18n();
  const [zoom, setZoom] = useState<string | null>(null);
  const review = useAction(async (share: boolean) => { await api(`clinic/lab-orders/${lab.id}/review`, { body: { shareWithPatient: share } }); onChanged(); });
  const results = lab.results ?? [];
  if (results.length === 0) return <p className="ps-7 text-xs text-slate-500">{t("labs.none")}</p>;
  return (
    <div className="ms-7 space-y-3 rounded-2xl border border-slate-200 bg-slate-50/70 p-3">
      {results.map((r) => (
        <div key={r.id} className="space-y-2">
          <p className="flex flex-wrap items-center gap-2 text-xs text-slate-500">
            <Badge tone={r.uploadedByPatient ? "blue" : "slate"}>{r.uploadedByPatient ? t("labs.byPatient") : t("labs.byClinic")}</Badge>
            {r.uploadedAt && <span>{dateTime(r.uploadedAt, locale, timezone)}</span>}
          </p>
          {r.resultText && <p className="text-sm">{r.resultText}</p>}
          {r.fileId && (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={`/api/bff/files/${r.fileId}/content`} alt={lab.testName} onClick={() => setZoom(r.fileId ?? null)} className="max-h-56 cursor-zoom-in rounded-xl border bg-white object-contain" />
          )}
        </div>
      ))}
      {canReview && lab.status !== "REVIEWED" && (
        <div className="flex flex-wrap gap-2 pt-1">
          <Button size="sm" loading={review.loading} onClick={() => review.run(true)}>{t("labs.reviewShare")}</Button>
          <Button size="sm" variant="secondary" disabled={review.loading} onClick={() => review.run(false)}>{t("labs.reviewOnly")}</Button>
        </div>
      )}
      {lab.status === "REVIEWED" && <p className="text-sm font-semibold text-emerald-700">✓ {t("labs.reviewed")}</p>}
      <ErrorText error={review.error} />
      {zoom && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 p-4" onClick={() => setZoom(null)} role="dialog">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={`/api/bff/files/${zoom}/content`} alt="" className="max-h-full max-w-full rounded-xl" />
        </div>
      )}
    </div>
  );
}
