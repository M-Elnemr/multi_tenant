"use client";

import Link from "next/link";
import { use, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/client";
import { dateOnly, dateTime, fromMinor, money, toMinor } from "@/lib/format";
import { PAY_METHODS, statusTone, type Action, type CaseDetail, type Step } from "@/lib/lawyer";
import { useFieldErrors } from "@/lib/use-field-errors";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { uploadFile } from "@/components/uploader";
import { CaseModal } from "@/components/lawyer/case-form";
import { StepFields } from "@/components/lawyer/step-fields";
import { Badge, Button, ErrorText, Field, Input, Loading, Modal, PageHeader, Select, Tabs, Textarea } from "@/components/ui";

type Tab = "overview" | "timeline" | "actions" | "payments" | "documents";
const pad = (n: number) => String(n).padStart(2, "0");
const localNow = () => { const d = new Date(Date.now() + 86400000); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T10:00`; };

export default function CasePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { t, locale, currency } = useI18n();
  const router = useRouter();
  const { canWrite, can } = useMe();
  const { data, loading, error, reload } = useApi<CaseDetail>(`lawyer/cases/${id}`);
  const [tab, setTab] = useState<Tab>("overview");
  const [editing, setEditing] = useState(false);
  const [stepEdit, setStepEdit] = useState<Step | "new" | null>(null);
  const [actionEdit, setActionEdit] = useState<Action | "new" | null>(null);
  const [paying, setPaying] = useState(false);
  const [confirmDel, setConfirmDel] = useState(false);
  const write = canWrite("cases");
  const fmt = (m: number) => money(m, currency, locale);
  const status = useAction(async (body: { status?: string; outcome?: string }) => { await api(`lawyer/cases/${id}/status`, { method: "PUT", body }); await reload(); });
  const delStep = useAction(async (sid: string) => { await api(`lawyer/cases/${id}/steps/${sid}`, { method: "DELETE" }); await reload(); });
  const toggleStep = useAction(async (s: Step) => { await api(`lawyer/cases/${id}/steps/${s.id}`, { method: "PUT", body: { ...s, done: !s.done } }); await reload(); });
  const delAction = useAction(async (aid: string) => { await api(`lawyer/cases/${id}/actions/${aid}`, { method: "DELETE" }); await reload(); });
  const delPay = useAction(async (pid: string) => { await api(`lawyer/cases/${id}/payments/${pid}`, { method: "DELETE" }); await reload(); });
  const delDoc = useAction(async (did: string) => { await api(`lawyer/cases/${id}/documents/${did}`, { method: "DELETE" }); await reload(); });
  const delCase = useAction(async () => { await api(`lawyer/cases/${id}`, { method: "DELETE" }); router.replace("/dashboard/cases"); });
  const [now] = useState(() => Date.now());
  const fileRef = useRef<HTMLInputElement>(null);
  const upload = useAction(async (f: File) => { const fileId = await uploadFile(f, "LEGAL_DOCUMENT", "TENANT_INTERNAL"); await api(`lawyer/cases/${id}/documents`, { body: { fileId, name: f.name } }); await reload(); });

  if (loading && !data) return <Loading />;
  if (!data) return <ErrorText error={error} />;
  const k = data;
  const nextStep = k.steps.find((s) => !s.done && new Date(s.startsAt).getTime() > now);
  const paidPct = k.billedMinor > 0 ? Math.min(100, Math.round((k.paidMinor / k.billedMinor) * 100)) : 0;
  const info: [string, string | null | undefined][] = [
    [t("lw.case.type"), t(`lw.type.${k.caseType}`)], [t("lw.case.court"), k.court], [t("lw.case.circuit"), k.circuit], [t("lw.case.courtNo"), k.courtCaseNo],
    [t("lw.case.clientRole"), k.clientRole ? t(`lw.role.${k.clientRole}`) : ""], [t("lw.case.opponent"), k.opponent], [t("lw.case.opponentLawyer"), k.opponentLawyer],
    [t("lw.case.openedOn"), dateOnly(k.openedOn, locale)], [t("lw.case.closedOn"), k.closedOn ? dateOnly(k.closedOn, locale) : ""],
  ];
  return (
    <>
      <div className="mb-3"><Link href="/dashboard/cases" className="text-sm font-semibold text-slate-500 hover:text-brand">← {t("lw.nav.cases")}</Link></div>
      <PageHeader title={k.title} subtitle={`#${k.number}${k.courtCaseNo ? ` · ${k.courtCaseNo}` : ""}`}
        badge={<><Badge tone={statusTone[k.status]}>{t(`lw.status.${k.status}`)}</Badge>{k.outcome !== "NONE" && <Badge tone={statusTone[k.outcome]}>{t(`lw.outcome.${k.outcome}`)}</Badge>}</>}
        actions={write && <><Button variant="secondary" onClick={() => setEditing(true)}><Icon name="edit" className="h-4 w-4" />{t("lw.case.edit")}</Button><Button onClick={() => { setTab("timeline"); setStepEdit("new"); }}><Icon name="calendar" className="h-4 w-4" />{t("lw.step.add")}</Button></>} />

      <div className="mb-5 grid gap-3 sm:grid-cols-3">
        <div className="rounded-2xl bg-gradient-to-br from-indigo-500 to-violet-600 p-4 text-white shadow-lg">
          <p className="text-xs font-semibold opacity-80">{t("lw.case.nextStep")}</p>
          {nextStep ? <><p className="mt-1 text-lg font-extrabold">{dateTime(nextStep.startsAt, locale)}</p><p className="truncate text-sm opacity-90">{t(`lw.cal.${nextStep.kind}`)} · {nextStep.description}</p></> : <p className="mt-1 text-sm opacity-90">{t("lw.case.noNext")}</p>}
        </div>
        <div className="rounded-2xl bg-white p-4 shadow-card">
          <p className="text-xs font-semibold text-slate-500">{t("lw.fin.billed")}</p><p className="mt-1 text-xl font-extrabold text-ink">{fmt(k.billedMinor)}</p>
          <div className="mt-2 h-2 overflow-hidden rounded-full bg-slate-100"><div className="h-full rounded-full bg-emerald-500" style={{ width: `${paidPct}%` }} /></div>
          <p className="mt-1 text-xs text-slate-500">{t("lw.fin.paidOf", { p: paidPct, a: fmt(k.paidMinor) })}</p>
        </div>
        <div className={`rounded-2xl p-4 shadow-card ${k.balanceMinor > 0 ? "bg-red-50" : "bg-emerald-50"}`}>
          <p className="text-xs font-semibold text-slate-500">{t("lw.fin.balance")}</p><p className={`mt-1 text-xl font-extrabold ${k.balanceMinor > 0 ? "text-red-600" : "text-emerald-700"}`}>{fmt(k.balanceMinor)}</p>
          {can("legal.finance.manage") && <button type="button" onClick={() => setPaying(true)} className="mt-2 text-sm font-semibold text-brand">+ {t("lw.pay.add")}</button>}
        </div>
      </div>

      <div className="mb-4 overflow-x-auto"><Tabs value={tab} onChange={setTab} tabs={[
        { key: "overview", label: t("lw.tab.overview") }, { key: "timeline", label: t("lw.tab.timeline"), count: k.steps.length }, { key: "actions", label: t("lw.tab.actions"), count: k.actions.length },
        { key: "payments", label: t("lw.tab.payments"), count: k.payments.length }, { key: "documents", label: t("lw.tab.documents"), count: k.documents.length },
      ]} /></div>
      <ErrorText error={status.error ?? delStep.error ?? toggleStep.error ?? delAction.error ?? delPay.error ?? delDoc.error ?? upload.error} />

      {tab === "overview" && (
        <div className="grid gap-5 lg:grid-cols-[1.4fr_1fr]">
          <div className="space-y-5">
            <div className="rounded-2xl bg-white p-5 shadow-card">
              <dl className="grid gap-x-6 gap-y-3 sm:grid-cols-2">{info.filter(([, v]) => v).map(([l, v]) => <div key={l}><dt className="text-xs font-semibold text-slate-500">{l}</dt><dd className="font-semibold text-slate-800">{v}</dd></div>)}</dl>
              {k.description && <p className="mt-4 whitespace-pre-line border-t border-slate-100 pt-4 text-sm leading-relaxed text-slate-700">{k.description}</p>}
            </div>
            {write && (
              <div className="rounded-2xl bg-white p-5 shadow-card">
                <p className="mb-3 text-sm font-bold text-slate-700">{t("lw.case.changeStatus")}</p>
                <div className="flex flex-wrap gap-2">
                  {(["UNDER_STUDY", "OPEN"] as const).map((s) => <button key={s} type="button" disabled={k.status === s} onClick={() => status.run({ status: s, outcome: "NONE" })} className={`chip ${k.status === s ? "chip-on" : ""}`}>{t(`lw.status.${s}`)}</button>)}
                  <button type="button" onClick={() => status.run({ status: "CLOSED", outcome: "WON" })} className={`chip ${k.outcome === "WON" ? "chip-on" : ""}`}>🏆 {t("lw.quick.won")}</button>
                  <button type="button" onClick={() => status.run({ status: "CLOSED", outcome: "LOST" })} className={`chip ${k.outcome === "LOST" ? "chip-on" : ""}`}>{t("lw.quick.lost")}</button>
                  <button type="button" onClick={() => status.run({ status: "CLOSED", outcome: "SETTLED" })} className={`chip ${k.outcome === "SETTLED" ? "chip-on" : ""}`}>🤝 {t("lw.outcome.SETTLED")}</button>
                  <button type="button" onClick={() => status.run({ status: "CLOSED", outcome: "NONE" })} className={`chip ${k.status === "CLOSED" && k.outcome === "NONE" ? "chip-on" : ""}`}>{t("lw.status.CLOSED")}</button>
                </div>
              </div>
            )}
          </div>
          <div className="space-y-5">
            <div className="rounded-2xl bg-white p-5 shadow-card">
              <p className="mb-3 text-sm font-bold text-slate-700">{t("lw.case.clients")}</p>
              {k.clients.length === 0 ? <p className="text-sm text-slate-500">{t("lw.case.noClients")}</p> : (
                <ul className="space-y-2">{k.clients.map((c) => (
                  <li key={c.id} className="flex items-center justify-between gap-2 rounded-xl bg-slate-50 p-3">
                    <Link href={`/dashboard/clients/${c.id}`} className="min-w-0"><p className="truncate font-semibold text-slate-800">{c.kind === "COMPANY" ? "🏢 " : "👤 "}{c.name}</p><p dir="ltr" className="text-xs text-slate-500">{c.phone}</p></Link>
                    {c.phone && <div className="flex gap-1"><a href={`tel:${c.phone}`} className="grid h-9 w-9 place-items-center rounded-full bg-white text-slate-600 shadow-sm"><Icon name="phone" className="h-4 w-4" /></a>
                      <a href={`https://wa.me/${c.phone.replace(/[^0-9]/g, "")}`} target="_blank" rel="noopener noreferrer" className="grid h-9 w-9 place-items-center rounded-full bg-emerald-500 text-white shadow-sm"><Icon name="chat" className="h-4 w-4" /></a></div>}
                  </li>
                ))}</ul>
              )}
            </div>
            {k.notes.length > 0 && <div className="rounded-2xl bg-white p-5 shadow-card"><p className="mb-3 text-sm font-bold text-slate-700">{t("lw.case.notes")}</p>
              <ul className="space-y-2">{k.notes.map((n) => <li key={n.id} className="rounded-xl bg-amber-50 p-3"><p className="text-xs font-semibold text-amber-800">{dateTime(n.startsAt, locale)}</p><p className="font-semibold text-slate-800">{n.title}</p>{n.body && <p className="text-sm text-slate-600">{n.body}</p>}</li>)}</ul></div>}
            {write && <button type="button" onClick={() => setConfirmDel(true)} className="text-sm font-semibold text-red-600">{t("lw.case.delete")}</button>}
          </div>
        </div>
      )}

      {tab === "timeline" && (
        <div className="space-y-3">
          {write && <Button onClick={() => setStepEdit("new")}><Icon name="plus" className="h-4 w-4" />{t("lw.step.add")}</Button>}
          {k.steps.length === 0 ? <p className="rounded-2xl border border-dashed border-slate-300 bg-white/60 p-8 text-center text-sm text-slate-500">{t("lw.step.none")}</p> : (
            <ol className="relative space-y-4 border-s-2 border-indigo-100 ps-6">
              {k.steps.map((s) => {
                const past = new Date(s.startsAt).getTime() < now;
                return (
                  <li key={s.id} className="relative">
                    <button type="button" disabled={!write} onClick={() => toggleStep.run(s)} className={`absolute -start-[35px] top-3 grid h-6 w-6 place-items-center rounded-full ring-4 ring-white ${s.done ? "bg-emerald-500 text-white" : past ? "bg-red-400 text-white" : "bg-indigo-500 text-white"}`} aria-label={t("lw.step.toggle")}>{s.done ? <Icon name="check" className="h-3.5 w-3.5" /> : null}</button>
                    <div className={`rounded-2xl bg-white p-4 shadow-card ${s.done ? "opacity-70" : ""}`}>
                      <div className="flex flex-wrap items-start justify-between gap-2">
                        <div><p className="text-xs font-bold text-indigo-600">{t(`lw.cal.${s.kind}`)} · {dateTime(s.startsAt, locale)}</p><p className={`mt-0.5 font-semibold text-slate-800 ${s.done ? "line-through" : ""}`}>{s.description}</p>
                          {s.location && <p className="text-xs text-slate-500">📍 {s.location}</p>}{s.result && <p className="mt-1 rounded-lg bg-slate-50 px-2 py-1 text-sm text-slate-700">{t("lw.step.result")}: {s.result}</p>}</div>
                        {write && <div className="flex gap-1"><Button size="sm" variant="ghost" onClick={() => setStepEdit(s)}><Icon name="edit" className="h-3.5 w-3.5" /></Button><Button size="sm" variant="ghost" className="!text-red-600" onClick={() => delStep.run(s.id)}><Icon name="close" className="h-3.5 w-3.5" /></Button></div>}
                      </div>
                    </div>
                  </li>
                );
              })}
            </ol>
          )}
        </div>
      )}

      {tab === "actions" && (
        <div className="space-y-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            {write && <Button onClick={() => setActionEdit("new")}><Icon name="plus" className="h-4 w-4" />{t("lw.action.add")}</Button>}
            <p className="text-sm text-slate-600">{t("lw.action.total", { a: fmt(k.actionFeesMinor), f: fmt(k.agreedFeeMinor) })}</p>
          </div>
          {k.actions.length === 0 ? <p className="rounded-2xl border border-dashed border-slate-300 bg-white/60 p-8 text-center text-sm text-slate-500">{t("lw.action.none")}</p> : (
            <ul className="space-y-2">{k.actions.map((a) => (
              <li key={a.id} className="flex items-start gap-3 rounded-2xl bg-white p-4 shadow-card">
                <span className="grid h-11 w-11 shrink-0 place-items-center rounded-xl bg-slate-100 text-center text-[11px] font-bold leading-tight text-slate-600">{new Date(a.actionDate).toLocaleDateString(locale === "ar" ? "ar-EG" : "en-GB", { day: "numeric", month: "short" })}</span>
                <div className="min-w-0 flex-1"><p className="font-semibold text-slate-800">{a.title}</p>{a.notes && <p className="mt-0.5 whitespace-pre-line text-sm text-slate-600">{a.notes}</p>}</div>
                <div className="text-end">{a.feeMinor > 0 && <p className="font-bold text-ink">{fmt(a.feeMinor)}</p>}
                  {write && <div className="mt-1 flex gap-1"><Button size="sm" variant="ghost" onClick={() => setActionEdit(a)}><Icon name="edit" className="h-3.5 w-3.5" /></Button><Button size="sm" variant="ghost" className="!text-red-600" onClick={() => delAction.run(a.id)}><Icon name="close" className="h-3.5 w-3.5" /></Button></div>}</div>
              </li>
            ))}</ul>
          )}
        </div>
      )}

      {tab === "payments" && (
        <div className="space-y-3">
          {can("legal.finance.manage") && <Button onClick={() => setPaying(true)}><Icon name="plus" className="h-4 w-4" />{t("lw.pay.add")}</Button>}
          {k.payments.length === 0 ? <p className="rounded-2xl border border-dashed border-slate-300 bg-white/60 p-8 text-center text-sm text-slate-500">{t("lw.pay.none")}</p> : (
            <ul className="space-y-2">{k.payments.map((p) => (
              <li key={p.id} className="flex items-center gap-3 rounded-2xl bg-white p-4 shadow-card">
                <span className="grid h-10 w-10 place-items-center rounded-xl bg-emerald-50 text-emerald-600"><Icon name="wallet" className="h-5 w-5" /></span>
                <div className="min-w-0 flex-1"><p className="font-bold text-emerald-700">{fmt(p.amountMinor)}</p><p className="text-xs text-slate-500">{dateOnly(p.paidOn, locale)} · {t(`lw.pm.${p.method}`)}{p.clientName ? ` · ${p.clientName}` : ""}{p.note ? ` · ${p.note}` : ""}</p></div>
                {can("legal.finance.manage") && <Button size="sm" variant="ghost" className="!text-red-600" onClick={() => delPay.run(p.id)}><Icon name="close" className="h-3.5 w-3.5" /></Button>}
              </li>
            ))}</ul>
          )}
        </div>
      )}

      {tab === "documents" && (
        <div className="space-y-3">
          {write && <><input ref={fileRef} type="file" accept="image/jpeg,image/png,application/pdf" className="hidden" onChange={(e) => { const f = e.target.files?.[0]; if (f) void upload.run(f); e.target.value = ""; }} />
            <Button loading={upload.loading} onClick={() => fileRef.current?.click()}><Icon name="plus" className="h-4 w-4" />{t("lw.doc.upload")}</Button><p className="text-xs text-slate-500">{t("lw.doc.hint")}</p></>}
          {k.documents.length === 0 ? <p className="rounded-2xl border border-dashed border-slate-300 bg-white/60 p-8 text-center text-sm text-slate-500">{t("lw.doc.none")}</p> : (
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">{k.documents.map((d) => (
              <div key={d.id} className="flex items-center gap-3 rounded-2xl bg-white p-4 shadow-card">
                <span className={`grid h-11 w-11 place-items-center rounded-xl text-lg ${d.contentType.includes("pdf") ? "bg-red-50" : "bg-sky-50"}`}>{d.contentType.includes("pdf") ? "📄" : "🖼️"}</span>
                <a href={`/api/bff${d.url.replace("/api/v1", "")}`} target="_blank" rel="noopener noreferrer" className="min-w-0 flex-1"><p className="truncate font-semibold text-slate-800 hover:text-brand">{d.name}</p><p className="text-xs text-slate-500">{dateOnly(d.createdAt, locale)}</p></a>
                {write && <Button size="sm" variant="ghost" className="!text-red-600" onClick={() => delDoc.run(d.id)}><Icon name="close" className="h-3.5 w-3.5" /></Button>}
              </div>
            ))}</div>
          )}
        </div>
      )}

      {editing && <CaseModal open initial={k} onClose={() => setEditing(false)} onSaved={async () => { setEditing(false); await reload(); }} />}
      {stepEdit && <StepModal caseId={id} step={stepEdit === "new" ? null : stepEdit} onClose={() => setStepEdit(null)} onSaved={async () => { setStepEdit(null); await reload(); }} />}
      {actionEdit && <ActionModal caseId={id} action={actionEdit === "new" ? null : actionEdit} onClose={() => setActionEdit(null)} onSaved={async () => { setActionEdit(null); await reload(); }} />}
      {paying && <PaymentModal k={k} onClose={() => setPaying(false)} onSaved={async () => { setPaying(false); await reload(); }} />}
      <Modal open={confirmDel} onClose={() => setConfirmDel(false)} title={t("lw.case.deleteTitle")}>
        <p className="text-sm text-slate-600">{t("lw.case.deleteBody")}</p>
        <div className="mt-5 flex gap-2"><Button variant="danger" loading={delCase.loading} onClick={() => delCase.run()}>{t("lw.case.delete")}</Button><Button variant="secondary" onClick={() => setConfirmDel(false)}>{t("common.cancel")}</Button></div>
      </Modal>
    </>
  );
}

