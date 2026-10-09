"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { uploadFile } from "@/components/uploader";
import { Button, Card, Empty, ErrorText, Loading, PageHeader, Select, StatusBadge, Textarea } from "@/components/ui";
import { dateTime } from "@/lib/format";
import { useRef } from "react";

type PatientRef = { id: string; firstName: string; lastName: string; patientCode: string };
type Timeline = {
  visits: { id: string; visitAt: string; doctorName: string; visitType?: string | null; notes: { id: string; noteType: string; content: string; createdAt: string }[] }[];
  prescriptions: { id: string; issuedAt: string; doctorName: string; notes?: string; items: { medicationName: string; strength?: string; dosage?: string; frequency?: string; duration?: string; instructions?: string }[] }[];
  labOrders: { id: string; testName: string; instructions?: string; status: string; orderedAt: string; results: { id: string; resultText?: string; resultSummary?: string; fileId?: string; uploadedAt: string }[] }[];
  documents: { id: string; title: string; documentType: string; fileId?: string; createdAt: string }[];
};

function LabUpload({ labId, onDone }: { labId: string; onDone: () => void }) {
  const t = useI18n().t;
  const [text, setText] = useState("");
  const ref = useRef<HTMLInputElement>(null);
  const [fileId, setFileId] = useState<string | null>(null);
  const send = useAction(async () => { await api(`portal/lab-orders/${labId}/results`, { body: { resultText: text || undefined, fileId: fileId ?? undefined } }); setText(""); setFileId(null); onDone(); });
  const pick = useAction(async (f: File) => { setFileId(await uploadFile(f, "LAB_RESULT")); });
  return (
    <div className="mt-3 space-y-2 rounded-lg bg-slate-50 p-3">
      <p className="text-xs font-medium">{t("portal.uploadResult")}</p>
      <Textarea rows={2} value={text} onChange={(e) => setText(e.target.value)} placeholder={t("portal.resultText")} />
      <input ref={ref} type="file" accept="application/pdf,image/png,image/jpeg" className="hidden" onChange={(e) => { const f = e.target.files?.[0]; if (f) void pick.run(f); }} />
      <div className="flex flex-wrap items-center gap-2"><Button type="button" size="sm" variant="secondary" loading={pick.loading} onClick={() => ref.current?.click()}>📎 {fileId ? t("portal.fileAttached") : t("portal.attachFile")}</Button><Button size="sm" loading={send.loading} disabled={!text && !fileId} onClick={() => send.run()}>{t("portal.send")}</Button></div>
      <ErrorText error={send.error ?? pick.error} />
    </div>
  );
}

export default function Record() {
  const { t, locale, timezone } = useI18n();
  const patients = useApi<PatientRef[]>("portal/patients");
  const [chosen, setPid] = useState("");
  const pid = chosen || patients.data?.[0]?.id || "";
  const tl = useApi<Timeline>(pid ? `portal/patients/${pid}/timeline` : null);
  if (patients.loading) return <Loading />;
  return (
    <>
      <PageHeader title={t("portal.record")} subtitle={t("portal.recordHint")} actions={(patients.data?.length ?? 0) > 1 ? <Select value={pid} onChange={(e) => setPid(e.target.value)}>{patients.data?.map((p) => <option key={p.id} value={p.id}>{p.firstName} {p.lastName}</option>)}</Select> : undefined} />
      <ErrorText error={tl.error} />
      {tl.loading && !tl.data ? <Loading /> : tl.data && (
        <div className="space-y-6">
          <section>
            <h2 className="mb-2 flex items-center justify-between font-medium">{t("portal.prescriptions")}{pid && <a className="text-sm font-normal text-brand-700 underline" href={`/api/bff/portal/patients/${pid}/export`}>{t("record.export")}</a>}</h2>
            {tl.data.prescriptions.length === 0 ? <Empty>{t("portal.none")}</Empty> : tl.data.prescriptions.map((p) => (
              <Card key={p.id} className="mb-3">
                <p className="flex items-center justify-between text-sm text-slate-500"><span>{dateTime(p.issuedAt, locale, timezone)} · {p.doctorName}</span><a className="text-brand-700 underline" href={`/api/bff/portal/prescriptions/${p.id}/pdf`}>{t("rx.pdf")}</a></p>
                <ul className="mt-2 space-y-1 text-sm">{p.items.map((i, k) => <li key={k}><b>{i.medicationName}</b> {i.strength} {i.dosage} {i.frequency} {i.duration && `· ${i.duration}`}{i.instructions && <span className="text-slate-500"> — {i.instructions}</span>}</li>)}</ul>
              </Card>
            ))}
          </section>
          <section>
            <h2 className="mb-2 font-medium">{t("portal.labs")}</h2>
            {tl.data.labOrders.length === 0 ? <Empty>{t("portal.none")}</Empty> : tl.data.labOrders.map((l) => (
              <Card key={l.id} className="mb-3 text-sm">
                <div className="flex items-center justify-between"><p className="font-medium">{l.testName}</p><StatusBadge status={l.status} /></div>
                {l.instructions && <p className="text-slate-500">{l.instructions}</p>}
                {l.results.map((r) => <div key={r.id} className="mt-2 rounded-lg bg-slate-50 p-2">{r.resultSummary ?? r.resultText}{r.fileId && <> <a className="text-brand underline" href={`/api/bff/files/${r.fileId}/content`} target="_blank" rel="noreferrer">{t("portal.openFile")}</a></>}</div>)}
                {["ORDERED", "PATIENT_UPLOADED", "UNDER_REVIEW"].includes(l.status) && <LabUpload labId={l.id} onDone={tl.reload} />}
              </Card>
            ))}
          </section>
          <section>
            <h2 className="mb-2 font-medium">{t("portal.visits")}</h2>
            {tl.data.visits.length === 0 ? <Empty>{t("portal.none")}</Empty> : tl.data.visits.map((v) => (
              <Card key={v.id} className="mb-3 text-sm"><p className="font-medium">{dateTime(v.visitAt, locale, timezone)} · {v.doctorName}{v.visitType && ` · ${t(v.visitType === "FOLLOW_UP" ? "visit.followUp" : "visit.consultation")}`}</p>{v.notes.map((n) => <p key={n.id} className="mt-2 rounded-lg bg-slate-50 p-2">{n.content}</p>)}</Card>
            ))}
          </section>
          {tl.data.documents.length > 0 && (
            <section><h2 className="mb-2 font-medium">{t("portal.documents")}</h2>
              <ul className="space-y-2 text-sm">{tl.data.documents.map((d) => <li key={d.id} className="flex justify-between rounded-lg border bg-white p-3"><span>{d.title}</span>{d.fileId && <a className="text-brand underline" href={`/api/bff/files/${d.fileId}/content`} target="_blank" rel="noreferrer">{t("portal.openFile")}</a>}</li>)}</ul>
            </section>
          )}
        </div>
      )}
    </>
  );
}
