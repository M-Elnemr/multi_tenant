"use client";

import { shipName } from "@/lib/ship-name";
import { useEffect, useState } from "react";
import { useNavTitle, useSection } from "@/components/dashboard/section";
import { api } from "@/lib/client";
import { toMinor, money } from "@/lib/format";
import BrandingCard from "@/components/dashboard/branding";
import TemplatePicker from "@/components/dashboard/template-picker";
import WaReportCard from "@/components/dashboard/wa-report";
import CodGuardCard from "@/components/dashboard/cod-guard";
import MarketCard from "@/components/dashboard/market-card";
import EvolutionCard from "@/components/dashboard/evolution-card";
import SecurityCard from "@/components/dashboard/security-card";
import { ZonesCard } from "@/components/dashboard/zones";
import { BannersCard } from "@/components/dashboard/banners";
import { BranchesCard, ContactCard, MarketingCard, SetupChecklist, type StoreProfile } from "@/components/dashboard/store-identity";
import { CategoryPicker, categoriesValid, type CategoryOption } from "@/components/category-picker";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Button, Empty, ErrorText, Field, Input, PageHeader, Select, Table, Td, Textarea } from "@/components/ui";
import { Switch } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { Icon } from "@/components/icons";
import { SaveBar, SettingsCard, ToggleRow } from "@/components/dashboard/kit";

type Profile = StoreProfile & { categories?: { code: string }[]; otherCategory?: string | null; shippingPolicy?: string; returnPolicy?: string; privacyPolicy?: string; termsText?: string };
type Pm = { method: string; enabled: boolean };
type Ship = { id: string; type: string; name: string; feeMinor: number; freeAboveMinor?: number; isActive: boolean };

type Tab = "profile" | "look" | "sales" | "marketing" | "whatsapp" | "branches";
/** Old links such as /dashboard/settings#cod-guard land on the tab that holds that card. */
const ANCHOR_TAB: Record<string, Tab> = { profile: "profile", contact: "profile", look: "look", branding: "look", templates: "look", sales: "sales", shipping: "sales", "cod-guard": "sales", market: "sales", marketing: "marketing", "whatsapp-report": "marketing", "whatsapp-own": "whatsapp", branches: "branches" };

