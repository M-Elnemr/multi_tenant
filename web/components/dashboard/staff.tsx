"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Button, Card, ErrorText, Field, Input, Loading, Modal, PageHeader, SecretBox, Select, StatusBadge, Table, Td } from "../ui";
import { PhoneInput } from "@/components/inputs";

type Member = { userId: string; firstName: string; lastName: string; phone: string; roles: string; userStatus: string };

const ROLES = { STORE: ["STORE_ADMIN", "STORE_MANAGER", "INVENTORY_MANAGER", "ORDER_MANAGER", "CUSTOMER_SUPPORT", "REPORT_VIEWER"], CLINIC: ["DOCTOR", "CLINIC_ADMIN", "RECEPTIONIST", "NURSE", "LAB_COORDINATOR", "ACCOUNTANT", "REPORT_VIEWER"] };

/** Add a doctor/receptionist/staff member: they get a one-time PIN to create their own password. No SMS needed. */
export default function StaffPage() {
  const { t, tenantType } = useI18n();
  const roles = ROLES[tenantType ?? "STORE"];
  const { data, loading, reload, error } = useApi<Member[]>("tenant/members");
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({ firstName: "", lastName: "", phone: "", email: "", role: roles[0] });
  const [pin, setPin] = useState<{ pin: string; name: string } | null>(null);
  const invite = useAction(async () => {
    const r = await api<{ activationPin?: string }>("tenant/members", { body: form });
    setPin(r.activationPin ? { pin: r.activationPin, name: form.firstName } : null);
    setOpen(false);
    setForm({ firstName: "", lastName: "", phone: "", email: "", role: roles[0] });
    await reload();
  });
  const reissue = useAction(async (m: Member) => {
    const r = await api<{ activationPin: string }>(`tenant/members/${m.userId}/activation-pin`, { body: {} });
    setPin({ pin: r.activationPin, name: m.firstName });
  });
  const remove = useAction(async (id: string) => { await api(`tenant/members/${id}`, { method: "DELETE" }); await reload(); });

  return (
    <>
      <PageHeader title={t("staff.title")} subtitle={t("staff.subtitle")} actions={<Button onClick={() => setOpen(true)}>{t("staff.add")}</Button>} />
      {pin && <div className="mb-4"><SecretBox label={t("staff.pinFor", { name: pin.name })} value={pin.pin} /><p className="mt-2 text-sm text-slate-600">{t("staff.pinHow")}</p></div>}
      <ErrorText error={error ?? reissue.error ?? remove.error} />
      {loading && !data ? <Loading /> : (
        <Table head={[t("staff.name"), t("staff.phone"), t("staff.role"), t("admin.status"), ""]}>
          {data?.map((m) => (
            <tr key={m.userId}>
              <Td>{m.firstName} {m.lastName}</Td><Td><span dir="ltr">{m.phone}</span></Td>
              <Td>{m.roles.split(",").map((r) => t(`role.${r}`)).join(", ")}</Td>
              <Td><StatusBadge status={m.userStatus === "INVITED" ? "PENDING" : "ACTIVE"} /></Td>
              <Td className="text-end">
                {!m.roles.includes("_OWNER") && (<>
                  <Button size="sm" variant="ghost" onClick={() => reissue.run(m)}>{t("staff.newPin")}</Button>
                  <Button size="sm" variant="ghost" onClick={() => remove.run(m.userId)}>{t("common.delete")}</Button>
                </>)}
              </Td>
            </tr>
          ))}
        </Table>
      )}
      <Modal open={open} onClose={() => setOpen(false)} title={t("staff.add")}>
        <form onSubmit={(e) => { e.preventDefault(); void invite.run(); }} className="space-y-3">
          <Field label={t("form.name")}><Input value={form.firstName} onChange={(e) => setForm({ ...form, firstName: e.target.value })} required maxLength={100} /></Field>
          <Field label={t("register.phone")}><PhoneInput value={form.phone} onValue={(phone) => setForm({ ...form, phone })} required /></Field>
          <Field label={t("staff.role")}><Select value={form.role} onChange={(e) => setForm({ ...form, role: e.target.value })}>{roles.map((r) => <option key={r} value={r}>{t(`role.${r}`)}</option>)}</Select></Field>
          <ErrorText error={invite.error} />
          <Button type="submit" loading={invite.loading} className="w-full">{t("staff.create")}</Button>
        </form>
      </Modal>
      <Card className="mt-6 text-sm text-slate-600">{t("staff.explain")}</Card>
    </>
  );
}
