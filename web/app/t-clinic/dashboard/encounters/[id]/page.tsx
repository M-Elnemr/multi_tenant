"use client";

import { use, useEffect, useState } from "react";
import { api } from "@/lib/client";
import { TimelineView, type StaffTimeline } from "@/components/dashboard/timeline-view";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Badge, Button, Card, ErrorText, Field, Input, Loading, PageHeader, Select, StatusBadge, Textarea } from "@/components/ui";
import { dateTime } from "@/lib/format";

type Encounter = {
  id: string; patientId: string; appointmentId?: string; visitAt: string; chiefComplaint?: string; clinicalSummary?: string; followUpDate?: string;
  vitals: unknown[]; notes: { id: string; content: string; noteType: string; isPatientVisible: boolean }[]; conditions: { id: string; name: string }[];
  prescriptions: { id: string; status: string; items: { medicationName: string; dosage?: string; frequency?: string }[] }[];
  labOrders: { id: string; testName: string; status: string; priority: string }[];
};
type Item = { medicationName: string; strength: string; dosage: string; frequency: string; duration: string; instructions: string };
const EMPTY_ITEM: Item = { medicationName: "", strength: "", dosage: "", frequency: "", duration: "", instructions: "" };
const EMPTY_VITALS = { temperatureC: "", heartRateBpm: "", systolicBp: "", diastolicBp: "", weightKg: "", oxygenSaturation: "" };

