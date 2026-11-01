"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { fromMinor, toMinor } from "@/lib/format";
import { CASE_STATUSES, CASE_TYPES, OUTCOMES, type CaseDetail, type CaseRow, type Client } from "@/lib/lawyer";
import { useFieldErrors } from "@/lib/use-field-errors";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Icon } from "../icons";
import { Button, ErrorText, Field, Input, Modal, Select, Textarea } from "../ui";

export type CaseDraft = { title: string; caseType: string; status: string; outcome: string; court: string; circuit: string; courtCaseNo: string; opponent: string; opponentLawyer: string; clientRole: string; openedOn: string; fee: string; description: string; clients: { id: string; name: string }[] };

export const emptyCase = (): CaseDraft => ({ title: "", caseType: "CIVIL", status: "OPEN", outcome: "NONE", court: "", circuit: "", courtCaseNo: "", opponent: "", opponentLawyer: "", clientRole: "", openedOn: "", fee: "", description: "", clients: [] });

export const caseDraftFrom = (c: CaseDetail): CaseDraft => ({ title: c.title, caseType: c.caseType, status: c.status, outcome: c.outcome, court: c.court, circuit: c.circuit, courtCaseNo: c.courtCaseNo, opponent: c.opponent, opponentLawyer: c.opponentLawyer,
  clientRole: c.clientRole, openedOn: c.openedOn ?? "", fee: c.agreedFeeMinor ? fromMinor(c.agreedFeeMinor) : "", description: c.description, clients: c.clients.map((x) => ({ id: x.id, name: x.name })) });

export const caseBody = (d: CaseDraft) => ({ title: d.title.trim(), caseType: d.caseType, status: d.status, outcome: d.outcome, court: d.court, circuit: d.circuit, courtCaseNo: d.courtCaseNo, opponent: d.opponent, opponentLawyer: d.opponentLawyer,
  clientRole: d.clientRole, openedOn: d.openedOn || null, agreedFeeMinor: d.fee ? toMinor(d.fee) : 0, description: d.description, clientIds: d.clients.map((c) => c.id) });

/** Search clients and pick several; "+ new client" adds one on the spot. */
export function ClientPicker({ value, onChange, single }: { value: { id: string; name: string }[]; onChange: (v: { id: string; name: string }[]) => void; single?: boolean }) {
  const { t } = useI18n();
  const [q, setQ] = useState("");
  const [adding, setAdding] = useState(false);
  const [nc, setNc] = useState({ name: "", phone: "" });
  const fe = useFieldErrors();
  const res = useApi<{ data: Client[] }>(`lawyer/clients?pageSize=6${q.trim() ? `&q=${encodeURIComponent(q.trim())}` : ""}`);
  const add = useAction(async () => {
    if (!fe.check({ name: nc.name.trim().length < 2 && t("srv.Write the client's name") })) return;
    try {
      const c = await api<Client>("lawyer/clients", { body: { name: nc.name.trim(), phone: nc.phone.trim() } });
      onChange(single ? [{ id: c.id, name: c.name }] : [...value, { id: c.id, name: c.name }]);
      setNc({ name: "", phone: "" }); setAdding(false);
    } catch (e) { if (!fe.fromError(e)) throw e; }
  });
  return (
    <div className="space-y-2">
      {value.length > 0 && <div className="flex flex-wrap gap-1.5">{value.map((c) => (
        <span key={c.id} className="inline-flex items-center gap-1 rounded-full bg-brand-soft py-1 pe-1 ps-3 text-xs font-semibold text-brand-dark">{c.name}
          <button type="button" className="grid h-5 w-5 place-items-center rounded-full hover:bg-brand/20" onClick={() => onChange(value.filter((x) => x.id !== c.id))}><Icon name="close" className="h-3 w-3" /></button></span>
      ))}</div>}
      {!adding ? (
        <>
          <Input placeholder={t("lw.client.search")} value={q} onChange={(e) => setQ(e.target.value)} />
          <div className="max-h-44 overflow-y-auto rounded-xl border border-slate-100">
            {res.data?.data.map((c) => {
              const on = value.some((x) => x.id === c.id);
              return <button key={c.id} type="button" onClick={() => onChange(on ? value.filter((x) => x.id !== c.id) : single ? [{ id: c.id, name: c.name }] : [...value, { id: c.id, name: c.name }])}
                className={`flex w-full items-center justify-between gap-2 border-b border-slate-50 px-3 py-2 text-start text-sm last:border-0 ${on ? "bg-brand-soft font-semibold text-brand-dark" : "hover:bg-slate-50"}`}>
                <span className="truncate">{c.kind === "COMPANY" ? "🏢 " : ""}{c.name}</span><span dir="ltr" className="text-xs text-slate-400">{c.phone}</span></button>;
            })}
            {res.data && res.data.data.length === 0 && <p className="p-3 text-center text-xs text-slate-500">{t("lw.client.none")}</p>}
          </div>
          <button type="button" onClick={() => { setAdding(true); setNc({ name: q, phone: "" }); }} className="text-sm font-semibold text-brand">+ {t("lw.client.quickAdd")}</button>
        </>
      ) : (
        <div className="space-y-2 rounded-xl bg-slate-50 p-3">
          <div className="grid gap-2 sm:grid-cols-2">
            <Field name="name" error={fe.err("name")} label={t("lw.client.name")}><Input value={nc.name} onChange={(e) => setNc({ ...nc, name: e.target.value })} /></Field>
            <Field name="phone" error={fe.err("phone")} label={t("lw.client.phone")}><Input dir="ltr" inputMode="tel" value={nc.phone} onChange={(e) => setNc({ ...nc, phone: e.target.value })} /></Field>
          </div>
          <ErrorText error={fe.any ? null : add.error} />
          <div className="flex gap-2"><Button type="button" size="sm" loading={add.loading} onClick={() => add.run()}>{t("common.add")}</Button><Button type="button" size="sm" variant="ghost" onClick={() => setAdding(false)}>{t("common.cancel")}</Button></div>
        </div>
      )}
    </div>
  );
}

