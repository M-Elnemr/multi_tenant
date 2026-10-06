"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { toMinor, money } from "@/lib/format";
import BrandingCard from "@/components/dashboard/branding";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Button, Card, ErrorText, Field, Input, PageHeader, Select, Table, Td, Textarea } from "@/components/ui";

type Profile = { storeName: string; shortDescription?: string; about?: string; supportPhone?: string; supportEmail?: string; addressText?: string; shippingPolicy?: string; returnPolicy?: string };
type Pm = { method: string; enabled: boolean };
type Ship = { id: string; type: string; name: string; feeMinor: number; freeAboveMinor?: number; isActive: boolean };
type Branch = { id: string; name: string; code: string; city?: string; isActive: boolean };

export default function StoreSettings() {
  const { t, locale, currency } = useI18n();
  const { can } = useMe();
  const profile = useApi<Profile>(can("settings.manage") ? "store/profile" : null);
  const methods = useApi<Pm[]>(can("shipping.manage") ? "store/payment-methods" : null);
  const ships = useApi<Ship[]>(can("shipping.manage") ? "store/shipping-methods" : null);
  const branches = useApi<Branch[]>(can("branch.manage") ? "store/branches" : null);
  const [edited, setP] = useState<Profile | null>(null);
  const p = edited ?? profile.data;
  const [saved, setSaved] = useState(false);
  const saveProfile = useAction(async () => { await api("store/profile", { method: "PATCH", body: p }); setSaved(true); });
  const setMethod = useAction(async (method: string, enabled: boolean) => { await api("store/payment-methods", { method: "PUT", body: { method, enabled } }); await methods.reload(); });
  const [s, setS] = useState({ type: "FIXED", name: "", fee: "", freeAbove: "" });
  const addShip = useAction(async () => { await api("store/shipping-methods", { body: { type: s.type, name: s.name, feeMinor: toMinor(s.fee || "0"), freeAboveMinor: s.type === "FREE_ABOVE" ? toMinor(s.freeAbove) : undefined } }); setS({ type: "FIXED", name: "", fee: "", freeAbove: "" }); await ships.reload(); });
  const toggleShip = useAction(async (x: Ship) => { await api(`store/shipping-methods/${x.id}/active`, { method: "PUT", body: { active: !x.isActive } }); await ships.reload(); });
  const [b, setB] = useState({ name: "", code: "", city: "" });
  const addBranch = useAction(async () => { await api("store/branches", { body: b }); setB({ name: "", code: "", city: "" }); await branches.reload(); });

  return (
    <>
      <PageHeader title={t("nav.settings")} />
      <div className="space-y-6">
        {p && (
          <Card className="space-y-4">
            <h2 className="font-medium">{t("settings.storeProfile")}</h2>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label={t("register.storeName")}><Input value={p.storeName} onChange={(e) => setP({ ...p, storeName: e.target.value })} /></Field>
              <Field label={t("settings.shortDesc")}><Input value={p.shortDescription ?? ""} onChange={(e) => setP({ ...p, shortDescription: e.target.value })} /></Field>
              <Field label={t("register.phone")}><Input value={p.supportPhone ?? ""} onChange={(e) => setP({ ...p, supportPhone: e.target.value })} dir="ltr" /></Field>
              <Field label={t("register.email")}><Input value={p.supportEmail ?? ""} onChange={(e) => setP({ ...p, supportEmail: e.target.value })} dir="ltr" /></Field>
            </div>
            <Field label={t("settings.about")}><Textarea value={p.about ?? ""} onChange={(e) => setP({ ...p, about: e.target.value })} /></Field>
            <Field label={t("settings.shippingPolicy")}><Textarea value={p.shippingPolicy ?? ""} onChange={(e) => setP({ ...p, shippingPolicy: e.target.value })} /></Field>
            <Field label={t("settings.returnPolicy")}><Textarea value={p.returnPolicy ?? ""} onChange={(e) => setP({ ...p, returnPolicy: e.target.value })} /></Field>
            <ErrorText error={saveProfile.error} />{saved && <Alert tone="green">{t("common.saved")}</Alert>}
            <Button loading={saveProfile.loading} onClick={() => { setSaved(false); void saveProfile.run(); }}>{t("common.save")}</Button>
          </Card>
        )}
        {can("settings.manage") && <BrandingCard />}
        {methods.data && (
          <Card className="space-y-3">
            <h2 className="font-medium">{t("settings.paymentMethods")}</h2>
            {methods.data.map((m) => (
              <label key={m.method} className="flex items-center justify-between rounded-lg border p-3 text-sm"><span>{t(`pay.${m.method}`)}</span><input type="checkbox" checked={m.enabled} onChange={(e) => setMethod.run(m.method, e.target.checked)} className="h-5 w-5" /></label>
            ))}
            <ErrorText error={setMethod.error} />
          </Card>
        )}
        {ships.data && (
          <Card className="space-y-3">
            <h2 className="font-medium">{t("settings.shipping")}</h2>
            <Table head={[t("products.name"), t("settings.type"), t("settings.fee"), ""]}>
              {ships.data.map((x) => <tr key={x.id}><Td>{x.name}</Td><Td>{t(`ship.${x.type}`)}</Td><Td>{money(x.feeMinor, currency, locale)}{x.freeAboveMinor ? ` (${t("ship.freeAboveShort")} ${money(x.freeAboveMinor, currency, locale)})` : ""}</Td><Td className="text-end"><Button size="sm" variant="ghost" onClick={() => toggleShip.run(x)}>{x.isActive ? t("common.hide") : t("common.show")}</Button></Td></tr>)}
            </Table>
            <form onSubmit={(e) => { e.preventDefault(); void addShip.run(); }} className="grid gap-3 sm:grid-cols-5">
              <Select value={s.type} onChange={(e) => setS({ ...s, type: e.target.value })}>{["PICKUP", "FIXED", "FREE_ABOVE"].map((x) => <option key={x} value={x}>{t(`ship.${x}`)}</option>)}</Select>
              <Input placeholder={t("products.name")} value={s.name} onChange={(e) => setS({ ...s, name: e.target.value })} required />
              <Input placeholder={t("settings.fee")} value={s.fee} onChange={(e) => setS({ ...s, fee: e.target.value })} inputMode="decimal" dir="ltr" />
              {s.type === "FREE_ABOVE" ? <Input placeholder={t("ship.freeAboveShort")} value={s.freeAbove} onChange={(e) => setS({ ...s, freeAbove: e.target.value })} inputMode="decimal" dir="ltr" /> : <span />}
              <Button type="submit" loading={addShip.loading}>{t("common.add")}</Button>
            </form>
            <ErrorText error={addShip.error ?? toggleShip.error} />
          </Card>
        )}
        {branches.data && (
          <Card className="space-y-3">
            <h2 className="font-medium">{t("settings.branches")}</h2>
            <ul className="text-sm">{branches.data.map((x) => <li key={x.id} className="border-b py-2 last:border-0">{x.name} <span className="font-mono text-xs text-slate-500">{x.code}</span> {x.city}</li>)}</ul>
            <form onSubmit={(e) => { e.preventDefault(); void addBranch.run(); }} className="grid gap-3 sm:grid-cols-4">
              <Input placeholder={t("products.name")} value={b.name} onChange={(e) => setB({ ...b, name: e.target.value })} required />
              <Input placeholder="CODE" value={b.code} onChange={(e) => setB({ ...b, code: e.target.value })} dir="ltr" required />
              <Input placeholder={t("checkout.city")} value={b.city} onChange={(e) => setB({ ...b, city: e.target.value })} />
              <Button type="submit" loading={addBranch.loading}>{t("common.add")}</Button>
            </form>
            <ErrorText error={addBranch.error} />
          </Card>
        )}
      </div>
    </>
  );
}
