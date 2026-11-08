"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { money, toMinor } from "@/lib/format";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { Menu, Sheet, Switch } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { VisitTypeBadge } from "@/components/visit-type";
import { Button, Empty, ErrorText, Field, Input, Loading, PageHeader, Select } from "@/components/ui";
import { MoreTrigger, Paper, Pill, SageTile } from "@/components/clinic/dash";

type Service = { id: string; name: string; description?: string; durationMinutes: number; priceMinor?: number; currency: string; isActive: boolean; visitType?: string | null };

export default function Services() {
  const { t, locale } = useI18n();
  const toast = useToast();
  const { can } = useMe();
  const { data, loading, reload, error } = useApi<Service[]>("clinic/services");
  const [open, setOpen] = useState(false);
  const [f, setF] = useState({ name: "", durationMinutes: "30", price: "", visitType: "" });
  const add = useAction(async () => {
    await api("clinic/services", { body: { name: f.name, durationMinutes: Number(f.durationMinutes), priceMinor: f.price ? toMinor(f.price) : undefined, visitType: f.visitType || undefined } });
    setF({ name: "", durationMinutes: "30", price: "", visitType: "" });
    setOpen(false);
    await reload();
    toast(t("common.saved"));
  });
  const toggle = useAction(async (s: Service) => { await api(`clinic/services/${s.id}/active`, { method: "PUT", body: { active: !s.isActive } }); await reload(); toast(s.isActive ? t("common.hidden") : t("status.ACTIVE"), { tone: "info" }); });
  const manage = can("schedule.manage");
  const addBtn = manage ? <Button onClick={() => setOpen(true)}><Icon name="plus" className="h-4 w-4" />{t("common.add")}</Button> : undefined;
  return (
    <>
      <PageHeader eyebrow={t("navgrp.setup")} title={t("nav.services")} subtitle={t("services.hint")} actions={addBtn} />
      <div className="space-y-4">
        <ErrorText error={error ?? toggle.error} />
        {loading && !data ? <Loading variant="list" /> : (data?.length ?? 0) === 0 ? (
          <Empty icon="services" title={t("nav.services")} action={addBtn}>{t("services.hint")}</Empty>
        ) : (
          <Paper className="overflow-hidden">
            <ul className="divide-y divide-line">
              {data?.map((s) => (
                <li key={s.id} className={`flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3.5 transition hover:bg-surface-2/70 sm:px-5 ${s.isActive ? "" : "opacity-65"}`}>
                  <SageTile icon="services" />
                  <div className="min-w-0 flex-1 basis-48">
                    <p className="flex flex-wrap items-center gap-2 font-semibold text-ink">{s.name} <VisitTypeBadge type={s.visitType} /></p>
                    <p className="mt-0.5 flex items-center gap-1.5 text-[13px] text-muted"><Icon name="clock" className="h-3.5 w-3.5" /><span className="tnum">{s.durationMinutes} {t("clinic.minutes")}</span></p>
                  </div>
                  <span className="font-display text-[16px] font-semibold text-ink tnum">{s.priceMinor ? money(s.priceMinor, s.currency, locale) : "-"}</span>
                  <Pill tone={s.isActive ? "sage" : "slate"}>{s.isActive ? t("status.ACTIVE") : t("common.hidden")}</Pill>
                  {manage && (
                    <span className="flex items-center gap-2">
                      <Switch checked={s.isActive} onChange={() => toggle.run(s)} label={s.isActive ? t("common.hide") : t("common.show")} size="sm" />
                      <Menu label={t("cld.moreActions")} items={[{ label: s.isActive ? t("common.hide") : t("common.show"), icon: "eye", onSelect: () => void toggle.run(s) }]} trigger={({ toggle: tg }) => <MoreTrigger toggle={tg} label={t("cld.moreActions")} />} />
                    </span>
                  )}
                </li>
              ))}
            </ul>
          </Paper>
        )}
      </div>

      <Sheet open={open} onClose={() => setOpen(false)} title={t("services.new")}
        footer={<><ErrorText error={add.error} /><Button className="mt-2 w-full" loading={add.loading} disabled={!f.name.trim()} onClick={() => add.run()}>{t("common.add")}</Button></>}>
        <form onSubmit={(e) => { e.preventDefault(); void add.run(); }} className="space-y-4">
          <Field label={t("products.name")}><Input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} required /></Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label={t("services.duration")}><Input type="number" min={5} value={f.durationMinutes} onChange={(e) => setF({ ...f, durationMinutes: e.target.value })} dir="ltr" required /></Field>
            <Field label={t("products.price")} hint={t("services.priceHint")}><Input value={f.price} onChange={(e) => setF({ ...f, price: e.target.value })} inputMode="decimal" dir="ltr" /></Field>
          </div>
          <Field label={t("settings.serviceType")}><Select value={f.visitType} onChange={(e) => setF({ ...f, visitType: e.target.value })}><option value="">{t("settings.serviceTypeNone")}</option><option value="CONSULTATION">{t("visit.consultation")}</option><option value="FOLLOW_UP">{t("visit.followUp")}</option></Select></Field>
        </form>
      </Sheet>
    </>
  );
}
