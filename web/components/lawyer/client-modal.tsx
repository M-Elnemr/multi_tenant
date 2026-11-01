"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import type { Client } from "@/lib/lawyer";
import { useFieldErrors } from "@/lib/use-field-errors";
import { useAction } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Button, ErrorText, Field, Input, Modal, Textarea } from "../ui";

/** Add or edit a client (person or company). */
export function ClientModal({ initial, onClose, onSaved }: { initial?: Client; onClose: () => void; onSaved: (c: Client) => void }) {
  const { t } = useI18n();
  const fe = useFieldErrors();
  const [f, setF] = useState({ name: initial?.name ?? "", phone: initial?.phone ?? "", phone2: initial?.phone2 ?? "", email: initial?.email ?? "", nationalId: initial?.nationalId ?? "", address: initial?.address ?? "", kind: initial?.kind ?? "PERSON", notes: initial?.notes ?? "", tags: (initial?.tags ?? []).join("، ") });
  const set = (x: Partial<typeof f>) => { setF((o) => ({ ...o, ...x })); for (const k of Object.keys(x)) fe.clear(k); };
  const save = useAction(async () => {
    if (!fe.check({ name: f.name.trim().length < 2 && t("srv.Write the client's name") })) return;
    const body = { ...f, tags: f.tags.split(/[,،]/).map((x) => x.trim()).filter(Boolean) };
    try { onSaved(initial ? await api<Client>(`lawyer/clients/${initial.id}`, { method: "PUT", body }) : await api<Client>("lawyer/clients", { body })); }
    catch (e) { if (!fe.fromError(e)) throw e; }
  });
  return (
    <Modal open onClose={onClose} title={initial ? t("lw.client.edit") : t("lw.client.new")} wide>
      <form onSubmit={(e) => { e.preventDefault(); void save.run(); }} className="space-y-3">
        <div className="flex gap-2">{(["PERSON", "COMPANY"] as const).map((k) => <button key={k} type="button" onClick={() => set({ kind: k })} className={`chip ${f.kind === k ? "chip-on" : ""}`}>{k === "PERSON" ? "👤" : "🏢"} {t(`lw.client.kind.${k}`)}</button>)}</div>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field name="name" error={fe.err("name")} label={t("lw.client.name")}><Input value={f.name} onChange={(e) => set({ name: e.target.value })} /></Field>
          <Field name="phone" error={fe.err("phone")} label={t("lw.client.phone")}><Input dir="ltr" inputMode="tel" value={f.phone} onChange={(e) => set({ phone: e.target.value })} /></Field>
          <Field name="phone2" error={fe.err("phone2")} label={t("lw.client.phone2")}><Input dir="ltr" inputMode="tel" value={f.phone2} onChange={(e) => set({ phone2: e.target.value })} /></Field>
          <Field name="email" error={fe.err("email")} label={t("lw.client.email")}><Input dir="ltr" type="email" value={f.email} onChange={(e) => set({ email: e.target.value })} /></Field>
          <Field name="nationalId" error={fe.err("nationalId")} label={f.kind === "COMPANY" ? t("lw.client.crNo") : t("lw.client.nationalId")}><Input dir="ltr" value={f.nationalId} onChange={(e) => set({ nationalId: e.target.value })} /></Field>
          <Field label={t("lw.client.tags")} hint={t("lw.client.tagsHint")}><Input value={f.tags} onChange={(e) => set({ tags: e.target.value })} /></Field>
        </div>
        <Field label={t("lw.client.address")}><Input value={f.address} onChange={(e) => set({ address: e.target.value })} /></Field>
        <Field label={t("lw.client.notes")}><Textarea rows={3} value={f.notes} onChange={(e) => set({ notes: e.target.value })} /></Field>
        {fe.any ? <p className="rounded-xl bg-red-50 px-4 py-3 text-sm font-medium text-red-700">{t("form.fixMarked")}</p> : <ErrorText error={save.error} />}
        <Button type="submit" className="w-full" loading={save.loading}>{t("common.save")}</Button>
      </form>
    </Modal>
  );
}