/** The case fields. `p` prefixes the error paths ("newCase." when the case is created from the schedule). */
export function CaseFields({ d, set, err, p = "" }: { d: CaseDraft; set: (x: Partial<CaseDraft>) => void; err: (path: string) => string | undefined; p?: string }) {
  const { t, currency } = useI18n();
  return (
    <div className="space-y-4">
      <Field name={`${p}title`} error={err(`${p}title`)} label={t("lw.case.title")}><Input value={d.title} onChange={(e) => set({ title: e.target.value })} placeholder={t("lw.case.titlePh")} /></Field>
      <div className="grid gap-3 sm:grid-cols-3">
        <Field name={`${p}caseType`} error={err(`${p}caseType`)} label={t("lw.case.type")}><Select value={d.caseType} onChange={(e) => set({ caseType: e.target.value })}>{CASE_TYPES.map((x) => <option key={x} value={x}>{t(`lw.type.${x}`)}</option>)}</Select></Field>
        <Field name={`${p}status`} error={err(`${p}status`)} label={t("lw.case.status")}><Select value={d.status} onChange={(e) => set({ status: e.target.value, outcome: e.target.value === "UNDER_STUDY" ? "NONE" : d.outcome })}>{CASE_STATUSES.map((x) => <option key={x} value={x}>{t(`lw.status.${x}`)}</option>)}</Select></Field>
        <Field name={`${p}outcome`} error={err(`${p}outcome`)} label={t("lw.case.outcome")}><Select value={d.outcome} disabled={d.status === "UNDER_STUDY"} onChange={(e) => set({ outcome: e.target.value })}><option value="NONE">{t("lw.outcome.NONE")}</option>{OUTCOMES.map((x) => <option key={x} value={x}>{t(`lw.outcome.${x}`)}</option>)}</Select></Field>
      </div>
      <div data-field={`${p}clientIds`}><p className="mb-1.5 text-sm font-semibold text-slate-700">{t("lw.case.clients")}</p><ClientPicker value={d.clients} onChange={(clients) => set({ clients })} />
        {err(`${p}clientIds`) && <p className="mt-1 text-xs font-medium text-red-600">{err(`${p}clientIds`)}</p>}</div>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label={t("lw.case.court")}><Input value={d.court} onChange={(e) => set({ court: e.target.value })} placeholder={t("lw.case.courtPh")} /></Field>
        <Field label={t("lw.case.circuit")}><Input value={d.circuit} onChange={(e) => set({ circuit: e.target.value })} /></Field>
        <Field label={t("lw.case.courtNo")}><Input dir="ltr" value={d.courtCaseNo} onChange={(e) => set({ courtCaseNo: e.target.value })} placeholder="1234 / 2026" /></Field>
        <Field label={t("lw.case.clientRole")}><Select value={d.clientRole} onChange={(e) => set({ clientRole: e.target.value })}><option value="">—</option>{["PLAINTIFF", "DEFENDANT", "APPELLANT", "RESPONDENT", "COMPLAINANT", "ACCUSED", "CONSULTANT"].map((r) => <option key={r} value={r}>{t(`lw.role.${r}`)}</option>)}</Select></Field>
        <Field label={t("lw.case.opponent")}><Input value={d.opponent} onChange={(e) => set({ opponent: e.target.value })} /></Field>
        <Field label={t("lw.case.opponentLawyer")}><Input value={d.opponentLawyer} onChange={(e) => set({ opponentLawyer: e.target.value })} /></Field>
        <Field name={`${p}openedOn`} error={err(`${p}openedOn`)} label={t("lw.case.openedOn")}><Input type="date" value={d.openedOn} onChange={(e) => set({ openedOn: e.target.value })} /></Field>
        <Field name={`${p}agreedFeeMinor`} error={err(`${p}agreedFeeMinor`)} label={t("lw.case.fee", { c: currency })} hint={t("lw.case.feeHint")}><Input dir="ltr" inputMode="decimal" value={d.fee} onChange={(e) => set({ fee: e.target.value })} /></Field>
      </div>
      <Field label={t("lw.case.description")}><Textarea rows={3} value={d.description} onChange={(e) => set({ description: e.target.value })} /></Field>
    </div>
  );
}

