"use client";

import { localPhone } from "@/lib/phone";
import { useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Alert, Avatar, Button, Empty, ErrorText, Field, Input, Loading, PageHeader, SecretBox, StatusBadge, Table, Td, cx } from "../ui";
import { Icon } from "../icons";
import { ConfirmDialog, Menu, Sheet } from "../overlay";
import { useToast } from "../toast";
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
      <p className="mb-3 text-[13px] text-muted">{t("staff.accessHint")}</p>
      <div className="mb-2 flex flex-wrap gap-2">
        {(["HIDDEN", "READ", "WRITE"] as Level[]).map((l) => <Button key={l} type="button" size="sm" variant="ghost" onClick={() => all(l)}>{t(`staff.all.${l}`)}</Button>)}
      </div>
      <ul className="divide-y divide-line rounded-[14px] ring-1 ring-line">
        {screens.map((s) => (
          <li key={s.key} className="flex flex-wrap items-center justify-between gap-2 px-3 py-2">
            <span className="text-[13.5px] font-semibold text-ink">{t(`screen.${s.key}`)}</span>
            <div role="radiogroup" aria-label={t(`screen.${s.key}`)} className="inline-flex overflow-hidden rounded-[10px] ring-1 ring-line-strong text-xs font-semibold">
              {(["HIDDEN", "READ", "WRITE"] as Level[]).filter((l) => l !== "WRITE" || s.writable).map((l) => {
                const on = (value[s.key] ?? "HIDDEN") === l;
                return <button key={l} type="button" role="radio" aria-checked={on} onClick={() => set(s.key, l)} className={`px-3 py-2 transition ${on ? (l === "HIDDEN" ? "bg-ink text-surface" : l === "READ" ? "bg-amber-500 text-white" : "bg-brand text-white") : "bg-surface text-muted hover:bg-surface-2"}`}>{t(`access.${l}`)}</button>;
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
  const toast = useToast();
  const [confirm, setConfirm] = useState<Member | null>(null);
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
    toast(t("staff.create"));
  });
  const saveAccess = useAction(async () => {
    await api(`tenant/members/${editing!.userId}/access`, { method: "PUT", body: { access } });
    setEditing(null);
    await reload();
    toast(t("staff.saveAccess"));
  });
  const chosen = Object.values(access).some((l) => l !== "HIDDEN");
  const reissue = useAction(async (m: Member) => {
    const r = await api<{ activationPin: string }>(`tenant/members/${m.userId}/activation-pin`, { body: {} });
    setPin({ pin: r.activationPin, name: m.firstName });
  });
  const remove = useAction(async (id: string) => { await api(`tenant/members/${id}`, { method: "DELETE" }); setConfirm(null); await reload(); toast(t("common.delete")); });

  const accessText = (m: Member) => m.roles.includes("_OWNER") ? t("staff.fullAccess") : Object.keys(m.screens ?? {}).length ? Object.entries(m.screens ?? {}).map(([k, l]) => `${t(`screen.${k}`)} (${t(`access.${l}`)})`).join("، ") : t("staff.noScreens");
  const actions = (m: Member) => m.roles.includes("_OWNER") ? null : (
    <Menu label={m.firstName} trigger={({ toggle }) => <button type="button" onClick={toggle} aria-label={m.firstName} className="grid h-9 w-9 place-items-center rounded-[10px] text-muted transition hover:bg-surface-2 hover:text-ink pointer-coarse:h-11 pointer-coarse:w-11"><Icon name="sliders" className="h-[18px] w-[18px]" /></button>}
      items={[
        { label: t("staff.editAccess"), icon: "shield", onSelect: () => { setAccess(m.screens ?? {}); setEditing(m); } },
        { label: t("staff.newPin"), icon: "lock", onSelect: () => void reissue.run(m) },
        "divider",
        { label: t("common.delete"), icon: "close", danger: true, onSelect: () => setConfirm(m) },
      ]} />
  );
  const footer = (on: () => void, loading: boolean, label: string, disabled?: boolean) => <Button onClick={on} loading={loading} disabled={disabled} className="w-full">{label}</Button>;

  return (
    <>
      <PageHeader eyebrow={t("nav.settings")} title={t("staff.title")} subtitle={t("staff.subtitle")} actions={<Button onClick={() => { setAccess({}); setOpen(true); }}><Icon name="plus" className="h-4 w-4" />{t("staff.add")}</Button>} />
      {pin && <div className="mb-5 max-w-xl"><SecretBox label={t("staff.pinFor", { name: pin.name })} value={pin.pin} /><p className="mt-2 text-[13px] text-muted">{t("staff.pinHow")}</p></div>}
      <ErrorText error={error ?? reissue.error ?? remove.error} />
      {loading && !data ? <Loading variant="table" /> : data && data.length === 0 ? <Empty icon="users" action={<Button onClick={() => { setAccess({}); setOpen(true); }}>{t("staff.add")}</Button>}>{t("staff.explain")}</Empty> : (
        <Table head={[t("staff.name"), t("staff.phone"), t("staff.access"), t("admin.status"), ""]}>
          {data?.map((m) => (
            <tr key={m.userId}>
              <Td><span className="flex items-center gap-3"><Avatar name={`${m.firstName} ${m.lastName}`} className="h-8 w-8 text-xs" /><span className="font-semibold text-ink">{m.firstName} {m.lastName}</span></span></Td>
              <Td><span dir="ltr" className="tnum">{localPhone(m.phone)}</span></Td>
              <Td className="max-w-xs text-[13px] text-muted">{accessText(m)}</Td>
              <Td><StatusBadge status={m.userStatus === "INVITED" ? "PENDING" : "ACTIVE"} /></Td>
              <Td className="text-end">{actions(m)}</Td>
            </tr>
          ))}
        </Table>
      )}
      <div className="mt-5 max-w-3xl"><Alert tone="blue">{t("staff.explain")}</Alert></div>

      <Sheet open={open} onClose={() => setOpen(false)} title={t("staff.add")} width="30rem" footer={footer(() => void invite.run(), invite.loading, t("staff.create"), !chosen || !form.firstName || !form.phone)}>
        <form id="staff-invite" onSubmit={(e) => { e.preventDefault(); void invite.run(); }} className="space-y-4">
          <Field label={t("form.name")}><Input value={form.firstName} onChange={(e) => setForm({ ...form, firstName: e.target.value })} required maxLength={100} /></Field>
          <Field label={t("register.phone")}><PhoneInput value={form.phone} onValue={(phone) => setForm({ ...form, phone })} required /></Field>
          <AccessPicker screens={screens} value={access} onChange={setAccess} />
          {!chosen && <p className={cx("text-[13px] text-amber-700")}>{t("staff.pickOne")}</p>}
          <ErrorText error={invite.error} />
        </form>
      </Sheet>
      <Sheet open={!!editing} onClose={() => setEditing(null)} title={`${t("staff.editAccess")}: ${editing?.firstName ?? ""}`} width="30rem" footer={footer(() => void saveAccess.run(), saveAccess.loading, t("staff.saveAccess"))}>
        <div className="space-y-4">
          <AccessPicker screens={screens} value={access} onChange={setAccess} />
          <ErrorText error={saveAccess.error} />
        </div>
      </Sheet>
      <ConfirmDialog open={!!confirm} onClose={() => setConfirm(null)} danger loading={remove.loading} onConfirm={() => confirm && void remove.run(confirm.userId)}
        title={t("common.delete")} body={confirm ? `${confirm.firstName} ${confirm.lastName}` : undefined} confirmLabel={t("common.delete")} cancelLabel={t("common.cancel")} />
    </>
  );
}
