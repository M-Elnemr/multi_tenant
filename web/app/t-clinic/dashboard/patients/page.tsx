"use client";

import Link from "next/link";
import { useState } from "react";
import { api } from "@/lib/client";
import { PhoneInput } from "@/components/inputs";
import { Page, useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Button, ErrorText, Field, Input, Loading, Modal, PageHeader, Pager, SecretBox, Select, Table, Td } from "@/components/ui";
import { dateOnly } from "@/lib/format";

type P = { id: string; patientCode: string; firstName: string; lastName: string; dateOfBirth?: string; sex?: string; phone?: string; hasPortal: boolean };
type Created = { id: string; patientCode: string; portalAccess: string; activationPin?: string; linkPin?: string; firstName: string; password?: string };

export default function Patients() {
  const { t, locale } = useI18n();
  const { can } = useMe();
  const [q, setQ] = useState("");
  const [page, setPage] = useState(1);
  const { data, loading, error, reload } = useApi<Page<P>>(`clinic/patients?page=${page}&q=${encodeURIComponent(q)}`);
  const [open, setOpen] = useState(false);
  const [f, setF] = useState({ firstName: "", lastName: "", phone: "", dateOfBirth: "", sex: "" });
  const [mode, setMode] = useState<"code" | "password">("code");
  const [pw, setPw] = useState("");
  const [created, setCreated] = useState<Created | null>(null);
  const create = useAction(async () => {
    const r = await api<Created>("clinic/patients", { body: { ...f, dateOfBirth: f.dateOfBirth || undefined, sex: f.sex || undefined, phone: f.phone || undefined, initialPassword: mode === "password" && pw ? pw : undefined } });
    setCreated({ ...r, password: mode === "password" && r.portalAccess === "PASSWORD_SET" ? pw : undefined });
    setPw(""); setOpen(false); setF({ firstName: "", lastName: "", phone: "", dateOfBirth: "", sex: "" });
    await reload();
  });
  return (
    <>
      <PageHeader title={t("nav.patients")} actions={can("patient.create") && <Button onClick={() => setOpen(true)}>{t("patients.add")}</Button>} />
      {created && (
        <div className="mb-5 space-y-3 rounded-xl border bg-white p-4">
          <p className="text-sm">{t("patients.created", { name: created.firstName, code: created.patientCode })}</p>
          {created.activationPin && <SecretBox label={t("patients.activationPin")} value={created.activationPin} />}
          {created.linkPin && <SecretBox label={t("patients.linkPin")} value={created.linkPin} />}
          {created.password && <SecretBox label={t("patients.passwordSet")} value={created.password} />}
          <p className="text-sm text-slate-600">{created.portalAccess === "PASSWORD_SET" ? t("patients.howPassword") : created.portalAccess === "ASSIGNED" ? t("patients.howAssigned") : created.portalAccess === "ACTIVATION_PIN" ? t("patients.howActivation") : created.portalAccess === "LINK_PIN" ? t("patients.howLink") : created.portalAccess === "GUARDIAN" ? t("patients.howGuardian") : t("patients.noPortal")}</p>
        </div>
      )}
      <div className="mb-4 max-w-md"><Input placeholder={t("patients.searchHint")} value={q} onChange={(e) => { setQ(e.target.value); setPage(1); }} /></div>
      <ErrorText error={error} />
      {loading && !data ? <Loading /> : (
        <>
          <Table head={[t("clinic.patient"), t("patients.code"), t("staff.phone"), t("patients.dob"), t("patients.portal")]}>
            {data?.data.map((p) => <tr key={p.id} className="hover:bg-slate-50"><Td><Link href={`/dashboard/patients/${p.id}`} className="font-medium text-brand">{p.firstName} {p.lastName}</Link></Td><Td className="font-mono text-xs">{p.patientCode}</Td><Td><span dir="ltr">{p.phone}</span></Td><Td>{dateOnly(p.dateOfBirth, locale)}</Td><Td>{p.hasPortal ? "✓" : "—"}</Td></tr>)}
          </Table>
          <Pager meta={data?.meta} onPage={setPage} />
        </>
      )}
      <Modal open={open} onClose={() => setOpen(false)} title={t("patients.add")}>
        <form onSubmit={(e) => { e.preventDefault(); void create.run(); }} className="space-y-3">
          <Field label={t("register.ownerName")}><Input value={f.firstName} onChange={(e) => setF({ ...f, firstName: e.target.value })} required maxLength={100} /></Field>
          <Field label={t("register.phone")} hint={t("patients.phoneHint")}><PhoneInput value={f.phone} onValue={(phone) => setF({ ...f, phone })} /></Field>
          <div className="grid grid-cols-2 gap-3"><Field label={t("patients.dob")}><Input type="date" value={f.dateOfBirth} onChange={(e) => setF({ ...f, dateOfBirth: e.target.value })} dir="ltr" /></Field><Field label={t("patients.sex")}><Select value={f.sex} onChange={(e) => setF({ ...f, sex: e.target.value })}><option value="">-</option><option value="F">{t("patients.female")}</option><option value="M">{t("patients.male")}</option></Select></Field></div>
          {f.phone && (
            <div className="space-y-2 rounded-lg border p-3 text-sm">
              <p className="font-medium">{t("patients.portalAccess")}</p>
              <label className="flex items-center gap-2"><input type="radio" checked={mode === "code"} onChange={() => setMode("code")} />{t("patients.modeCode")}</label>
              <label className="flex items-center gap-2"><input type="radio" checked={mode === "password"} onChange={() => setMode("password")} />{t("patients.modePassword")}</label>
              {mode === "password" && <Field label={t("login.password")} hint={t("patients.passwordHint")}><Input value={pw} onChange={(e) => setPw(e.target.value)} minLength={8} dir="ltr" autoComplete="off" /></Field>}
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
