"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { fromMinor, money, toMinor } from "@/lib/format";
import { useAction } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { ConfirmDialog, Sheet, Switch } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { Badge, Button, Empty, ErrorText, Field, Input, Textarea } from "@/components/ui";
import type { Service } from "./common";

type Form = { name: string; description: string; durationMinutes: string; bufferMinutes: string; price: string; active: boolean };
const BLANK: Form = { name: "", description: "", durationMinutes: "60", bufferMinutes: "0", price: "", active: true };

function Editor({ svc, open, onClose, onDone }: { svc: Service | null; open: boolean; onClose: () => void; onDone: () => void }) {
  const { t, currency } = useI18n();
  const toast = useToast();
  const [f, setF] = useState<Form>(svc ? { name: svc.name, description: svc.description ?? "", durationMinutes: String(svc.durationMinutes), bufferMinutes: String(svc.bufferMinutes), price: fromMinor(svc.priceMinor), active: svc.isActive } : BLANK);
  const save = useAction(async () => {
    const body = { name: f.name.trim(), description: f.description.trim(), durationMinutes: Number(f.durationMinutes), bufferMinutes: Number(f.bufferMinutes || 0), priceMinor: f.price.trim() ? toMinor(f.price) : 0, active: f.active };
    if (svc) await api(`store/services/${svc.id}`, { method: "PATCH", body }); else await api("store/services", { body });
    toast(t("common.saved"));
    onDone();
  });
  const ok = f.name.trim() && Number(f.durationMinutes) >= 5;
  return (
    <Sheet open={open} onClose={onClose} title={svc ? t("bk.svc.edit") : t("bk.svc.add")} footer={<div className="flex justify-end gap-2"><Button variant="secondary" onClick={onClose}>{t("common.cancel")}</Button><Button variant="ink" loading={save.loading} disabled={!ok} onClick={() => void save.run()}>{t("common.save")}</Button></div>}>
      <form className="space-y-4" onSubmit={(e) => { e.preventDefault(); if (ok) void save.run(); }}>
        <Field label={t("bk.svc.name")}><Input value={f.name} maxLength={160} onChange={(e) => setF({ ...f, name: e.target.value })} placeholder={t("bk.svc.namePh")} /></Field>
        <Field label={`${t("bk.svc.desc")} (${t("ch.optional")})`}><Textarea value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} /></Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label={t("bk.svc.duration")}><Input type="number" inputMode="numeric" min={5} max={720} dir="ltr" value={f.durationMinutes} onChange={(e) => setF({ ...f, durationMinutes: e.target.value })} /></Field>
          <Field label={t("bk.svc.buffer")} hint={t("bk.svc.bufferHint")}><Input type="number" inputMode="numeric" min={0} max={240} dir="ltr" value={f.bufferMinutes} onChange={(e) => setF({ ...f, bufferMinutes: e.target.value })} /></Field>
        </div>
        <Field label={`${t("bk.price")} (${currency})`}><Input type="text" inputMode="decimal" dir="ltr" value={f.price} onChange={(e) => setF({ ...f, price: e.target.value })} placeholder="0" /></Field>
        <div className="flex items-center justify-between rounded-[12px] bg-canvas p-3 ring-1 ring-line"><span className="text-sm font-semibold text-ink-2">{t("bk.svc.active")}</span><Switch checked={f.active} onChange={(v) => setF({ ...f, active: v })} label={t("bk.svc.active")} /></div>
        <ErrorText error={save.error} />
      </form>
    </Sheet>
  );
}

export function Services({ services, canManage, onChanged }: { services: Service[]; canManage: boolean; onChanged: () => void }) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const [edit, setEdit] = useState<Service | "new" | null>(null);
  const [del, setDel] = useState<Service | null>(null);
  const remove = useAction(async (id: string) => { await api(`store/services/${id}`, { method: "DELETE" }); toast(t("common.saved")); setDel(null); onChanged(); });
  return (
    <div className="space-y-4">
      {canManage && <div className="flex justify-end"><Button variant="ink" onClick={() => setEdit("new")}><Icon name="plus" className="h-4 w-4" />{t("bk.svc.add")}</Button></div>}
      {services.length === 0 ? <Empty icon="services" title={t("bk.svc.none.title")} action={canManage ? <Button variant="ink" onClick={() => setEdit("new")}>{t("bk.svc.add")}</Button> : undefined}>{t("bk.svc.none.body")}</Empty> : (
        <ul className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
          {services.map((s) => (
            <li key={s.id} className={`flex flex-col gap-3 rounded-[16px] bg-surface p-4 ring-1 ring-line ${s.isActive ? "" : "opacity-60"}`}>
              <div className="flex items-start justify-between gap-2"><p className="min-w-0 font-display text-[15px] font-semibold text-ink">{s.name}</p>{!s.isActive && <Badge tone="slate">{t("bk.svc.off")}</Badge>}</div>
              {s.description && <p className="line-clamp-2 text-[13px] text-muted">{s.description}</p>}
              <div className="mt-auto flex flex-wrap items-center gap-x-4 gap-y-1 text-[13px] text-ink-2">
                <span className="inline-flex items-center gap-1.5"><Icon name="clock" className="h-4 w-4 text-muted" /><span className="tnum">{s.durationMinutes} {t("bk.min")}</span></span>
                <span className="font-semibold tnum text-ink">{money(s.priceMinor, s.currency, locale)}</span>
              </div>
              {canManage && <div className="flex gap-2"><Button size="sm" variant="secondary" onClick={() => setEdit(s)}><Icon name="edit" className="h-4 w-4" />{t("common.edit")}</Button><Button size="sm" variant="ghost" className="text-red-600" onClick={() => setDel(s)}>{t("common.delete")}</Button></div>}
            </li>
          ))}
        </ul>
      )}
      {edit && <Editor key={edit === "new" ? "new" : edit.id} svc={edit === "new" ? null : edit} open onClose={() => setEdit(null)} onDone={() => { setEdit(null); onChanged(); }} />}
      <ConfirmDialog open={!!del} onClose={() => setDel(null)} danger loading={remove.loading} onConfirm={() => del && void remove.run(del.id)} title={t("bk.svc.delete")} body={del ? t("bk.svc.deleteBody", { name: del.name }) : undefined} confirmLabel={t("common.delete")} cancelLabel={t("common.cancel")} />
    </div>
  );
}
