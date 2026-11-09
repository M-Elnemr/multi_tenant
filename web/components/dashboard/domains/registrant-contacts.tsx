"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "../../hooks";
import { useI18n } from "../../i18n-provider";
import { Button, ErrorText, Field, Input, Select } from "../../ui";
import type { RegistrantContact, RegistrantContactInput } from "./types";

/** The tenant's saved registrant (WHOIS) contacts: fetched once, reused across every purchase/renewal. */
export function useRegistrantContacts() {
  const list = useApi<RegistrantContact[]>("store/domains/registrant-contacts");
  const create = useAction(async (body: RegistrantContactInput) => {
    const c = await api<RegistrantContact>("store/domains/registrant-contacts", { body });
    await list.reload();
    return c;
  });
  return { ...list, create };
}

const EMPTY: RegistrantContactInput = { name: "", org: "", email: "", phone: "", country: "", address: "" };

/** Pick a saved registrant contact, or fill in a new one (shown by default when there are none yet); selects the new one on save. */
export function RegistrantContactPicker({ contacts, value, onChange }: {
  contacts: ReturnType<typeof useRegistrantContacts>; value: string | null; onChange: (id: string) => void;
}) {
  const { t } = useI18n();
  const list = contacts.data ?? [];
  const [showForm, setShowForm] = useState(list.length === 0);
  const [f, setF] = useState<RegistrantContactInput>(EMPTY);
  const emailOk = /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(f.email.trim());
  const valid = f.name.trim().length >= 2 && emailOk && f.phone.trim().length >= 6 && f.country.trim().length >= 2 && f.address.trim().length >= 5;

  const save = async () => {
    const c = await contacts.create.run({ ...f, org: f.org?.trim() || undefined });
    if (c) { onChange(c.id); setF(EMPTY); setShowForm(false); }
  };

  return (
    <div className="space-y-3">
      {list.length > 0 && !showForm && (
        <>
          <Field label={t("domainBuy.contact.pick")}>
            <Select value={value ?? ""} onChange={(e) => onChange(e.target.value)}>
              <option value="" disabled>{t("domainBuy.contact.choose")}</option>
              {list.map((c) => <option key={c.id} value={c.id}>{c.name} · {c.email}</option>)}
            </Select>
          </Field>
          <button type="button" onClick={() => setShowForm(true)} className="text-[13px] font-semibold text-brand hover:underline">{t("domainBuy.contact.addNew")}</button>
        </>
      )}
      {showForm && (
        <div className="space-y-3 rounded-[14px] bg-canvas p-4 ring-1 ring-line">
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label={t("domainBuy.contact.name")}><Input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} /></Field>
            <Field label={t("domainBuy.contact.org")} hint={t("domainBuy.contact.orgHint")}><Input value={f.org} onChange={(e) => setF({ ...f, org: e.target.value })} /></Field>
            <Field label={t("domainBuy.contact.email")}><Input type="email" dir="ltr" value={f.email} onChange={(e) => setF({ ...f, email: e.target.value })} /></Field>
            <Field label={t("domainBuy.contact.phone")}><Input dir="ltr" value={f.phone} onChange={(e) => setF({ ...f, phone: e.target.value })} /></Field>
            <Field label={t("domainBuy.contact.country")}><Input value={f.country} onChange={(e) => setF({ ...f, country: e.target.value })} /></Field>
          </div>
          <Field label={t("domainBuy.contact.address")}><Input value={f.address} onChange={(e) => setF({ ...f, address: e.target.value })} /></Field>
          <ErrorText error={contacts.create.error} />
          <div className="flex justify-end gap-2">
            {list.length > 0 && <Button type="button" variant="ghost" size="sm" onClick={() => setShowForm(false)}>{t("common.cancel")}</Button>}
            <Button type="button" size="sm" loading={contacts.create.loading} disabled={!valid} onClick={() => void save()}>{t("domainBuy.contact.save")}</Button>
          </div>
        </div>
      )}
    </div>
  );
}
