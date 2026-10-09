"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { fromMinor, money, toMinor } from "@/lib/format";
import { GOVERNORATES } from "@/lib/governorates";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Alert, Badge, Button, Card, ErrorText, Field, Input, Modal } from "../ui";

type Zone = { id: string; name: string; governorateCodes: string[]; feeMinor: number; freeAboveMinor?: number | null; codFeeMinor: number; etaMinDays: number; etaMaxDays: number; isActive: boolean };
type Method = { id: string; type: string };

/** Price and delivery time by governorate. A governorate belongs to one zone; one "delivery by governorate" method makes the zones apply at checkout. */
export function ZonesCard() {
  const { t, locale, currency } = useI18n();
  const zones = useApi<Zone[]>("store/shipping-zones");
  const methods = useApi<Method[]>("store/shipping-methods");
  const [f, setF] = useState<(Partial<Zone> & { fee?: string; free?: string; cod?: string }) | null>(null);
  const hasMethod = (methods.data ?? []).some((m) => m.type === "ZONES");
  const addMethod = useAction(async () => { await api("store/shipping-methods", { body: { type: "ZONES", name: t("ship.ZONES"), feeMinor: 0 } }); await methods.reload(); });
  const save = useAction(async () => {
    if (!f) return;
    const body = { name: f.name, governorateCodes: f.governorateCodes ?? [], feeMinor: toMinor(f.fee || "0"), freeAboveMinor: f.free?.trim() ? toMinor(f.free) : null, codFeeMinor: toMinor(f.cod || "0"), etaMinDays: f.etaMinDays ?? 1, etaMaxDays: f.etaMaxDays ?? 3 };
    if (f.id) await api(`store/shipping-zones/${f.id}`, { method: "PATCH", body }); else await api("store/shipping-zones", { body });
    setF(null); await zones.reload();
  });
  const toggle = useAction(async (z: Zone) => { await api(`store/shipping-zones/${z.id}`, { method: "PATCH", body: { isActive: !z.isActive } }); await zones.reload(); });
  const del = useAction(async (z: Zone) => { await api(`store/shipping-zones/${z.id}`, { method: "DELETE" }); await zones.reload(); });
  const used = new Set((zones.data ?? []).filter((z) => z.id !== f?.id).flatMap((z) => z.governorateCodes));
  const gname = (c: string) => { const g = GOVERNORATES.find((x) => x.code === c); return g ? (locale === "ar" ? g.ar : g.en) : c; };
  const toggleGov = (c: string) => setF((cur) => cur && { ...cur, governorateCodes: (cur.governorateCodes ?? []).includes(c) ? (cur.governorateCodes ?? []).filter((x) => x !== c) : [...(cur.governorateCodes ?? []), c] });
  return (
    <Card className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2"><div><h2 className="font-semibold">🚚 {t("zones.title")}</h2><p className="text-sm text-slate-500">{t("zones.hint")}</p></div><Button size="sm" onClick={() => setF({ name: "", governorateCodes: [], etaMinDays: 1, etaMaxDays: 3 })}>{t("common.add")}</Button></div>
      {!hasMethod && methods.data && <Alert tone="amber"><span className="me-3">{t("zones.needMethod")}</span><Button size="sm" loading={addMethod.loading} onClick={() => addMethod.run()}>{t("zones.enable")}</Button></Alert>}
      <ul className="divide-y text-sm">
        {(zones.data ?? []).map((z) => (
          <li key={z.id} className="flex flex-wrap items-start justify-between gap-3 py-3">
            <div className="min-w-0">
              <p className="font-semibold">{z.name} {!z.isActive && <Badge tone="slate">{t("common.hidden")}</Badge>}</p>
              <p className="text-slate-600">{z.governorateCodes.map(gname).join("، ") || "—"}</p>
              <p className="mt-0.5 text-xs text-slate-500">{money(z.feeMinor, currency, locale)}{z.freeAboveMinor ? ` · ${t("ship.freeAboveShort")} ${money(z.freeAboveMinor, currency, locale)}` : ""}{z.codFeeMinor ? ` · ${t("checkout.codFee")} ${money(z.codFeeMinor, currency, locale)}` : ""} · {t("checkout.eta", { min: z.etaMinDays, max: z.etaMaxDays })}</p>
            </div>
            <div className="flex gap-1"><Button size="sm" variant="secondary" onClick={() => setF({ ...z, fee: fromMinor(z.feeMinor), free: z.freeAboveMinor ? fromMinor(z.freeAboveMinor) : "", cod: z.codFeeMinor ? fromMinor(z.codFeeMinor) : "" })}>{t("common.edit")}</Button><Button size="sm" variant="ghost" onClick={() => toggle.run(z)}>{z.isActive ? t("common.hide") : t("common.show")}</Button><Button size="sm" variant="ghost" onClick={() => del.run(z)}>{t("common.delete")}</Button></div>
          </li>
        ))}
        {zones.data?.length === 0 && <li className="py-3 text-slate-500">{t("zones.empty")}</li>}
      </ul>
      <ErrorText error={toggle.error ?? del.error ?? addMethod.error} />
      <Modal open={!!f} onClose={() => setF(null)} title={f?.id ? t("zones.edit") : t("zones.add")} wide>
        {f && (
          <form onSubmit={(e) => { e.preventDefault(); void save.run(); }} className="space-y-4">
            <Field label={t("products.name")}><Input value={f.name ?? ""} onChange={(e) => setF({ ...f, name: e.target.value })} required placeholder={t("zones.namePlaceholder")} /></Field>
            <div>
              <p className="mb-2 text-sm font-semibold text-slate-700">{t("zones.governorates")} <span className="font-normal text-slate-500">({(f.governorateCodes ?? []).length})</span></p>
              <div className="flex flex-wrap gap-1.5">{GOVERNORATES.map((g) => {
                const on = (f.governorateCodes ?? []).includes(g.code), taken = used.has(g.code);
                return <button type="button" key={g.code} onClick={() => toggleGov(g.code)} className={`rounded-full border px-3 py-1 text-xs font-semibold transition ${on ? "border-brand bg-brand text-white" : taken ? "border-slate-200 bg-slate-100 text-slate-400" : "border-slate-300 bg-white hover:border-brand"}`} title={taken && !on ? t("zones.taken") : undefined}>{locale === "ar" ? g.ar : g.en}</button>;
              })}</div>
            </div>
            <div className="grid gap-3 sm:grid-cols-3">
              <Field label={t("settings.fee")}><Input dir="ltr" inputMode="decimal" value={f.fee ?? ""} onChange={(e) => setF({ ...f, fee: e.target.value })} /></Field>
              <Field label={t("ship.freeAboveShort")} hint={t("zones.freeHint")}><Input dir="ltr" inputMode="decimal" value={f.free ?? ""} onChange={(e) => setF({ ...f, free: e.target.value })} /></Field>
              <Field label={t("checkout.codFee")} hint={t("zones.codHint")}><Input dir="ltr" inputMode="decimal" value={f.cod ?? ""} onChange={(e) => setF({ ...f, cod: e.target.value })} /></Field>
              <Field label={t("zones.etaMin")}><Input dir="ltr" inputMode="numeric" value={String(f.etaMinDays ?? 1)} onChange={(e) => setF({ ...f, etaMinDays: Number(e.target.value.replace(/\D/g, "") || 0) })} /></Field>
              <Field label={t("zones.etaMax")}><Input dir="ltr" inputMode="numeric" value={String(f.etaMaxDays ?? 3)} onChange={(e) => setF({ ...f, etaMaxDays: Number(e.target.value.replace(/\D/g, "") || 0) })} /></Field>
            </div>
            <ErrorText error={save.error} />
            <Button type="submit" loading={save.loading}>{t("common.save")}</Button>
          </form>
        )}
      </Modal>
    </Card>
  );
}
