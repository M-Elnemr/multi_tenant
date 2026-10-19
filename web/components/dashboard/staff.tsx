"use client";

import { localPhone } from "@/lib/phone";
import { useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Button, Card, ErrorText, Field, Input, Loading, Modal, PageHeader, SecretBox, StatusBadge, Table, Td } from "../ui";
import { PhoneInput } from "@/components/inputs";

type Level = "HIDDEN" | "READ" | "WRITE";
type Access = Record<string, Level>;
type Member = { userId: string; firstName: string; lastName: string; phone: string; roles: string; userStatus: string; screens?: Record<string, "READ" | "WRITE"> };
type ScreenDef = { key: string; writable: boolean };

/** Hide / read only / read & write for every screen the owner can allow; screens that cannot be written (reports) offer only the first two. */
function AccessPicker({ screens, value, onChange }: { screens: ScreenDef[]; value: Access; onChange: (a: Access) => void }) {
  const { t } = useI18n();
  const set = (k: string, l: Level) => onChange({ ...value, [k]: l });
  const all = (l: Level) => onChange(Object.fromEntries(screens.map((s) => [s.key, l === "WRITE" && !s.writable ? "READ" : l])));
  return (
    <div>
      <p className="mb-2 text-sm text-slate-600">{t("staff.accessHint")}</p>
      <div className="mb-2 flex flex-wrap gap-2">
        {(["HIDDEN", "READ", "WRITE"] as Level[]).map((l) => <Button key={l} type="button" size="sm" variant="ghost" onClick={() => all(l)}>{t(`staff.all.${l}`)}</Button>)}
      </div>
      <ul className="divide-y divide-slate-100 rounded-xl border border-slate-200">
        {screens.map((s) => (
          <li key={s.key} className="flex flex-wrap items-center justify-between gap-2 px-3 py-2">
            <span className="text-sm font-semibold">{t(`screen.${s.key}`)}</span>
            <div role="radiogroup" aria-label={t(`screen.${s.key}`)} className="inline-flex overflow-hidden rounded-lg border border-slate-200 text-xs font-semibold">
              {(["HIDDEN", "READ", "WRITE"] as Level[]).filter((l) => l !== "WRITE" || s.writable).map((l) => {
                const on = (value[s.key] ?? "HIDDEN") === l;
                return <button key={l} type="button" role="radio" aria-checked={on} onClick={() => set(s.key, l)} className={`px-3 py-1.5 transition ${on ? (l === "HIDDEN" ? "bg-slate-700 text-white" : l === "READ" ? "bg-amber-500 text-white" : "bg-brand text-white") : "bg-white text-slate-600 hover:bg-slate-50"}`}>{t(`access.${l}`)}</button>;
              })}
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** Add a doctor/receptionist/staff member: they get a one-time PIN to create their own password. No SMS needed. */
export default function StaffPage() {
  const { t } = useI18n();
  const { data, loading, reload, error } = useApi<Member[]>("tenant/members");
  const catalogue = useApi<ScreenDef[]>("tenant/members/screens");
  const screens = catalogue.data ?? [];
  const [open, setOpen] = useState(false);
  const [access, setAccess] = useState<Access>({});
  const [editing, setEditing] = useState<Member | null>(null);
  const [form, setForm] = useState({ firstName: "", lastName: "", phone: "", email: "" });
  const [pin, setPin] = useState<{ pin: string; name: string } | null>(null);
  const invite = useAction(async () => {
    const r = await api<{ activationPin?: string }>("tenant/members", { body: { ...form, access } });
    setPin(r.activationPin ? { pin: r.activationPin, name: form.firstName } : null);
    setOpen(false);
    setForm({ firstName: "", lastName: "", phone: "", email: "" });
    setAccess({});
    await reload();
  });
  const saveAccess = useAction(async () => {
    await api(`tenant/members/${editing!.userId}/access`, { method: "PUT", body: { access } });
    setEditing(null);
    await reload();
  });
  const chosen = Object.values(access).some((l) => l !== "HIDDEN");
  const reissue = useAction(async (m: Member) => {
    const r = await api<{ activationPin: string }>(`tenant/members/${m.userId}/activation-pin`, { body: {} });
    setPin({ pin: r.activationPin, name: m.firstName });
  });
  const remove = useAction(async (id: string) => { await api(`tenant/members/${id}`, { method: "DELETE" }); await reload(); });

  return (
    <>
      <PageHeader title={t("staff.title")} subtitle={t("staff.subtitle")} actions={<Button onClick={() => { setAccess({}); setOpen(true); }}>{t("staff.add")}</Button>} />
      {pin && <div className="mb-4"><SecretBox label={t("staff.pinFor", { name: pin.name })} value={pin.pin} /><p className="mt-2 text-sm text-slate-600">{t("staff.pinHow")}</p></div>}
      <ErrorText error={error ?? reissue.error ?? remove.error} />
      {loading && !data ? <Loading /> : (
        <Table head={[t("staff.name"), t("staff.phone"), t("staff.access"), t("admin.status"), ""]}>
          {data?.map((m) => (
            <tr key={m.userId}>
              <Td>{m.firstName} {m.lastName}</Td><Td><span dir="ltr">{localPhone(m.phone)}</span></Td>
              <Td>{m.roles.includes("_OWNER") ? t("staff.fullAccess") : Object.keys(m.screens ?? {}).length ? Object.entries(m.screens ?? {}).map(([k, l]) => `${t(`screen.${k}`)} (${t(`access.${l}`)})`).join("، ") : t("staff.noScreens")}</Td>
              <Td><StatusBadge status={m.userStatus === "INVITED" ? "PENDING" : "ACTIVE"} /></Td>
              <Td className="text-end">
                {!m.roles.includes("_OWNER") && (<>
                  <Button size="sm" variant="ghost" onClick={() => { setAccess(m.screens ?? {}); setEditing(m); }}>{t("staff.editAccess")}</Button>
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
          <AccessPicker screens={screens} value={access} onChange={setAccess} />
          {!chosen && <p className="text-sm text-amber-700">{t("staff.pickOne")}</p>}
          <ErrorText error={invite.error} />
          <Button type="submit" loading={invite.loading} disabled={!chosen} className="w-full">{t("staff.create")}</Button>
        </form>
      </Modal>
      <Modal open={!!editing} onClose={() => setEditing(null)} title={`${t("staff.editAccess")}: ${editing?.firstName ?? ""}`}>
        <div className="space-y-3">
          <AccessPicker screens={screens} value={access} onChange={setAccess} />
          <ErrorText error={saveAccess.error} />
          <Button onClick={() => void saveAccess.run()} loading={saveAccess.loading} className="w-full">{t("staff.saveAccess")}</Button>
        </div>
      </Modal>
      <Card className="mt-6 text-sm text-slate-600">{t("staff.explain")}</Card>
    </>
  );
}
