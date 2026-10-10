"use client";

import Link from "next/link";
import { useState } from "react";
import { api } from "@/lib/client";
import { PhoneInput } from "@/components/inputs";
import { Page, useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { Button, ErrorText, Field, Input, Loading, Modal, PageHeader, Pager, SecretBox, Select, Table, Td, FilterBar } from "@/components/ui";
import { WhatsAppButton } from "@/components/whatsapp-button";
import { usePatientPortal } from "@/components/portal-flag";
import { ageText } from "@/lib/format";

type P = { id: string; patientCode: string; firstName: string; lastName: string; ageYears?: number; ageMonths?: number; sex?: string; phone?: string; hasPortal: boolean; dependents?: P[] };
type Created = { id: string; patientCode: string; portalAccess: string; firstName: string; password?: string };

export default function Patients() {
  const { t, locale } = useI18n();
  const { can } = useMe();
  const [q, setQ] = useState("");
  const [page, setPage] = useState(1);
  const { data, loading, error, reload } = useApi<Page<P>>(`clinic/patients?page=${page}&q=${encodeURIComponent(q)}`);
  const [open, setOpen] = useState(false);
  const portal = usePatientPortal();
  const clinic = useApi<{ clinicName?: string }>("clinic/public/profile");
  const blank = { firstName: "", phone: "", ageYears: "", ageMonths: "0", sex: "" };
  const [f, setF] = useState(blank);
  const [pw, setPw] = useState("");
  const [created, setCreated] = useState<Created | null>(null);
  const [depOf, setDepOf] = useState<P | null>(null);   // adding a child under this patient (no phone, no password)
  const create = useAction(async () => {
    const r = await api<Created>("clinic/patients", { body: { firstName: f.firstName.trim(), phone: depOf ? undefined : f.phone, guardianPatientId: depOf?.id, relationship: depOf ? "PARENT" : undefined, ageYears: Number(f.ageYears), ageMonths: Number(f.ageMonths || 0), sex: f.sex || undefined, initialPassword: portal && pw ? pw : undefined } });
    setCreated({ ...r, password: portal && r.portalAccess === "PASSWORD_SET" ? pw : undefined });
    setPw(""); setOpen(false); setDepOf(null); setF(blank);
    await reload();
  });
  return (
    <>
      <PageHeader title={t("nav.patients")} actions={can("patient.create") && <Button onClick={() => { setDepOf(null); setOpen(true); }}>{t("patients.add")}</Button>} />
      {created && (
        <div className="mb-5 space-y-3 rounded-xl border bg-white p-4">
          <p className="text-sm">{t("patients.created", { name: created.firstName, code: created.patientCode })}</p>
          {portal && created.password && <SecretBox label={t("patients.passwordSet")} value={created.password} />}
          {portal && <p className="text-sm text-slate-600">{created.portalAccess === "PASSWORD_SET" ? t("patients.howPassword") : created.portalAccess === "ASSIGNED" ? t("patients.howAssigned") : created.portalAccess === "GUARDIAN" ? t("patients.howGuardian") : t("patients.noPortal")}</p>}
        </div>
      )}
      <FilterBar><div className="relative w-full max-w-md flex-1"><Icon name="search" className="pointer-events-none absolute start-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" /><Input className="ps-10" placeholder={t("patients.searchHint")} value={q} onChange={(e) => { setQ(e.target.value); setPage(1); }} /></div></FilterBar>
      <ErrorText error={error} />
      {loading && !data ? <Loading /> : (
        <>
          <Table head={[t("clinic.patient"), t("patients.code"), t("patients.mobile"), t("patients.age"), ...(portal ? [t("patients.portal")] : [])]}>
            {data?.data.flatMap((p) => [
              <tr key={p.id} className="hover:bg-slate-50"><Td><Link href={`/dashboard/patients/${p.id}`} className="font-medium text-brand">{p.firstName} {p.lastName}</Link>{portal && p.hasPortal && can("patient.create") && <button type="button" className="ms-3 text-xs text-slate-500 hover:text-brand" onClick={() => { setDepOf(p); setOpen(true); }}>+ {t("dep.add")}</button>}</Td><Td className="font-mono text-xs">{p.patientCode}</Td><Td><span className="inline-flex items-center gap-2"><span dir="ltr">{p.phone}</span><WhatsAppButton phone={p.phone} message={t("pq.waHello", { name: p.firstName, clinic: clinic.data?.clinicName ?? "" })} size={28} /></span></Td><Td>{ageText(p.ageYears, p.ageMonths, locale)}</Td>{portal && <Td>{p.hasPortal ? "✓" : "—"}</Td>}</tr>,
              ...(p.dependents ?? []).map((c) => <tr key={c.id} className="bg-slate-50/60 hover:bg-slate-50"><Td><Link href={`/dashboard/patients/${c.id}`} className="ms-6 text-brand">↳ {c.firstName} {c.lastName}</Link><span className="ms-2 text-xs text-slate-500">{t("dep.childOf", { name: p.firstName })}</span></Td><Td className="font-mono text-xs">{c.patientCode}</Td><Td>—</Td><Td>{ageText(c.ageYears, c.ageMonths, locale)}</Td>{portal && <Td>—</Td>}</tr>),
            ])}
          </Table>
          <Pager meta={data?.meta} onPage={setPage} />
        </>
      )}
      <Modal open={open} onClose={() => setOpen(false)} title={depOf ? t("dep.addTitle", { name: depOf.firstName }) : t("patients.add")}>
        <form onSubmit={(e) => { e.preventDefault(); void create.run(); }} className="space-y-3">
          <Field label={t("patients.name")}><Input value={f.firstName} onChange={(e) => setF({ ...f, firstName: e.target.value })} required maxLength={100} /></Field>
          {depOf ? <p className="rounded-lg bg-slate-50 p-3 text-sm text-slate-600">{t("dep.noPhoneHint")}</p> : <Field label={t("patients.mobile")} hint={t("patients.mobileHint")}><PhoneInput value={f.phone} onValue={(phone) => setF({ ...f, phone })} required /></Field>}
          <p className="text-sm font-medium">{t("patients.age")} <span className="font-normal text-slate-500">· {t("patients.ageHint")}</span></p>
          <div className="grid grid-cols-2 gap-3">
            <Field label={t("patients.years")}><Input type="number" inputMode="numeric" min={0} max={130} step={1} value={f.ageYears} onChange={(e) => setF({ ...f, ageYears: e.target.value })} required dir="ltr" /></Field>
            <Field label={t("patients.months")}><Input type="number" inputMode="numeric" min={0} max={11} step={1} value={f.ageMonths} onChange={(e) => setF({ ...f, ageMonths: String(Math.min(11, Math.max(0, Math.floor(Number(e.target.value) || 0)))) })} dir="ltr" /></Field>
          </div>
          <div className="grid grid-cols-2 gap-3"><Field label={t("patients.sex")}><Select value={f.sex} onChange={(e) => setF({ ...f, sex: e.target.value })}><option value="">-</option><option value="F">{t("patients.female")}</option><option value="M">{t("patients.male")}</option></Select></Field></div>
          {portal && !depOf && f.phone && (
            <div className="space-y-2 rounded-lg border p-3 text-sm">
              <p className="font-medium">{t("patients.portalAccess")}</p>
              <Field label={t("patients.tempPassword")} hint={t("patients.tempPasswordHint")}><Input value={pw} onChange={(e) => setPw(e.target.value)} minLength={8} dir="ltr" autoComplete="off" /></Field>
              <p className="text-xs text-slate-500">{t("patients.existingHint")}</p>
            </div>
          )}
          <ErrorText error={create.error} />
          <Button type="submit" loading={create.loading} className="w-full">{t("common.save")}</Button>
        </form>
      </Modal>
    </>
  );
}
