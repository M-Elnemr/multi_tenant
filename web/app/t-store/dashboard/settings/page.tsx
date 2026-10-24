"use client";

import { shipName } from "@/lib/ship-name";
import { useEffect, useState } from "react";
import { api } from "@/lib/client";
import { toMinor, money } from "@/lib/format";
import BrandingCard from "@/components/dashboard/branding";
import TemplatePicker from "@/components/dashboard/template-picker";
import WaReportCard from "@/components/dashboard/wa-report";
import CodGuardCard from "@/components/dashboard/cod-guard";
import MarketCard from "@/components/dashboard/market-card";
import EvolutionCard from "@/components/dashboard/evolution-card";
import { ZonesCard } from "@/components/dashboard/zones";
import { BannersCard } from "@/components/dashboard/banners";
import { BranchesCard, ContactCard, MarketingCard, SetupChecklist, type StoreProfile } from "@/components/dashboard/store-identity";
import { CategoryPicker, categoriesValid, type CategoryOption } from "@/components/category-picker";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Button, Card, ErrorText, Field, Input, PageHeader, Select, Table, Tabs, Td, Textarea } from "@/components/ui";

type Profile = StoreProfile & { categories?: { code: string }[]; otherCategory?: string | null; shippingPolicy?: string; returnPolicy?: string; privacyPolicy?: string; termsText?: string };
type Pm = { method: string; enabled: boolean };
type Ship = { id: string; type: string; name: string; feeMinor: number; freeAboveMinor?: number; isActive: boolean };

type Tab = "profile" | "look" | "sales" | "marketing" | "whatsapp" | "branches";
/** Old links such as /dashboard/settings#cod-guard land on the tab that holds that card. */
const ANCHOR_TAB: Record<string, Tab> = { profile: "profile", contact: "profile", look: "look", branding: "look", templates: "look", sales: "sales", shipping: "sales", "cod-guard": "sales", market: "sales", marketing: "marketing", "whatsapp-report": "marketing", "whatsapp-own": "whatsapp", branches: "branches" };