const toLocal = (iso: string) => { const d = new Date(iso); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`; };

function StepModal({ caseId, step, onClose, onSaved }: { caseId: string; step: Step | null; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const fe = useFieldErrors();
  const [f, setF] = useState({ kind: step?.kind ?? "HEARING", startsAt: step ? toLocal(step.startsAt) : localNow(), durationMinutes: String(step?.durationMinutes ?? 60), description: step?.description ?? "", location: step?.location ?? "", result: step?.result ?? "", done: step?.done ?? false });
  const save = useAction(async () => {
    if (!fe.check({ description: !f.description.trim() && t("srv.Describe this step"), startsAt: !f.startsAt && t("srv.Choose the date and time") })) return;
    const body = { kind: f.kind, startsAt: new Date(f.startsAt).toISOString(), durationMinutes: Number(f.durationMinutes), description: f.description, location: f.location, result: f.result, done: f.done };
    try { if (step) await api(`lawyer/cases/${caseId}/steps/${step.id}`, { method: "PUT", body }); else await api(`lawyer/cases/${caseId}/steps`, { body }); } catch (e) { if (!fe.fromError(e)) throw e; return; }
    onSaved();
  });
  return (
    <Modal open onClose={onClose} title={step ? t("lw.step.edit") : t("lw.step.add")}>
      <form onSubmit={(e) => { e.preventDefault(); void save.run(); }} className="space-y-3">
        <StepFields f={f} set={(x) => { setF((o) => ({ ...o, ...x })); for (const k of Object.keys(x)) fe.clear(k); }} err={fe.err} />
        {step && <><Field label={t("lw.step.result")}><Textarea rows={2} value={f.result} onChange={(e) => setF({ ...f, result: e.target.value })} placeholder={t("lw.step.resultPh")} /></Field>
          <label className="flex items-center gap-2 text-sm font-semibold"><input type="checkbox" checked={f.done} onChange={(e) => setF({ ...f, done: e.target.checked })} className="h-5 w-5 accent-[var(--brand)]" />{t("lw.step.done")}</label></>}
        <p className="rounded-xl bg-indigo-50 px-3 py-2 text-xs font-medium text-indigo-700">🔔 {t("lw.step.remindNote")}</p>
        {!fe.any && <ErrorText error={save.error} />}
        <Button type="submit" className="w-full" loading={save.loading}>{t("common.save")}</Button>
      </form>
    </Modal>
  );
}

function ActionModal({ caseId, action, onClose, onSaved }: { caseId: string; action: Action | null; onClose: () => void; onSaved: () => void }) {
  const { t, currency } = useI18n();
  const fe = useFieldErrors();
  const today = new Date();
  const [f, setF] = useState({ actionDate: action?.actionDate ?? `${today.getFullYear()}-${pad(today.getMonth() + 1)}-${pad(today.getDate())}`, title: action?.title ?? "", notes: action?.notes ?? "", fee: action?.feeMinor ? fromMinor(action.feeMinor) : "" });
  const save = useAction(async () => {
    if (!fe.check({ title: !f.title.trim() && t("srv.Describe what was done") })) return;
    const body = { actionDate: f.actionDate, title: f.title, notes: f.notes, feeMinor: f.fee ? toMinor(f.fee) : 0 };
    try { if (action) await api(`lawyer/cases/${caseId}/actions/${action.id}`, { method: "PUT", body }); else await api(`lawyer/cases/${caseId}/actions`, { body }); } catch (e) { if (!fe.fromError(e)) throw e; return; }
    onSaved();
  });
  return (
    <Modal open onClose={onClose} title={action ? t("lw.action.edit") : t("lw.action.add")}>
      <form onSubmit={(e) => { e.preventDefault(); void save.run(); }} className="space-y-3">
        <div className="flex flex-wrap gap-1.5">{["lw.action.s1", "lw.action.s2", "lw.action.s3", "lw.action.s4", "lw.action.s5"].map((s) => <button key={s} type="button" className="chip" onClick={() => setF({ ...f, title: t(s) })}>{t(s)}</button>)}</div>
        <Field name="title" error={fe.err("title")} label={t("lw.action.title")}><Input value={f.title} onChange={(e) => { setF({ ...f, title: e.target.value }); fe.clear("title"); }} /></Field>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label={t("lw.action.date")}><Input type="date" value={f.actionDate} onChange={(e) => setF({ ...f, actionDate: e.target.value })} /></Field>
          <Field name="feeMinor" error={fe.err("feeMinor")} label={t("lw.action.fee", { c: currency })} hint={t("offer.optional")}><Input dir="ltr" inputMode="decimal" value={f.fee} onChange={(e) => setF({ ...f, fee: e.target.value })} /></Field>
        </div>
        <Field label={t("lw.action.notes")}><Textarea rows={4} value={f.notes} onChange={(e) => setF({ ...f, notes: e.target.value })} /></Field>
        {!fe.any && <ErrorText error={save.error} />}
        <Button type="submit" className="w-full" loading={save.loading}>{t("common.save")}</Button>
      </form>
    </Modal>
  );
}

function PaymentModal({ k, onClose, onSaved }: { k: CaseDetail; onClose: () => void; onSaved: () => void }) {
  const { t, currency } = useI18n();
  const fe = useFieldErrors();
  const today = new Date();
  const [f, setF] = useState({ amount: k.balanceMinor > 0 ? fromMinor(k.balanceMinor) : "", paidOn: `${today.getFullYear()}-${pad(today.getMonth() + 1)}-${pad(today.getDate())}`, method: "CASH", note: "", clientId: k.clients[0]?.id ?? "" });
  const save = useAction(async () => {
    if (!fe.check({ amountMinor: !(Number(f.amount) > 0) && t("srv.Enter the amount received") })) return;
    try { await api(`lawyer/cases/${k.id}/payments`, { body: { amountMinor: toMinor(f.amount), paidOn: f.paidOn, method: f.method, note: f.note, clientId: f.clientId || null } }); } catch (e) { if (!fe.fromError(e)) throw e; return; }
    onSaved();
  });
  return (
    <Modal open onClose={onClose} title={t("lw.pay.add")}>
      <form onSubmit={(e) => { e.preventDefault(); void save.run(); }} className="space-y-3">
        <Field name="amountMinor" error={fe.err("amountMinor")} label={t("lw.pay.amount", { c: currency })}><Input dir="ltr" inputMode="decimal" className="text-lg font-bold" value={f.amount} onChange={(e) => { setF({ ...f, amount: e.target.value }); fe.clear("amountMinor"); }} /></Field>
        <div className="flex flex-wrap gap-1.5">{PAY_METHODS.map((m) => <button key={m} type="button" onClick={() => setF({ ...f, method: m })} className={`chip ${f.method === m ? "chip-on" : ""}`}>{t(`lw.pm.${m}`)}</button>)}</div>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label={t("lw.pay.date")}><Input type="date" value={f.paidOn} onChange={(e) => setF({ ...f, paidOn: e.target.value })} /></Field>
          {k.clients.length > 1 && <Field label={t("lw.pay.from")}><Select value={f.clientId} onChange={(e) => setF({ ...f, clientId: e.target.value })}>{k.clients.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}</Select></Field>}
        </div>
        <Field label={t("lw.pay.note")}><Input value={f.note} onChange={(e) => setF({ ...f, note: e.target.value })} /></Field>
        {!fe.any && <ErrorText error={save.error} />}
        <Button type="submit" className="w-full" loading={save.loading}>{t("common.save")}</Button>
      </form>
    </Modal>
  );
}
