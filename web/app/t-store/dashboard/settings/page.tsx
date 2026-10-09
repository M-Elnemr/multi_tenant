"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { toMinor, money } from "@/lib/format";
import BrandingCard from "@/components/dashboard/branding";
import { ZonesCard } from "@/components/dashboard/zones";
import { BannersCard } from "@/components/dashboard/banners";
import { BranchesCard, ContactCard, SetupChecklist, type StoreProfile } from "@/components/dashboard/store-identity";
import { CategoryPicker, categoriesValid, type CategoryOption } from "@/components/category-picker";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Button, Card, ErrorText, Field, Input, PageHeader, Select, Table, Td, Textarea } from "@/components/ui";

type Profile = StoreProfile & { categories?: { code: string }[]; otherCategory?: string | null; shippingPolicy?: string; returnPolicy?: string; privacyPolicy?: string; termsText?: string };
type Pm = { method: string; enabled: boolean };
type Ship = { id: string; type: string; name: string; feeMinor: number; freeAboveMinor?: number; isActive: boolean };

export default function StoreSettings() {
  const { t, locale, currency } = useI18n();
  const { can } = useMe();
  const profile = useApi<Profile>(can("settings.manage") ? "store/profile" : null);
  const methods = useApi<Pm[]>(can("shipping.manage") ? "store/payment-methods" : null);
  const ships = useApi<Ship[]>(can("shipping.manage") ? "store/shipping-methods" : null);
  const brand = useApi<{ logoFileId?: string }>(can("settings.manage") ? "tenant/branding" : null);
  const [edited, setP] = useState<Profile | null>(null);
  const p = edited ?? profile.data;
  const [saved, setSaved] = useState(false);
  const saveProfile = useAction(async () => { await api("store/profile", { method: "PATCH", body: { storeName: p?.storeName, shortDescription: p?.shortDescription, about: p?.about, shippingPolicy: p?.shippingPolicy, returnPolicy: p?.returnPolicy, privacyPolicy: p?.privacyPolicy, termsText: p?.termsText, minOrderMinor: p?.minOrderMinor, taxId: p?.taxId, vatIncluded: p?.vatIncluded, vatPercent: p?.vatPercent, returnWindowDays: p?.returnWindowDays } }); setSaved(true); });
  const catList = useApi<CategoryOption[]>(can("settings.manage") ? "store/business-categories" : null);
  const [editedCats, setCats] = useState<{ codes: string[]; other: string } | null>(null);
  const cats = editedCats ?? (profile.data ? { codes: (profile.data.categories ?? []).map((c) => c.code), other: profile.data.otherCategory ?? "" } : null);
  const saveCats = useAction(async () => {
    if (!cats) return;
    await api("store/profile/categories", { method: "PUT", body: { categories: cats.codes, otherCategory: cats.codes.includes("other") ? cats.other.trim() : undefined } });
    setSaved(true);
    await profile.reload();
  });
  const setMethod = useAction(async (method: string, enabled: boolean) => { await api("store/payment-methods", { method: "PUT", body: { method, enabled } }); await methods.reload(); });
  const [s, setS] = useState({ type: "FIXED", name: "", fee: "", freeAbove: "" });
  const addShip = useAction(async () => { await api("store/shipping-methods", { body: { type: s.type, name: s.name, feeMinor: toMinor(s.fee || "0"), freeAboveMinor: s.type === "FREE_ABOVE" ? toMinor(s.freeAbove) : undefined } }); setS({ type: "FIXED", name: "", fee: "", freeAbove: "" }); await ships.reload(); });
  const toggleShip = useAction(async (x: Ship) => { await api(`store/shipping-methods/${x.id}/active`, { method: "PUT", body: { active: !x.isActive } }); await ships.reload(); });

  return (
    <>
      <PageHeader title={t("nav.settings")} />
      <div className="space-y-6">
        {profile.data && <SetupChecklist profile={profile.data} hasLogo={!!brand.data?.logoFileId} />}
        {profile.data && <section id="contact"><ContactCard key={JSON.stringify(profile.data.workingHours) + profile.data.coverFileId} profile={profile.data} reload={profile.reload} /></section>}
        {p && (
          <Card className="space-y-4">
            <h2 className="font-medium">{t("settings.storeProfile")}</h2>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label={t("register.storeName")}><Input value={p.storeName} onChange={(e) => setP({ ...p, storeName: e.target.value })} /></Field>
              <Field label={t("settings.shortDesc")}><Input value={p.shortDescription ?? ""} onChange={(e) => setP({ ...p, shortDescription: e.target.value })} /></Field>
              <Field label={t("identity.minOrder")}><Input dir="ltr" inputMode="decimal" value={p.minOrderMinor ? String(p.minOrderMinor / 100) : ""} onChange={(e) => setP({ ...p, minOrderMinor: toMinor(e.target.value || "0") })} /></Field>
              <Field label={t("identity.returnDays")}><Input dir="ltr" inputMode="numeric" value={String(p.returnWindowDays ?? 14)} onChange={(e) => setP({ ...p, returnWindowDays: Number(e.target.value.replace(/\D/g, "") || 0) })} /></Field>
              <Field label={t("identity.taxId")}><Input dir="ltr" value={p.taxId ?? ""} onChange={(e) => setP({ ...p, taxId: e.target.value })} /></Field>
              <label className="flex items-center gap-2 self-end pb-2 text-sm"><input type="checkbox" className="h-5 w-5" checked={!!p.vatIncluded} onChange={(e) => setP({ ...p, vatIncluded: e.target.checked })} />{t("identity.vat")}</label>
            </div>
            <Field label={t("settings.about")}><Textarea value={p.about ?? ""} onChange={(e) => setP({ ...p, about: e.target.value })} /></Field>
            <Field label={t("settings.shippingPolicy")}><Textarea value={p.shippingPolicy ?? ""} onChange={(e) => setP({ ...p, shippingPolicy: e.target.value })} /></Field>
            <Field label={t("identity.privacy")}><Textarea value={p.privacyPolicy ?? ""} onChange={(e) => setP({ ...p, privacyPolicy: e.target.value })} /></Field>
            <Field label={t("identity.terms")}><Textarea value={p.termsText ?? ""} onChange={(e) => setP({ ...p, termsText: e.target.value })} /></Field>
            <Field label={t("settings.returnPolicy")}><Textarea value={p.returnPolicy ?? ""} onChange={(e) => setP({ ...p, returnPolicy: e.target.value })} /></Field>
            <ErrorText error={saveProfile.error} />{saved && <Alert tone="green">{t("common.saved")}</Alert>}
            <Button loading={saveProfile.loading} onClick={() => { setSaved(false); void saveProfile.run(); }}>{t("common.save")}</Button>
          </Card>
        )}
        {cats && catList.data && (
          <Card className="space-y-3">
            <h2 className="font-medium">{t("storeSettings.categories")}</h2>
            <CategoryPicker options={catList.data} value={cats.codes} onChange={(codes) => setCats({ ...cats, codes })} other={cats.other} onOther={(other) => setCats({ ...cats, other })} />
            <ErrorText error={saveCats.error} />{saved && <Alert tone="green">{t("common.saved")}</Alert>}
            <Button loading={saveCats.loading} disabled={!categoriesValid(cats.codes, cats.other)} onClick={() => { setSaved(false); void saveCats.run(); }}>{t("common.save")}</Button>
          </Card>
        )}
        {can("settings.manage") && <section id="branding"><BrandingCard /></section>}
        {methods.data && (
          <Card className="space-y-3">
            <h2 className="font-medium">{t("settings.paymentMethods")}</h2>
            {methods.data.map((m) => (
              <label key={m.method} className="flex items-center justify-between rounded-lg border p-3 text-sm"><span>{t(`pay.${m.method}`)}</span><input type="checkbox" checked={m.enabled} onChange={(e) => setMethod.run(m.method, e.target.checked)} className="h-5 w-5" /></label>
            ))}
            <ErrorText error={setMethod.error} />
          </Card>
        )}
        {ships.data && (<section id="shipping">
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
          </Card></section>
        )}
        {can("settings.manage") && <BannersCard />}
        {can("shipping.manage") && <ZonesCard />}
        {can("branch.manage") && <section id="branches"><BranchesCard /></section>}
      </div>
    </>
  );
}