export default function StoreSettings() {
  const { t, locale, currency } = useI18n();
  const { can, canWrite } = useMe();
  const toast = useToast();
  const readSettings = can("settings.manage") || can("settings.read");   // read only access still sees the page, with every field disabled
  const readShipping = can("shipping.manage") || can("shipping.read");
  const profile = useApi<Profile>(readSettings ? "store/profile" : null);
  const methods = useApi<Pm[]>(readShipping ? "store/payment-methods" : null);
  const ships = useApi<Ship[]>(readShipping ? "store/shipping-methods" : null);
  const brand = useApi<{ logoFileId?: string }>(readSettings ? "tenant/branding" : null);
  const [edited, setP] = useState<Profile | null>(null);
  const p = edited ?? profile.data;
  // each part is its own side-menu item (flat menu); an old link with only #card opens the part that holds that card
  const tabs: Tab[] = ["profile", "look", "sales", "marketing", ...(can("ai.read") || can("ai.manage") ? ["whatsapp" as Tab] : []), ...(can("branch.manage") ? ["branches" as Tab] : [])];
  const [tab, setTab] = useSection<Tab>(tabs, "profile");
  const title = useNavTitle(t("nav.settings"));
  useEffect(() => {
    const id = window.location.hash.slice(1);
    if (!ANCHOR_TAB[id]) return;
    if (!new URLSearchParams(window.location.search).get("tab") && ANCHOR_TAB[id] !== "profile") { setTab(ANCHOR_TAB[id], id); return; }
    const h = setTimeout(() => document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" }), 250);
    return () => clearTimeout(h);
  }, [tab]); // eslint-disable-line react-hooks/exhaustive-deps
  const show = (k: Tab) => tab === k;
  const saveProfile = useAction(async () => { await api("store/profile", { method: "PATCH", body: { storeName: p?.storeName, shortDescription: p?.shortDescription, about: p?.about, shippingPolicy: p?.shippingPolicy, returnPolicy: p?.returnPolicy, privacyPolicy: p?.privacyPolicy, termsText: p?.termsText, minOrderMinor: p?.minOrderMinor, taxId: p?.taxId, vatIncluded: p?.vatIncluded, vatPercent: p?.vatPercent, returnWindowDays: p?.returnWindowDays } }); await profile.reload(); setP(null); toast(t("common.saved")); });
  const catList = useApi<CategoryOption[]>(readSettings ? "store/business-categories" : null);
  const [editedCats, setCats] = useState<{ codes: string[]; other: string } | null>(null);
  const cats = editedCats ?? (profile.data ? { codes: (profile.data.categories ?? []).map((c) => c.code), other: profile.data.otherCategory ?? "" } : null);
  const saveCats = useAction(async () => {
    if (!cats) return;
    await api("store/profile/categories", { method: "PUT", body: { categories: cats.codes, otherCategory: cats.codes.includes("other") ? cats.other.trim() : undefined } });
    await profile.reload();
    setCats(null);
    toast(t("common.saved"));
  });
  const setMethod = useAction(async (method: string, enabled: boolean) => { await api("store/payment-methods", { method: "PUT", body: { method, enabled } }); await methods.reload(); toast(t("common.saved")); });
  const [s, setS] = useState({ type: "FIXED", name: "", fee: "", freeAbove: "" });
  const addShip = useAction(async () => { await api("store/shipping-methods", { body: { type: s.type, name: s.name, feeMinor: toMinor(s.fee || "0"), freeAboveMinor: s.type === "FREE_ABOVE" ? toMinor(s.freeAbove) : undefined } }); setS({ type: "FIXED", name: "", fee: "", freeAbove: "" }); await ships.reload(); });
  const toggleShip = useAction(async (x: Ship) => { await api(`store/shipping-methods/${x.id}/active`, { method: "PUT", body: { active: !x.isActive } }); await ships.reload(); });

  return (
    <>
      <PageHeader eyebrow={t("nav.settings")} title={title} actions={can("settings.manage") ? <a href="/?edit=1" className="inline-flex h-10 items-center gap-2 rounded-[11px] bg-ink px-4 text-sm font-semibold text-surface transition active:scale-[.97]"><Icon name="edit" className="h-4 w-4" />{t("site.customize")}</a> : undefined} />
      <fieldset disabled={!canWrite("settings")} className="m-0 min-w-0 border-0 p-0">
      <div className="max-w-4xl space-y-5">
        {show("profile") && profile.data && <SetupChecklist profile={profile.data} hasLogo={!!brand.data?.logoFileId} />}
        {show("profile") && profile.data && <section id="contact"><ContactCard key={JSON.stringify(profile.data.workingHours) + profile.data.coverFileId} profile={profile.data} reload={profile.reload} /></section>}
        {show("profile") && p && (
          <SettingsCard icon="home" title={t("settings.storeProfile")}>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label={t("register.storeName")}><Input value={p.storeName} onChange={(e) => setP({ ...p, storeName: e.target.value })} /></Field>
              <Field label={t("settings.shortDesc")}><Input value={p.shortDescription ?? ""} onChange={(e) => setP({ ...p, shortDescription: e.target.value })} /></Field>
              <Field label={t("identity.minOrder")}><Input dir="ltr" inputMode="decimal" value={p.minOrderMinor ? String(p.minOrderMinor / 100) : ""} onChange={(e) => setP({ ...p, minOrderMinor: toMinor(e.target.value || "0") })} /></Field>
              <Field label={t("identity.returnDays")}><Input dir="ltr" inputMode="numeric" value={String(p.returnWindowDays ?? 14)} onChange={(e) => setP({ ...p, returnWindowDays: Number(e.target.value.replace(/\D/g, "") || 0) })} /></Field>
              <Field label={t("identity.taxId")}><Input dir="ltr" value={p.taxId ?? ""} onChange={(e) => setP({ ...p, taxId: e.target.value })} /></Field>
              <div className="self-end pb-1.5"><ToggleRow title={t("identity.vat")} control={<Switch checked={!!p.vatIncluded} onChange={(v) => setP({ ...p, vatIncluded: v })} label={t("identity.vat")} />} /></div>
            </div>
            <div className="mt-4 space-y-4">
            <Field label={t("settings.about")}><Textarea value={p.about ?? ""} onChange={(e) => setP({ ...p, about: e.target.value })} /></Field>
            <Field label={t("settings.shippingPolicy")}><Textarea value={p.shippingPolicy ?? ""} onChange={(e) => setP({ ...p, shippingPolicy: e.target.value })} /></Field>
            <Field label={t("identity.privacy")}><Textarea value={p.privacyPolicy ?? ""} onChange={(e) => setP({ ...p, privacyPolicy: e.target.value })} /></Field>
            <Field label={t("identity.terms")}><Textarea value={p.termsText ?? ""} onChange={(e) => setP({ ...p, termsText: e.target.value })} /></Field>
            <Field label={t("settings.returnPolicy")}><Textarea value={p.returnPolicy ?? ""} onChange={(e) => setP({ ...p, returnPolicy: e.target.value })} /></Field>
            </div>
            <div className="mt-3"><ErrorText error={saveProfile.error} /></div>
            {canWrite("settings") && <SaveBar dirty={!!edited} saving={saveProfile.loading} onSave={() => void saveProfile.run()} onDiscard={() => setP(null)} />}
          </SettingsCard>
        )}
        {show("profile") && cats && catList.data && (
          <SettingsCard icon="grid" title={t("storeSettings.categories")}>
            <CategoryPicker options={catList.data} value={cats.codes} onChange={(codes) => setCats({ ...cats, codes })} other={cats.other} onOther={(other) => setCats({ ...cats, other })} />
            <div className="mt-3"><ErrorText error={saveCats.error} /></div>
            <div className="mt-4"><Button loading={saveCats.loading} disabled={!editedCats || !categoriesValid(cats.codes, cats.other)} onClick={() => void saveCats.run()}>{t("common.save")}</Button></div>
          </SettingsCard>
        )}
        {show("look") && can("settings.manage") && <section id="branding"><BrandingCard /></section>}
        {show("look") && can("settings.manage") && <section id="templates"><TemplatePicker /></section>}
        {show("marketing") && can("copilot.use") && <section id="whatsapp-report"><WaReportCard /></section>}
        {show("sales") && can("settings.manage") && <section id="cod-guard"><CodGuardCard /></section>}
        {show("sales") && can("settings.manage") && <section id="market"><MarketCard /></section>}
        {show("sales") && methods.data && (
          <SettingsCard icon="billing" title={t("settings.paymentMethods")}>
            {methods.data.map((m) => (
              <ToggleRow key={m.method} title={t(`pay.${m.method}`)} control={<Switch checked={m.enabled} onChange={(v) => void setMethod.run(m.method, v)} label={t(`pay.${m.method}`)} />} />
            ))}
            <ErrorText error={setMethod.error} />
          </SettingsCard>
        )}
        {show("sales") && ships.data && (<section id="shipping">
          <SettingsCard icon="truck" title={t("settings.shipping")} flush>
            <div className="space-y-4 px-5 pb-5">
            {ships.data.length === 0 ? <Empty compact icon="truck">{t("settings.shipping")}</Empty> : <Table head={[t("products.name"), t("settings.type"), t("settings.fee"), ""]}>
              {ships.data.map((x) => <tr key={x.id}><Td>{shipName(t, x)}</Td><Td>{t(`ship.${x.type}`)}</Td><Td>{money(x.feeMinor, currency, locale)}{x.freeAboveMinor ? ` (${t("ship.freeAboveShort")} ${money(x.freeAboveMinor, currency, locale)})` : ""}</Td><Td className="text-end"><Button size="sm" variant="ghost" onClick={() => toggleShip.run(x)}>{x.isActive ? t("common.hide") : t("common.show")}</Button></Td></tr>)}
            </Table>}
            <form onSubmit={(e) => { e.preventDefault(); void addShip.run(); }} className="grid gap-3 sm:grid-cols-5">
              <Select value={s.type} onChange={(e) => setS({ ...s, type: e.target.value })}>{["PICKUP", "FIXED", "FREE_ABOVE"].map((x) => <option key={x} value={x}>{t(`ship.${x}`)}</option>)}</Select>
              <Input placeholder={t("products.name")} value={s.name} onChange={(e) => setS({ ...s, name: e.target.value })} required />
              <Input placeholder={t("settings.fee")} value={s.fee} onChange={(e) => setS({ ...s, fee: e.target.value })} inputMode="decimal" dir="ltr" />
              {s.type === "FREE_ABOVE" ? <Input placeholder={t("ship.freeAboveShort")} value={s.freeAbove} onChange={(e) => setS({ ...s, freeAbove: e.target.value })} inputMode="decimal" dir="ltr" /> : <span />}
              <Button type="submit" loading={addShip.loading}><Icon name="plus" className="h-4 w-4" />{t("common.add")}</Button>
            </form>
            <ErrorText error={addShip.error ?? toggleShip.error} />
            </div>
          </SettingsCard></section>
        )}
        {show("look") && can("settings.manage") && <BannersCard />}
        {show("marketing") && profile.data && can("settings.manage") && <MarketingCard profile={profile.data} reload={profile.reload} />}
        {show("sales") && can("shipping.manage") && <ZonesCard />}
        {show("profile") && <section id="security"><SecurityCard /></section>}
        {show("whatsapp") && (can("ai.read") || can("ai.manage")) && <section id="whatsapp-own"><EvolutionCard write={can("ai.manage")} /></section>}
        {show("branches") && can("branch.manage") && <section id="branches"><BranchesCard /></section>}
      </div>
      </fieldset>
    </>
  );
}
