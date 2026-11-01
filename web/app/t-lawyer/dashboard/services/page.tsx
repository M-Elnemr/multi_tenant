"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { fromMinor, money, toMinor } from "@/lib/format";
import type { Service } from "@/lib/lawyer";
import { useFieldErrors } from "@/lib/use-field-errors";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { Badge, Button, ErrorText, Field, Input, Loading, Modal, PageHeader, Select, Textarea } from "@/components/ui";

const MODE_ICON = { OFFICE: "🏛", ONLINE: "💻", PHONE: "📞" } as const;

export default function Services() {
  const { t, locale, currency } = useI18n();
  const { canWrite } = useMe();
  const { data, loading, error, reload } = useApi<Service[]>("lawyer/services");
  const [edit, setEdit] = useState<Service | "new" | null>(null);
  const del = useAction(async (id: string) => { await api(`lawyer/services/${id}`, { method: "DELETE" }); await reload(); });
  return (
    <>
      <PageHeader title={t("nav.services")} subtitle={t("lw.svc.subtitle")} actions={canWrite("settings") && <Button onClick={() => setEdit("new")}><Icon name="plus" className="h-4 w-4" />{t("lw.svc.new")}</Button>} />
      <ErrorText error={error ?? del.error} />
      {loading && !data ? <Loading /> : (
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
          {data?.map((s) => (
            <div key={s.id} className={`rounded-2xl bg-white p-5 shadow-card ${s.isActive ? "" : "opacity-60"}`}>
              <div className="flex items-start justify-between gap-2"><span className="text-3xl">{MODE_ICON[s.mode]}</span>{!s.isActive && <Badge>{t("lw.svc.hidden")}</Badge>}</div>
              <p className="mt-2 text-lg font-bold text-ink">{s.name}</p>
              {s.description && <p className="mt-1 text-sm text-slate-500">{s.description}</p>}
              <p className="mt-3 flex flex-wrap gap-2 text-sm"><span className="rounded-full bg-slate-100 px-2.5 py-0.5 font-semibold">⏱ {t("lw.minutes", { n: s.durationMinutes })}</span><span className="rounded-full bg-emerald-50 px-2.5 py-0.5 font-semibold text-emerald-700">{s.priceMinor == null ? t("lw.svc.noPrice") : s.priceMinor === 0 ? t("lw.svc.free") : money(s.priceMinor, currency, locale)}</span></p>
              {canWrite("settings") && <div className="mt-4 flex gap-1"><Button size="sm" variant="secondary" onClick={() => setEdit(s)}><Icon name="edit" className="h-3.5 w-3.5" />{t("offer.edit")}</Button><Button size="sm" variant="ghost" className="!text-red-600" onClick={() => del.run(s.id)}>{t("offer.delete")}</Button></div>}
            </div>
          ))}
        </div>
      )}
      {edit && <ServiceModal s={edit === "new" ? null : edit} onClose={() => setEdit(null)} onSaved={async () => { setEdit(null); await reload(); }} />}
    </>
  );
}

function ServiceModal({ s, onClose, onSaved }: { s: Service | null; onClose: () => void; onSaved: () => void }) {
  const { t, currency } = useI18n();
  const fe = useFieldErrors();
  const [f, setF] = useState({ name: s?.name ?? "", description: s?.description ?? "", durationMinutes: String(s?.durationMinutes ?? 30), price: s?.priceMinor == null ? "" : fromMinor(s.priceMinor), mode: s?.mode ?? "OFFICE", active: s?.isActive ?? true });
  const save = useAction(async () => {
    if (!fe.check({ name: !f.name.trim() && t("srv.Name the service") })) return;
    const body = { name: f.name, description: f.description, durationMinutes: Number(f.durationMinutes), priceMinor: f.price.trim() === "" ? null : toMinor(f.price), mode: f.mode, active: f.active };
    try { if (s) await api(`lawyer/services/${s.id}`, { method: "PUT", body }); else await api("lawyer/services", { body }); } catch (e) { if (!fe.fromError(e)) throw e; return; }
    onSaved();
  });
  return (
    <Modal open onClose={onClose} title={s ? t("offer.edit") : t("lw.svc.new")}>
      <form onSubmit={(e) => { e.preventDefault(); void save.run(); }} className="space-y-3">
        <Field name="name" error={fe.err("name")} label={t("lw.svc.name")}><Input value={f.name} onChange={(e) => { setF({ ...f, name: e.target.value }); fe.clear("name"); }} placeholder={t("lw.svc.namePh")} /></Field>
        <Field label={t("lw.svc.description")}><Textarea rows={2} value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} /></Field>
        <div className="grid gap-3 sm:grid-cols-3">
          <Field name="durationMinutes" error={fe.err("durationMinutes")} label={t("lw.step.duration")}><Select value={f.durationMinutes} onChange={(e) => setF({ ...f, durationMinutes: e.target.value })}>{["15", "30", "45", "60", "90", "120"].map((m) => <option key={m} value={m}>{t("lw.minutes", { n: m })}</option>)}</Select></Field>
          <Field name="priceMinor" error={fe.err("priceMinor")} label={t("lw.svc.price", { c: currency })} hint={t("lw.svc.priceHint")}><Input dir="ltr" inputMode="decimal" value={f.price} onChange={(e) => setF({ ...f, price: e.target.value })} /></Field>
          <Field label={t("lw.svc.mode")}><Select value={f.mode} onChange={(e) => setF({ ...f, mode: e.target.value as Service["mode"] })}>{(["OFFICE", "ONLINE", "PHONE"] as const).map((m) => <option key={m} value={m}>{MODE_ICON[m]} {t(`lw.mode.${m}`)}</option>)}</Select></Field>
        </div>
        <label className="flex items-center gap-2 text-sm font-semibold"><input type="checkbox" className="h-5 w-5 accent-[var(--brand)]" checked={f.active} onChange={(e) => setF({ ...f, active: e.target.checked })} />{t("lw.svc.showOnSite")}</label>
        {!fe.any && <ErrorText error={save.error} />}
        <Button type="submit" className="w-full" loading={save.loading}>{t("common.save")}</Button>
      </form>
    </Modal>
  );
}