/** The consultation screen: patient history on one side, the current visit on the other. Drafts survive a connection drop (kept locally until saved). */
export default function Consultation({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const enc = useApi<Encounter>(`clinic/encounters/${id}`);
  if (enc.loading && !enc.data) return <Loading />;
  if (!enc.data) return <ErrorText error={enc.error} />;
  // key = encounter id: the form below initialises its fields (and recovers local drafts) exactly once per encounter.
  return <ConsultationForm key={enc.data.id} id={id} e={enc.data} reload={enc.reload} />;
}

function readDraft(key: string): { summary?: string; complaint?: string; note?: string } | null {
  try { return JSON.parse(localStorage.getItem(key) ?? "null"); } catch { return null; }
}

function ConsultationForm({ id, e, reload }: { id: string; e: Encounter; reload: () => Promise<void> }) {
  const { t, locale, timezone } = useI18n();
  const { can } = useMe();
  const patientId = e.patientId;
  const patient = useApi<{ firstName: string; lastName: string; patientCode: string; dateOfBirth?: string; sex?: string }>(patientId ? `clinic/patients/${patientId}` : null);
  const history = useApi<StaffTimeline>(patientId ? `clinic/patients/${patientId}/timeline` : null);
  const [tab, setTab] = useState<"visit" | "history">("visit");

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
  const [lab, setLab] = useState({ testName: "", priority: "ROUTINE" });
  const [recovered, setRecovered] = useState(Boolean(draft && (draft.summary || draft.complaint || draft.note)));
  const [nowMs] = useState(() => Date.now());

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
  });
  const saveVitals = useAction(async () => {
    await api(`clinic/encounters/${id}/vitals`, { body: { temperatureC: num(vit.temperatureC), heartRateBpm: num(vit.heartRateBpm), systolicBp: num(vit.systolicBp), diastolicBp: num(vit.diastolicBp), weightKg: num(vit.weightKg), oxygenSaturation: num(vit.oxygenSaturation) } });
    setVit(EMPTY_VITALS);
    await refresh();
  });
  const addCondition = useAction(async () => { await api(`clinic/encounters/${id}/conditions`, { body: { name: condition } }); setCondition(""); await refresh(); });
  const addNote = useAction(async () => { await api(`clinic/encounters/${id}/notes`, { body: { content: note, patientVisible: shared } }); setNote(""); setShared(false); await refresh(); });
  const prescribe = useAction(async (issue: boolean) => {
    await api(`clinic/encounters/${id}/prescriptions`, { body: { items: items.filter((i) => i.medicationName.trim()), issue } });
    setItems([{ ...EMPTY_ITEM }]);
    await refresh();
  });
  const issue = useAction(async (pid: string) => { await api(`clinic/prescriptions/${pid}/issue`, { body: {} }); await refresh(); });
  const cancelRx = useAction(async (pid: string) => { await api(`clinic/prescriptions/${pid}/cancel`, { body: {} }); await refresh(); });
  const orderLab = useAction(async () => { await api(`clinic/encounters/${id}/lab-orders`, { body: lab }); setLab({ testName: "", priority: "ROUTINE" }); await refresh(); });
  const complete = useAction(async () => {
    await saveVisit.run();
    if (e.appointmentId) await api(`clinic/appointments/${e.appointmentId}/complete`, { body: {} });
    await refresh();
  });

  const age = patient.data?.dateOfBirth ? Math.floor((nowMs - new Date(patient.data.dateOfBirth).getTime()) / 31557600000) : null;
  const err = saveVisit.error ?? saveVitals.error ?? addCondition.error ?? addNote.error ?? prescribe.error ?? issue.error ?? cancelRx.error ?? orderLab.error ?? complete.error;
  const setItem = (i: number, patch: Partial<Item>) => setItems(items.map((y, k) => (k === i ? { ...y, ...patch } : y)));

  return (
    <>
      <PageHeader
        title={patient.data ? `${patient.data.firstName} ${patient.data.lastName}` : "…"}
        subtitle={`${patient.data?.patientCode ?? ""}${age !== null ? ` · ${age} ${t("consult.years")}` : ""} · ${dateTime(e.visitAt, locale, timezone)}`}
        actions={<>
          <Button variant="secondary" loading={saveVisit.loading} onClick={() => saveVisit.run()}>{t("consult.saveDraft")}</Button>
          {e.appointmentId && <Button loading={complete.loading} onClick={() => complete.run()}>{t("consult.complete")}</Button>}
        </>}
      />
      {recovered && <div className="mb-3"><Alert tone="amber">{t("consult.recovered")}</Alert></div>}
      <ErrorText error={err} />
      <div className="mb-4 flex gap-2 border-b text-sm">
        {(["visit", "history"] as const).map((k) => (
          <button key={k} onClick={() => setTab(k)} className={`border-b-2 px-4 py-2 ${tab === k ? "border-brand font-medium text-brand" : "border-transparent text-slate-500"}`}>{t(`consult.tab.${k}`)}</button>
        ))}
      </div>
      {tab === "history" ? (
        history.loading && !history.data ? <Loading /> : history.data && <TimelineView tl={history.data} />
      ) : (
        <div className="grid gap-5 lg:grid-cols-2">
          <div className="space-y-5">
            <Card className="space-y-3">
              <Field label={t("consult.complaint")}><Textarea rows={2} value={complaint} onChange={(x) => setComplaint(x.target.value)} /></Field>
              <Field label={t("consult.summary")}><Textarea rows={5} value={summary} onChange={(x) => setSummary(x.target.value)} /></Field>
              <Field label={t("consult.followUp")}><Input type="date" value={followUp} onChange={(x) => setFollowUp(x.target.value)} dir="ltr" className="max-w-48" /></Field>
            </Card>
            <Card className="space-y-3">
              <h3 className="font-medium">{t("consult.vitals")}</h3>
              <div className="grid grid-cols-3 gap-2">
                {([["temperatureC", "°C"], ["heartRateBpm", "bpm"], ["oxygenSaturation", "SpO₂ %"], ["systolicBp", "SYS"], ["diastolicBp", "DIA"], ["weightKg", "kg"]] as const).map(([k, label]) => (
                  <Field key={k} label={label}><Input value={vit[k]} onChange={(x) => setVit({ ...vit, [k]: x.target.value })} inputMode="decimal" dir="ltr" /></Field>
                ))}
              </div>
              <Button size="sm" variant="secondary" loading={saveVitals.loading} onClick={() => saveVitals.run()}>{t("consult.recordVitals")}</Button>
              <p className="text-xs text-slate-500">{e.vitals.length} {t("consult.recorded")}</p>
            </Card>
            <Card className="space-y-3">
              <h3 className="font-medium">{t("consult.conditions")}</h3>
              <div className="flex flex-wrap gap-1.5">{e.conditions.map((c) => <Badge key={c.id} tone="blue">{c.name}</Badge>)}</div>
              <div className="flex gap-2"><Input value={condition} onChange={(x) => setCondition(x.target.value)} placeholder={t("consult.diagnosis")} /><Button size="sm" onClick={() => addCondition.run()} disabled={!condition.trim()}>{t("common.add")}</Button></div>
            </Card>
            <Card className="space-y-3">
              <h3 className="font-medium">{t("consult.notes")}</h3>
              {e.notes.map((n) => <p key={n.id} className="rounded-lg bg-slate-50 p-2 text-sm">{n.content} {n.isPatientVisible ? <span className="text-xs text-emerald-700">· {t("consult.visibleToPatient")}</span> : <span className="text-xs text-slate-500">· {t("consult.internal")}</span>}</p>)}
              <Textarea rows={3} value={note} onChange={(x) => setNote(x.target.value)} />
              <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={shared} onChange={(x) => setShared(x.target.checked)} />{t("consult.shareWithPatient")}</label>
              <Button size="sm" loading={addNote.loading} disabled={!note.trim()} onClick={() => addNote.run()}>{t("consult.addNote")}</Button>
            </Card>
          </div>
          <div className="space-y-5">
            {can("prescription.create") && (
              <Card className="space-y-3">
                <h3 className="font-medium">{t("portal.prescriptions")}</h3>
                {e.prescriptions.map((p) => (
                  <div key={p.id} className="rounded-lg border p-3 text-sm">
                    <div className="mb-1 flex items-center justify-between">
                      <StatusBadge status={p.status} />
                      <div className="flex gap-1">
                        {p.status === "DRAFT" && <Button size="sm" onClick={() => issue.run(p.id)}>{t("consult.issue")}</Button>}
                        {p.status !== "CANCELLED" && can("prescription.delete") && <Button size="sm" variant="ghost" onClick={() => cancelRx.run(p.id)}>{t("orders.cancel")}</Button>}
                      </div>
                    </div>
                    <ul>{p.items.map((i, k) => <li key={k}><b>{i.medicationName}</b> {i.dosage} {i.frequency}</li>)}</ul>
                  </div>
                ))}
                {items.map((it, i) => (
                  <div key={i} className="grid grid-cols-2 gap-2 rounded-lg border border-dashed p-3">
                    <div className="col-span-2"><Input placeholder={t("consult.medication")} value={it.medicationName} onChange={(x) => setItem(i, { medicationName: x.target.value })} /></div>
                    <Input placeholder={t("consult.strength")} value={it.strength} onChange={(x) => setItem(i, { strength: x.target.value })} />
                    <Input placeholder={t("consult.dosage")} value={it.dosage} onChange={(x) => setItem(i, { dosage: x.target.value })} />
                    <Input placeholder={t("consult.frequency")} value={it.frequency} onChange={(x) => setItem(i, { frequency: x.target.value })} />
                    <Input placeholder={t("consult.duration")} value={it.duration} onChange={(x) => setItem(i, { duration: x.target.value })} />
                  </div>
                ))}
                <div className="flex flex-wrap gap-2">
                  <Button size="sm" variant="secondary" onClick={() => setItems([...items, { ...EMPTY_ITEM }])}>{t("consult.addItem")}</Button>
                  <Button size="sm" variant="secondary" loading={prescribe.loading} disabled={!items.some((i) => i.medicationName.trim())} onClick={() => prescribe.run(false)}>{t("consult.saveDraft")}</Button>
                  <Button size="sm" loading={prescribe.loading} disabled={!items.some((i) => i.medicationName.trim())} onClick={() => prescribe.run(true)}>{t("consult.issuePrescription")}</Button>
                </div>
              </Card>
            )}
            {can("lab_order.create") && (
              <Card className="space-y-3">
                <h3 className="font-medium">{t("portal.labs")}</h3>
                <ul className="space-y-1 text-sm">{e.labOrders.map((l) => <li key={l.id} className="flex items-center justify-between"><span>{l.testName} {l.priority === "URGENT" && <Badge tone="red">{t("consult.urgent")}</Badge>}</span><StatusBadge status={l.status} /></li>)}</ul>
                <div className="grid grid-cols-[1fr_auto_auto] gap-2">
                  <Input placeholder={t("consult.testName")} value={lab.testName} onChange={(x) => setLab({ ...lab, testName: x.target.value })} />
                  <Select value={lab.priority} onChange={(x) => setLab({ ...lab, priority: x.target.value })}><option value="ROUTINE">{t("consult.routine")}</option><option value="URGENT">{t("consult.urgent")}</option></Select>
                  <Button size="sm" loading={orderLab.loading} disabled={!lab.testName.trim()} onClick={() => orderLab.run()}>{t("consult.order")}</Button>
                </div>
              </Card>
            )}
          </div>
        </div>
      )}
    </>
  );
}
