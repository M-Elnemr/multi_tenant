"use client";

import { use, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/client";
import { TimelineView, type StaffTimeline } from "@/components/dashboard/timeline-view";
import { PrintRxButton, RxPhoto } from "@/components/print-rx";
import { uploadFile } from "@/components/uploader";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { ConfirmDialog, Checkbox } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { LabResults, type LabOrder } from "@/components/dashboard/lab-result";
import { Alert, Button, ErrorText, Field, Input, Loading, PageHeader, Select, Tabs, Textarea } from "@/components/ui";
import { VisitTypeBadge } from "@/components/visit-type";
import { CalmStatus, Paper, PaperHead, Pill, SageTile } from "@/components/clinic/dash";
import { ageText, dateTime } from "@/lib/format";

type Encounter = {
  id: string; patientId: string; appointmentId?: string; visitAt: string; chiefComplaint?: string; clinicalSummary?: string; followUpDate?: string;
  vitals: unknown[]; notes: { id: string; content: string; noteType: string; isPatientVisible: boolean }[]; conditions: { id: string; name: string }[];
  prescriptions: { id: string; status: string; imageFileId?: string | null; items: { medicationName: string; dosage?: string; frequency?: string }[] }[];
  labOrders: LabOrder[];
};
type Item = { medicationName: string; strength: string; dosage: string; frequency: string; duration: string; instructions: string };
const EMPTY_ITEM: Item = { medicationName: "", strength: "", dosage: "", frequency: "", duration: "", instructions: "" };
const EMPTY_VITALS = { temperatureC: "", heartRateBpm: "", systolicBp: "", diastolicBp: "", weightKg: "", oxygenSaturation: "" };

/** The consultation screen: patient history on one side, the current visit on the other. Drafts survive a connection drop (kept locally until saved). */
export default function Consultation({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const enc = useApi<Encounter>(`clinic/encounters/${id}`);
  if (enc.loading && !enc.data) return <Loading variant="detail" />;
  if (!enc.data) return <ErrorText error={enc.error} />;
  // key = encounter id: the form below initialises its fields (and recovers local drafts) exactly once per encounter.
  return <ConsultationForm key={enc.data.id} id={id} e={enc.data} reload={enc.reload} />;
}

function readDraft(key: string): { summary?: string; complaint?: string; note?: string } | null {
  try { return JSON.parse(localStorage.getItem(key) ?? "null"); } catch { return null; }
}

function ConsultationForm({ id, e, reload }: { id: string; e: Encounter; reload: () => Promise<void> }) {
  const { t, locale, timezone } = useI18n();
  const router = useRouter();
  const toast = useToast();
  const { can, canWrite } = useMe();
  const patientId = e.patientId;
  const patient = useApi<{ firstName: string; lastName: string; patientCode: string; ageYears?: number; ageMonths?: number; sex?: string }>(patientId ? `clinic/patients/${patientId}` : null);
  const history = useApi<StaffTimeline>(patientId ? `clinic/patients/${patientId}/timeline` : null);
  const [tab, setTab] = useState<"visit" | "history">("visit");
  const [askComplete, setAskComplete] = useState(false);
  const [askCancelRx, setAskCancelRx] = useState<string | null>(null);

  const draftKey = `consult-draft:${id}`;
  const [draft] = useState(() => (typeof window === "undefined" ? null : readDraft(draftKey)));
  const [summary, setSummary] = useState(draft?.summary ?? e.clinicalSummary ?? "");
  const [complaint, setComplaint] = useState(draft?.complaint ?? e.chiefComplaint ?? "");
  const [followUp, setFollowUp] = useState(e.followUpDate ?? "");
  const [note, setNote] = useState(draft?.note ?? "");
  const [shared, setShared] = useState(false);
  const [vit, setVit] = useState(EMPTY_VITALS);
  const [condition, setCondition] = useState("");
  const [items, setItems] = useState<Item[]>([{ ...EMPTY_ITEM }]);
  const [lab, setLab] = useState({ testName: "", priority: "ROUTINE", kind: "LAB" });
  const [recovered, setRecovered] = useState(Boolean(draft && (draft.summary || draft.complaint || draft.note)));

  useEffect(() => {
    try { localStorage.setItem(draftKey, JSON.stringify({ summary, complaint, note })); } catch { /* storage unavailable */ }
  }, [summary, complaint, note, draftKey]);

  const refresh = async () => { await Promise.all([reload(), history.reload()]); };
  const num = (s: string) => (s.trim() === "" ? undefined : Number(s));
  const saveVisit = useAction(async () => {
    await api(`clinic/encounters/${id}`, { method: "PATCH", body: { chiefComplaint: complaint, clinicalSummary: summary, followUpDate: followUp || undefined } });
    localStorage.removeItem(draftKey);
    setRecovered(false);
    await refresh();
    toast(t("common.saved"));
  });
  const saveVitals = useAction(async () => {
    await api(`clinic/encounters/${id}/vitals`, { body: { temperatureC: num(vit.temperatureC), heartRateBpm: num(vit.heartRateBpm), systolicBp: num(vit.systolicBp), diastolicBp: num(vit.diastolicBp), weightKg: num(vit.weightKg), oxygenSaturation: num(vit.oxygenSaturation) } });
    setVit(EMPTY_VITALS);
    await refresh();
    toast(t("common.saved"));
  });
  const addCondition = useAction(async () => { await api(`clinic/encounters/${id}/conditions`, { body: { name: condition } }); setCondition(""); await refresh(); });
  const addNote = useAction(async () => { await api(`clinic/encounters/${id}/notes`, { body: { content: note, patientVisible: shared } }); setNote(""); setShared(false); await refresh(); toast(t("common.saved")); });
  const [photo, setPhoto] = useState<string | null>(null);
  const [photoBusy, setPhotoBusy] = useState(false);
  const [photoError, setPhotoError] = useState<unknown>(null);
  const photoInput = useRef<HTMLInputElement>(null);
  const prescribe = useAction(async (issue: boolean) => {
    await api(`clinic/encounters/${id}/prescriptions`, { body: { items: items.filter((i) => i.medicationName.trim()), imageFileId: photo ?? undefined, issue } });
    setItems([{ ...EMPTY_ITEM }]);
    setPhoto(null);
    await refresh();
    toast(t("common.saved"));
  });
  const issue = useAction(async (pid: string) => { await api(`clinic/prescriptions/${pid}/issue`, { body: {} }); await refresh(); });
  const cancelRx = useAction(async (pid: string) => { await api(`clinic/prescriptions/${pid}/cancel`, { body: {} }); setAskCancelRx(null); await refresh(); });
  const orderLab = useAction(async () => { await api(`clinic/encounters/${id}/lab-orders`, { body: lab }); setLab({ testName: "", priority: "ROUTINE", kind: "LAB" }); await refresh(); toast(t("common.saved")); });
  // Finishing the exam saves it, closes the appointment, and goes back to "Current exam", which then shows nobody in the room until the next patient is sent in.
  // Any failure stops here (the error shows on this page) instead of leaving the doctor on a half-finished exam.
  const complete = useAction(async () => {
    await api(`clinic/encounters/${id}`, { method: "PATCH", body: { chiefComplaint: complaint, clinicalSummary: summary, followUpDate: followUp || undefined } });
    localStorage.removeItem(draftKey);
    if (e.appointmentId) await api(`clinic/appointments/${e.appointmentId}/complete`, { body: {} });
    router.push("/dashboard/current");
  });

  const age = patient.data?.ageYears !== undefined ? ageText(patient.data.ageYears, patient.data.ageMonths, locale) : null;
  const err = saveVisit.error ?? saveVitals.error ?? addCondition.error ?? addNote.error ?? prescribe.error ?? issue.error ?? cancelRx.error ?? orderLab.error ?? complete.error;
  const setItem = (i: number, patch: Partial<Item>) => setItems(items.map((y, k) => (k === i ? { ...y, ...patch } : y)));
  const canRx = items.some((i) => i.medicationName.trim());

  return (
    <>
      <PageHeader
        eyebrow={t("nav.current")}
        title={patient.data ? `${patient.data.firstName} ${patient.data.lastName}` : "…"}
        subtitle={`${patient.data?.patientCode ?? ""}${age !== null ? ` · ${age}` : ""} · ${dateTime(e.visitAt, locale, timezone)}`}
        badge={<VisitTypeBadge type={history.data?.visits.find((v) => v.id === e.id)?.visitType} />}
        actions={<>
          <Button variant="secondary" loading={saveVisit.loading} onClick={() => saveVisit.run()}>{t("consult.saveDraft")}</Button>
          {e.appointmentId && <Button loading={complete.loading} onClick={() => setAskComplete(true)}><Icon name="check" className="h-4 w-4" />{t("consult.complete")}</Button>}
        </>}
      />
      <div className="space-y-4">
        {recovered && <Alert tone="amber">{t("consult.recovered")}</Alert>}
        <ErrorText error={err} />
        <Tabs tabs={[{ key: "visit", label: t("consult.tab.visit") }, { key: "history", label: t("consult.tab.history") }]} value={tab} onChange={setTab} />

        {tab === "history" ? (
          history.loading && !history.data ? <Loading variant="list" /> : history.data && <TimelineView tl={history.data} />
        ) : (
          <fieldset disabled={!canWrite("exam")} className="m-0 grid min-w-0 items-start gap-5 border-0 p-0 lg:grid-cols-2">
            <div className="space-y-5">
              <Paper>
                <PaperHead icon="stethoscope" title={t("consult.summary")} />
                <div className="space-y-4 px-5 pb-5">
                  <Field label={t("consult.complaint")}><Textarea rows={2} value={complaint} onChange={(x) => setComplaint(x.target.value)} /></Field>
                  <Field label={t("consult.summary")}><Textarea rows={6} value={summary} onChange={(x) => setSummary(x.target.value)} /></Field>
                  <Field label={t("consult.followUp")}><Input type="date" value={followUp} onChange={(x) => setFollowUp(x.target.value)} dir="ltr" className="max-w-48" /></Field>
                </div>
              </Paper>
              <Paper>
                <PaperHead icon="pulse" title={t("consult.vitals")} subtitle={`${e.vitals.length} ${t("consult.recorded")}`} />
                <div className="space-y-3 px-5 pb-5">
                  <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
                    {([["temperatureC", "°C"], ["heartRateBpm", "bpm"], ["oxygenSaturation", "SpO₂ %"], ["systolicBp", "SYS"], ["diastolicBp", "DIA"], ["weightKg", "kg"]] as const).map(([k, label]) => (
                      <Field key={k} label={label}><Input value={vit[k]} onChange={(x) => setVit({ ...vit, [k]: x.target.value })} inputMode="decimal" dir="ltr" /></Field>
                    ))}
                  </div>
                  <Button size="sm" variant="secondary" loading={saveVitals.loading} onClick={() => saveVitals.run()}>{t("consult.recordVitals")}</Button>
                </div>
              </Paper>
              <Paper>
                <PaperHead icon="heart" title={t("consult.conditions")} />
                <div className="space-y-3 px-5 pb-5">
                  {e.conditions.length > 0 && <div className="flex flex-wrap gap-1.5">{e.conditions.map((c) => <Pill key={c.id} tone="sky">{c.name}</Pill>)}</div>}
                  <div className="flex gap-2"><Input value={condition} onChange={(x) => setCondition(x.target.value)} placeholder={t("consult.diagnosis")} /><Button size="sm" className="self-center" onClick={() => addCondition.run()} disabled={!condition.trim()}>{t("common.add")}</Button></div>
                </div>
              </Paper>
              <Paper>
                <PaperHead icon="file" title={t("consult.notes")} />
                <div className="space-y-3 px-5 pb-5">
                  {e.notes.map((n) => <p key={n.id} className="rounded-[12px] bg-surface-2 p-3 text-sm text-ink-2 ring-1 ring-inset ring-line">{n.content} {n.isPatientVisible ? <Pill tone="sage" className="ms-1">{t("consult.visibleToPatient")}</Pill> : <Pill className="ms-1">{t("consult.internal")}</Pill>}</p>)}
                  <Textarea rows={3} value={note} onChange={(x) => setNote(x.target.value)} />
                  <div className="flex items-center gap-2 text-sm text-ink-2"><Checkbox checked={shared} onChange={setShared} label={t("consult.shareWithPatient")} /><span>{t("consult.shareWithPatient")}</span></div>
                  <Button size="sm" loading={addNote.loading} disabled={!note.trim()} onClick={() => addNote.run()}>{t("consult.addNote")}</Button>
                </div>
              </Paper>
            </div>
            <div className="space-y-5">
              {(can("prescription.create") || can("prescription.read")) && (
                <Paper>
                  <PaperHead icon="receipt" title={t("portal.prescriptions")} />
                  <div className="space-y-3 px-5 pb-5">
                    {e.prescriptions.map((p) => (
                      <div key={p.id} className="rounded-[14px] bg-surface-2 p-3.5 text-sm ring-1 ring-inset ring-line">
                        <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                          <CalmStatus status={p.status} />
                          <div className="flex flex-wrap items-center gap-1">
                            <a className="inline-flex h-8 items-center rounded-[9px] px-2.5 text-[13px] font-semibold text-[var(--rail-accent-ink)] hover:bg-surface" href={`/api/bff/clinic/prescriptions/${p.id}/pdf`}>{t("rx.pdf")}</a>
                            <PrintRxButton prescriptionId={p.id} />
                            {p.status === "DRAFT" && <Button size="sm" onClick={() => issue.run(p.id)}>{t("consult.issue")}</Button>}
                            {p.status !== "CANCELLED" && can("prescription.delete") && <Button size="sm" variant="ghost" onClick={() => setAskCancelRx(p.id)}>{t("orders.cancel")}</Button>}
                          </div>
                        </div>
                        <ul className="divide-y divide-line">{p.items.map((i, k) => <li key={k} className="py-1.5"><b className="text-ink">{i.medicationName}</b> <span className="text-muted">{i.dosage} {i.frequency}</span></li>)}</ul>
                        {p.imageFileId && <RxPhoto fileId={p.imageFileId} />}
                      </div>
                    ))}
                    {items.map((it, i) => (
                      <div key={i} className="grid grid-cols-2 gap-2 rounded-[14px] border border-dashed border-line-strong p-3">
                        <div className="col-span-2"><Input placeholder={t("consult.medication")} value={it.medicationName} onChange={(x) => setItem(i, { medicationName: x.target.value })} /></div>
                        <Input placeholder={t("consult.strength")} value={it.strength} onChange={(x) => setItem(i, { strength: x.target.value })} />
                        <Input placeholder={t("consult.dosage")} value={it.dosage} onChange={(x) => setItem(i, { dosage: x.target.value })} />
                        <Input placeholder={t("consult.frequency")} value={it.frequency} onChange={(x) => setItem(i, { frequency: x.target.value })} />
                        <Input placeholder={t("consult.duration")} value={it.duration} onChange={(x) => setItem(i, { duration: x.target.value })} />
                      </div>
                    ))}
                    <div className="space-y-2 rounded-[14px] border border-dashed border-line-strong p-3">
                      <input ref={photoInput} type="file" accept="image/png,image/jpeg" capture="environment" className="hidden" onChange={async (x) => {
                        const file = x.target.files?.[0];
                        if (!file) return;
                        setPhotoBusy(true); setPhotoError(null);
                        try { setPhoto(await uploadFile(file, "PRESCRIPTION")); } catch (err) { setPhotoError(err); } finally { setPhotoBusy(false); if (photoInput.current) photoInput.current.value = ""; }
                      }} />
                      <p className="text-xs text-muted">{t("rx.photoHint")}</p>
                      <div className="flex flex-wrap items-center gap-2">
                        <Button type="button" size="sm" variant="secondary" disabled={photoBusy} onClick={() => photoInput.current?.click()}><Icon name="camera" className="h-4 w-4" />{photoBusy ? t("rx.uploading") : photo ? t("rx.photo") : t("rx.addPhoto")}{photo && !photoBusy && <Icon name="check" className="h-3.5 w-3.5" />}</Button>
                        {photo && <Button type="button" size="sm" variant="ghost" onClick={() => setPhoto(null)}>{t("rx.removePhoto")}</Button>}
                      </div>
                      {photo && <RxPhoto fileId={photo} />}
                      <ErrorText error={photoError} />
                    </div>
                    <div className="flex flex-wrap gap-2">
                      <Button size="sm" variant="secondary" onClick={() => setItems([...items, { ...EMPTY_ITEM }])}><Icon name="plus" className="h-3.5 w-3.5" />{t("consult.addItem")}</Button>
                      <Button size="sm" variant="secondary" loading={prescribe.loading} disabled={!(photo || canRx)} onClick={() => prescribe.run(false)}>{t("consult.saveDraft")}</Button>
                      <Button size="sm" loading={prescribe.loading} disabled={!(photo || canRx)} onClick={() => prescribe.run(true)}>{t("consult.issuePrescription")}</Button>
                    </div>
                  </div>
                </Paper>
              )}
              {(can("lab_order.create") || can("lab_order.read")) && (
                <Paper>
                  <PaperHead icon="flask" title={t("portal.labs")} />
                  <div className="space-y-3 px-5 pb-5">
                    <ul className="space-y-3 text-sm">{e.labOrders.map((l) => (
                      <li key={l.id} className="space-y-2">
                        <div className="flex items-center justify-between gap-3">
                          <span className="flex min-w-0 items-center gap-2.5"><SageTile icon={l.kind === "RADIOLOGY" ? "pulse" : "flask"} className="h-8 w-8" /><span className="truncate font-semibold text-ink">{l.testName}</span>{l.priority === "URGENT" && <Pill tone="amber">{t("consult.urgent")}</Pill>}</span>
                          <CalmStatus status={l.status === "ORDERED" ? "REQUESTED" : l.status} />
                        </div>
                        <LabResults lab={l} canReview={can("lab_result.review")} onChanged={() => void refresh()} />
                      </li>
                    ))}</ul>
                    <div className="grid grid-cols-2 gap-2 sm:grid-cols-[1fr_auto_auto_auto]">
                      <Input className="col-span-2 sm:col-span-1" placeholder={t("consult.testName")} value={lab.testName} onChange={(x) => setLab({ ...lab, testName: x.target.value })} />
                      <Select value={lab.kind} onChange={(x) => setLab({ ...lab, kind: x.target.value })} aria-label={t("lab.kind")}><option value="LAB">{t("lab.lab")}</option><option value="RADIOLOGY">{t("lab.radiology")}</option></Select>
                      <Select value={lab.priority} onChange={(x) => setLab({ ...lab, priority: x.target.value })}><option value="ROUTINE">{t("consult.routine")}</option><option value="URGENT">{t("consult.urgent")}</option></Select>
                      <Button size="sm" className="self-center" loading={orderLab.loading} disabled={!lab.testName.trim()} onClick={() => orderLab.run()}>{t("consult.order")}</Button>
                    </div>
                  </div>
                </Paper>
              )}
            </div>
          </fieldset>
        )}
      </div>

      <ConfirmDialog open={askComplete} onClose={() => setAskComplete(false)} loading={complete.loading}
        title={t("consult.complete")} body={t("cld.completeBody")} confirmLabel={t("consult.complete")} cancelLabel={t("common.cancel")}
        onConfirm={() => { void complete.run().then(() => setAskComplete(false)); }} />
      <ConfirmDialog open={!!askCancelRx} onClose={() => setAskCancelRx(null)} danger loading={cancelRx.loading}
        title={t("orders.cancel")} body={t("cld.cancelRxBody")} confirmLabel={t("orders.cancel")} cancelLabel={t("common.cancel")}
        onConfirm={() => { if (askCancelRx) void cancelRx.run(askCancelRx); }} />
    </>
  );
}