/** New / edit case in a sheet. */
export function CaseModal({ open, onClose, initial, onSaved, clients }: { open: boolean; onClose: () => void; initial?: CaseDetail; onSaved: (c: CaseDetail) => void; clients?: { id: string; name: string }[] }) {
  const { t } = useI18n();
  const [d, setD] = useState<CaseDraft>(() => (initial ? caseDraftFrom(initial) : { ...emptyCase(), clients: clients ?? [] }));
  const fe = useFieldErrors();
  const save = useAction(async () => {
    if (!fe.check({ title: d.title.trim().length < 3 && t("srv.Give the case a clear title") })) return;
    try {
      const c = initial ? await api<CaseDetail>(`lawyer/cases/${initial.id}`, { method: "PUT", body: caseBody(d) }) : await api<CaseDetail>("lawyer/cases", { body: caseBody(d) });
      onSaved(c);
    } catch (e) { if (!fe.fromError(e)) throw e; }
  });
  return (
    <Modal open={open} onClose={onClose} title={initial ? t("lw.case.edit") : t("lw.case.new")} wide>
      <form onSubmit={(e) => { e.preventDefault(); void save.run(); }} className="space-y-4">
        <CaseFields d={d} set={(x) => { setD((o) => ({ ...o, ...x })); for (const k of Object.keys(x)) fe.clear(k); }} err={fe.err} />
        {fe.any ? <p className="rounded-xl bg-red-50 px-4 py-3 text-sm font-medium text-red-700">{t("form.fixMarked")}</p> : <ErrorText error={save.error} />}
        <div className="flex gap-2"><Button type="submit" loading={save.loading}>{initial ? t("common.save") : t("lw.case.create")}</Button><Button type="button" variant="secondary" onClick={onClose}>{t("common.cancel")}</Button></div>
      </form>
    </Modal>
  );
}

/** Pick an existing case by number, title or client, or switch to creating a new one. */
export function CasePicker({ value, onChange }: { value: { id: string; label: string } | null; onChange: (v: { id: string; label: string } | null) => void }) {
  const { t } = useI18n();
  const [q, setQ] = useState("");
  const res = useApi<{ data: CaseRow[] }>(`lawyer/cases?pageSize=6&status=OPEN,UNDER_STUDY${q.trim() ? `&q=${encodeURIComponent(q.trim())}` : ""}`);
  if (value) return (
    <div className="flex items-center justify-between gap-2 rounded-xl border border-brand/30 bg-brand-soft px-3 py-2.5 text-sm font-semibold text-brand-dark">
      <span className="truncate">💼 {value.label}</span><button type="button" onClick={() => onChange(null)} className="text-xs underline">{t("lw.pick.change")}</button>
    </div>
  );
  return (
    <div className="space-y-2">
      <Input placeholder={t("lw.pick.search")} value={q} onChange={(e) => setQ(e.target.value)} />
      <div className="max-h-48 overflow-y-auto rounded-xl border border-slate-100">
        {res.data?.data.map((c) => (
          <button key={c.id} type="button" onClick={() => onChange({ id: c.id, label: `#${c.number} · ${c.title}` })} className="block w-full border-b border-slate-50 px-3 py-2 text-start text-sm last:border-0 hover:bg-slate-50">
            <span className="font-semibold text-slate-800">#{c.number} · {c.title}</span>{c.clientNames && <span className="block truncate text-xs text-slate-500">{c.clientNames}</span>}
          </button>
        ))}
        {res.data && res.data.data.length === 0 && <p className="p-3 text-center text-xs text-slate-500">{t("lw.pick.none")}</p>}
      </div>
    </div>
  );
}
