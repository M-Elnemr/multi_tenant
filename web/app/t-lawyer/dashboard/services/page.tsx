"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { fromMinor, money, toMinor } from "@/lib/format";
import type { Service } from "@/lib/lawyer";
import { useFieldErrors } from "@/lib/use-field-errors";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { ConfirmDialog, Menu, Switch } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { BRASS_LIGHT, DotsButton, Eyebrow, NAVY, Rise } from "@/components/lawyer/kit";
import { Badge, Button, Empty, ErrorText, Field, Input, Loading, Modal, PageHeader, Select, Textarea, cx } from "@/components/ui";

const MODE_ICON = { OFFICE: "building", ONLINE: "video", PHONE: "phone" } as const;

export default function Services() {
  const { t, locale, currency } = useI18n();
  const toast = useToast();
  const { canWrite } = useMe();
  const write = canWrite("settings");
  const { data, loading, error, reload } = useApi<Service[]>("lawyer/services");
  const [edit, setEdit] = useState<Service | "new" | null>(null);
  const [confirm, setConfirm] = useState<Service | null>(null);
  const del = useAction(async (id: string) => { await api(`lawyer/services/${id}`, { method: "DELETE" }); await reload(); setConfirm(null); toast(t("lw.toast.deleted")); });
  return (
    <div className="space-y-4">
      <PageHeader eyebrow={<Eyebrow>{t("lw.eb.setup")}</Eyebrow>} title={t("nav.services")} subtitle={t("lw.svc.subtitle")} actions={write && <Button onClick={() => setEdit("new")}><Icon name="plus" className="h-4 w-4" />{t("lw.svc.new")}</Button>} />
      <ErrorText error={error ?? del.error} />
      {loading && !data ? <Loading variant="cards" /> : data && data.length === 0 ? (
        <Empty icon="services" title={t("lw.svc.emptyTitle")} action={write ? <Button onClick={() => setEdit("new")}><Icon name="plus" className="h-4 w-4" />{t("lw.svc.new")}</Button> : undefined}>{t("lw.svc.subtitle")}</Empty>
      ) : (
        <Rise className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
          {data?.map((s) => (
            <article key={s.id} className={cx("flex flex-col rounded-[18px] bg-surface p-5 shadow-card", !s.isActive && "opacity-70")}>
              <div className="flex items-start justify-between gap-2">
                <span className="grid h-11 w-11 place-items-center rounded-[12px]" style={{ background: NAVY, color: BRASS_LIGHT }}><Icon name={MODE_ICON[s.mode]} className="h-5 w-5" /></span>
                <div className="flex items-center gap-1">{!s.isActive && <Badge>{t("lw.svc.hidden")}</Badge>}
                  {write && <Menu label={t("common.more")} items={[{ label: t("offer.edit"), icon: "edit", onSelect: () => setEdit(s) }, "divider", { label: t("offer.delete"), icon: "close", danger: true, onSelect: () => setConfirm(s) }]} trigger={({ toggle, open }) => <DotsButton onClick={toggle} open={open} label={t("common.more")} />} />}</div>
              </div>
              <p className="mt-3 font-display text-[17px] font-semibold text-ink">{s.name}</p>
              {s.description && <p className="mt-1 line-clamp-3 text-[13px] leading-relaxed text-muted">{s.description}</p>}
              <div className="mt-auto flex flex-wrap items-center gap-2 pt-4 text-[13px]">
                <span className="inline-flex items-center gap-1 rounded-full bg-surface-2 px-2.5 py-1 font-semibold text-ink-2 ring-1 ring-line"><Icon name="clock" className="h-3.5 w-3.5" />{t("lw.minutes", { n: s.durationMinutes })}</span>
                <span className="rounded-full px-2.5 py-1 font-semibold text-ink tnum" style={{ background: "color-mix(in srgb, #b08d57 16%, transparent)" }}>{s.priceMinor == null ? t("lw.svc.noPrice") : s.priceMinor === 0 ? t("lw.svc.free") : money(s.priceMinor, currency, locale)}</span>
                <span className="text-xs text-muted">{t(`lw.mode.${s.mode}`)}</span>
              </div>
            </article>
          ))}
        </Rise>
      )}
      {edit && <ServiceModal s={edit === "new" ? null : edit} onClose={() => setEdit(null)} onSaved={async () => { setEdit(null); toast(t("common.saved")); await reload(); }} />}
      <ConfirmDialog open={!!confirm} onClose={() => setConfirm(null)} onConfirm={() => confirm && void del.run(confirm.id)} loading={del.loading} danger title={t("lw.confirm.deleteTitle")} body={confirm?.name} confirmLabel={t("offer.delete")} cancelLabel={t("common.cancel")} />
    </div>
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
          <Field label={t("lw.svc.mode")}><Select value={f.mode} onChange={(e) => setF({ ...f, mode: e.target.value as Service["mode"] })}>{(["OFFICE", "ONLINE", "PHONE"] as const).map((m) => <option key={m} value={m}>{t(`lw.mode.${m}`)}</option>)}</Select></Field>
        </div>
        <div className="flex items-center gap-3 rounded-[12px] bg-surface-2 px-3.5 py-3 text-[13px] font-semibold text-ink-2 ring-1 ring-line"><Switch checked={f.active} onChange={(v) => setF({ ...f, active: v })} label={t("lw.svc.showOnSite")} />{t("lw.svc.showOnSite")}</div>
        {!fe.any && <ErrorText error={save.error} />}
        <Button type="submit" className="w-full" loading={save.loading}>{t("common.save")}</Button>
      </form>
    </Modal>
  );
}