export default function StoreSettings() {
  const { t, locale, currency } = useI18n();
  const { can, canWrite } = useMe();
  const readSettings = can("settings.manage") || can("settings.read");   // read only access still sees the page, with every field disabled
  const readShipping = can("shipping.manage") || can("shipping.read");
  const profile = useApi<Profile>(readSettings ? "store/profile" : null);
  const methods = useApi<Pm[]>(readShipping ? "store/payment-methods" : null);
  const ships = useApi<Ship[]>(readShipping ? "store/shipping-methods" : null);
  const brand = useApi<{ logoFileId?: string }>(readSettings ? "tenant/branding" : null);
  const [edited, setP] = useState<Profile | null>(null);
  const p = edited ?? profile.data;
  const [saved, setSaved] = useState(false);
  const [tab, setTab] = useState<Tab>("profile");
  useEffect(() => {
    const id = window.location.hash.slice(1);
    if (!ANCHOR_TAB[id]) return;
    setTimeout(() => { setTab(ANCHOR_TAB[id]); setTimeout(() => document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" }), 150); }, 0);
  }, []);
  const show = (k: Tab) => tab === k;
  const saveProfile = useAction(async () => { await api("store/profile", { method: "PATCH", body: { storeName: p?.storeName, shortDescription: p?.shortDescription, about: p?.about, shippingPolicy: p?.shippingPolicy, returnPolicy: p?.returnPolicy, privacyPolicy: p?.privacyPolicy, termsText: p?.termsText, minOrderMinor: p?.minOrderMinor, taxId: p?.taxId, vatIncluded: p?.vatIncluded, vatPercent: p?.vatPercent, returnWindowDays: p?.returnWindowDays } }); setSaved(true); });
  const catList = useApi<CategoryOption[]>(readSettings ? "store/business-categories" : null);
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
      <PageHeader title={t("nav.settings")} actions={can("settings.manage") ? <a href="/?edit=1" className="inline-flex items-center gap-2 rounded-xl bg-brand-gradient px-4 py-2 text-sm font-semibold text-white shadow-brand transition hover:brightness-110 active:scale-95">✨ {t("site.customize")}</a> : undefined} />
      <fieldset disabled={!canWrite("settings")} className="m-0 min-w-0 border-0 p-0">
      <div className="space-y-6">
        {profile.data && <SetupChecklist profile={profile.data} hasLogo={!!brand.data?.logoFileId} />}
        <Tabs<Tab> tabs={[{ key: "profile", label: t("settings.tab.profile") }, { key: "look", label: t("settings.tab.look") }, { key: "sales", label: t("settings.tab.sales") }, { key: "marketing", label: t("settings.tab.marketing") }, ...(can("ai.read") || can("ai.manage") ? [{ key: "whatsapp" as Tab, label: t("settings.tab.whatsapp") }] : []), ...(can("branch.manage") ? [{ key: "branches" as Tab, label: t("settings.tab.branches") }] : [])]} value={tab} onChange={setTab} />
        {show("profile") && profile.data && <section id="contact"><ContactCard key={JSON.stringify(profile.data.workingHours) + profile.data.coverFileId} profile={profile.data} reload={profile.reload} /></section>}
        {show("profile") && p && (
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
        {show("profile") && cats && catList.data && (
          <Card className="space-y-3">
            <h2 className="font-medium">{t("storeSettings.categories")}</h2>
            <CategoryPicker options={catList.data} value={cats.codes} onChange={(codes) => setCats({ ...cats, codes })} other={cats.other} onOther={(other) => setCats({ ...cats, other })} />
            <ErrorText error={saveCats.error} />{saved && <Alert tone="green">{t("common.saved")}</Alert>}
            <Button loading={saveCats.loading} disabled={!categoriesValid(cats.codes, cats.other)} onClick={() => { setSaved(false); void saveCats.run(); }}>{t("common.save")}</Button>
          </Card>
        )}
        {show("look") && can("settings.manage") && <section id="branding"><BrandingCard /></section>}
        {show("look") && can("settings.manage") && <section id="templates"><TemplatePicker /></section>}
        {show("marketing") && can("copilot.use") && <section id="whatsapp-report"><WaReportCard /></section>}
        {show("sales") && can("settings.manage") && <section id="cod-guard"><CodGuardCard /></section>}
        {show("sales") && can("settings.manage") && <section id="market"><MarketCard /></section>}
        {show("sales") && methods.data && (
          <Card className="space-y-3">
            <h2 className="font-medium">{t("settings.paymentMethods")}</h2>
            {methods.data.map((m) => (
              <label key={m.method} className="flex items-center justify-between rounded-lg border p-3 text-sm"><span>{t(`pay.${m.method}`)}</span><input type="checkbox" checked={m.enabled} onChange={(e) => setMethod.run(m.method, e.target.checked)} className="h-5 w-5" /></label>
            ))}
            <ErrorText error={setMethod.error} />
          </Card>
        )}
        {show("sales") && ships.data && (<section id="shipping">
          <Card className="space-y-3">
            <h2 className="font-medium">{t("settings.shipping")}</h2>
            <Table head={[t("products.name"), t("settings.type"), t("settings.fee"), ""]}>
              {ships.data.map((x) => <tr key={x.id}><Td>{shipName(t, x)}</Td><Td>{t(`ship.${x.type}`)}</Td><Td>{money(x.feeMinor, currency, locale)}{x.freeAboveMinor ? ` (${t("ship.freeAboveShort")} ${money(x.freeAboveMinor, currency, locale)})` : ""}</Td><Td className="text-end"><Button size="sm" variant="ghost" onClick={() => toggleShip.run(x)}>{x.isActive ? t("common.hide") : t("common.show")}</Button></Td></tr>)}
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
        {show("look") && can("settings.manage") && <BannersCard />}
        {show("marketing") && profile.data && can("settings.manage") && <MarketingCard profile={profile.data} reload={profile.reload} />}
        {show("sales") && can("shipping.manage") && <ZonesCard />}
        {show("whatsapp") && (can("ai.read") || can("ai.manage")) && <section id="whatsapp-own"><EvolutionCard write={can("ai.manage")} /></section>}
        {show("branches") && can("branch.manage") && <section id="branches"><BranchesCard /></section>}
      </div>
      </fieldset>
    </>
  );
}
