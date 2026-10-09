"use client";

import Link from "next/link";
import { useState } from "react";
import { api } from "@/lib/client";
import { GOVERNORATES, governorateName } from "@/lib/governorates";
import { defaultHours, type WeekHours } from "@/lib/hours";
import { useAction, useApi, useMe } from "../hooks";
import { useI18n } from "../i18n-provider";
import { PhoneInput, EmailInput } from "../inputs";
import { ImageUploader } from "../uploader";
import { Alert, Badge, Button, Card, ErrorText, Field, Input, Modal, Select, Textarea } from "../ui";
import { HoursEditor } from "./hours-editor";

export type StoreProfile = {
  storeName: string; shortDescription?: string; about?: string; supportPhone?: string; supportEmail?: string; addressText?: string;
  whatsapp?: string; extraPhones?: string[]; facebookUrl?: string; instagramUrl?: string; tiktokUrl?: string; websiteUrl?: string; mapsUrl?: string;
  workingHours?: WeekHours; isOpen?: boolean; closedMessage?: string; announcement?: string; coverFileId?: string | null;
  metaPixelId?: string; tiktokPixelId?: string; gaId?: string;
  minOrderMinor?: number; taxId?: string; vatIncluded?: boolean; vatPercent?: number; returnWindowDays?: number;
};

/** Contact channels, social links, opening hours, cover image, vacation mode. Every field is optional; what is filled shows on the storefront. */
export function ContactCard({ profile, reload }: { profile: StoreProfile; reload: () => void }) {
  const { t } = useI18n();
  const [p, setP] = useState<StoreProfile>(profile);
  const [saved, setSaved] = useState(false);
  const save = useAction(async () => {
    const { storeName, addressText, whatsapp, extraPhones, facebookUrl, instagramUrl, tiktokUrl, websiteUrl, mapsUrl, workingHours, isOpen, closedMessage, announcement, coverFileId } = p;
    await api("store/profile", { method: "PATCH", body: { storeName, addressText, whatsapp, extraPhones, facebookUrl, instagramUrl, tiktokUrl, websiteUrl, mapsUrl, workingHours, isOpen, closedMessage, announcement, coverFileId, supportPhone: p.supportPhone, supportEmail: p.supportEmail } });
    setSaved(true); reload();
  });
  const extra = p.extraPhones ?? [];
  return (
    <Card className="space-y-5">
      <h2 className="font-semibold">{t("identity.contact")}</h2>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label={t("identity.phone")}><PhoneInput value={p.supportPhone ?? ""} onValue={(v) => setP({ ...p, supportPhone: v })} /></Field>
        <Field label="WhatsApp" hint={t("identity.whatsappHint")}><PhoneInput value={p.whatsapp ?? ""} onValue={(v) => setP({ ...p, whatsapp: v })} /></Field>
        <Field label={t("identity.email")}><EmailInput value={p.supportEmail ?? ""} onValue={(v) => setP({ ...p, supportEmail: v })} /></Field>
        <Field label={t("identity.extraPhones")} hint={t("identity.extraPhonesHint")}>
          <Input dir="ltr" value={extra.join(", ")} onChange={(e) => setP({ ...p, extraPhones: e.target.value.split(/[,،]/).map((x) => x.trim()).filter(Boolean) })} />
        </Field>
      </div>
      <Field label={t("identity.address")}><Textarea rows={2} value={p.addressText ?? ""} onChange={(e) => setP({ ...p, addressText: e.target.value })} /></Field>
      <Field label={t("identity.mapsUrl")} hint={t("identity.mapsHint")}><Input dir="ltr" placeholder="https://maps.google.com/…" value={p.mapsUrl ?? ""} onChange={(e) => setP({ ...p, mapsUrl: e.target.value })} /></Field>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Facebook"><Input dir="ltr" placeholder="https://facebook.com/…" value={p.facebookUrl ?? ""} onChange={(e) => setP({ ...p, facebookUrl: e.target.value })} /></Field>
        <Field label="Instagram"><Input dir="ltr" placeholder="https://instagram.com/…" value={p.instagramUrl ?? ""} onChange={(e) => setP({ ...p, instagramUrl: e.target.value })} /></Field>
        <Field label="TikTok"><Input dir="ltr" placeholder="https://tiktok.com/@…" value={p.tiktokUrl ?? ""} onChange={(e) => setP({ ...p, tiktokUrl: e.target.value })} /></Field>
        <Field label={t("identity.website")}><Input dir="ltr" placeholder="https://…" value={p.websiteUrl ?? ""} onChange={(e) => setP({ ...p, websiteUrl: e.target.value })} /></Field>
      </div>
      <div className="space-y-2">
        <h3 className="text-sm font-semibold text-slate-700">{t("identity.hours")}</h3>
        <HoursEditor value={p.workingHours ?? {}} onChange={(workingHours) => setP({ ...p, workingHours })} />
        <Button size="sm" variant="ghost" onClick={() => setP({ ...p, workingHours: defaultHours() })}>{t("identity.hoursDefault")}</Button>
      </div>
      <div className="space-y-2">
        <h3 className="text-sm font-semibold text-slate-700">{t("identity.cover")}</h3>
        <div className="flex items-center gap-3">
          {p.coverFileId && (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={`/api/bff/files/${p.coverFileId}/content?variant=thumb`} alt="" className="h-16 w-28 rounded-lg object-cover" />
          )}
          <ImageUploader category="PRODUCT_IMAGE" label={t("identity.coverUpload")} onUploaded={(id) => setP({ ...p, coverFileId: id })} />
          {p.coverFileId && <Button size="sm" variant="ghost" onClick={() => setP({ ...p, coverFileId: "" })}>{t("common.remove")}</Button>}
        </div>
        <p className="text-xs text-slate-500">{t("identity.coverHint")}</p>
      </div>
      <Field label={t("identity.announcement")} hint={t("identity.announcementHint")}><Input value={p.announcement ?? ""} onChange={(e) => setP({ ...p, announcement: e.target.value })} maxLength={300} /></Field>
      <div className="space-y-2 rounded-xl border border-amber-200 bg-amber-50/60 p-4">
        <label className="flex items-center gap-2 text-sm font-semibold"><input type="checkbox" checked={p.isOpen === false} onChange={(e) => setP({ ...p, isOpen: !e.target.checked })} className="h-5 w-5" />{t("identity.vacation")}</label>
        {p.isOpen === false && <Input placeholder={t("identity.vacationMsg")} value={p.closedMessage ?? ""} onChange={(e) => setP({ ...p, closedMessage: e.target.value })} maxLength={300} />}
        <p className="text-xs text-slate-600">{t("identity.vacationHint")}</p>
      </div>
      <ErrorText error={save.error} />{saved && <Alert tone="green">{t("common.saved")}</Alert>}
      <Button loading={save.loading} onClick={() => { setSaved(false); void save.run(); }}>{t("common.save")}</Button>
    </Card>
  );
}

type Branch = {
  id: string; name: string; code: string; phone?: string; whatsapp?: string; addressLine1?: string; city?: string; governorateCode?: string; area?: string; landmark?: string;
  mapsUrl?: string; workingHours?: WeekHours; isPickup?: boolean; isActive: boolean;
};
const EMPTY: Partial<Branch> & { address?: string } = { name: "", code: "", phone: "", whatsapp: "", address: "", city: "", governorateCode: "CAI", area: "", landmark: "", mapsUrl: "", isPickup: false, workingHours: {} };

/** Branches shown to shoppers (address, phone, hours, map). Add, edit, switch off. */
export function BranchesCard() {
  const { t, locale } = useI18n();
  const branches = useApi<Branch[]>("store/branches");
  const [form, setForm] = useState<(Partial<Branch> & { address?: string }) | null>(null);
  const save = useAction(async () => {
    if (!form) return;
    const body = { ...form, address: form.address ?? form.addressLine1 };
    if (form.id) await api(`store/branches/${form.id}`, { method: "PATCH", body });
    else await api("store/branches", { body });
    setForm(null); await branches.reload();
  });
  const toggle = useAction(async (b: Branch) => { await api(`store/branches/${b.id}`, { method: "PATCH", body: { isActive: !b.isActive } }); await branches.reload(); });
  return (
    <Card className="space-y-3">
      <div className="flex items-center justify-between"><h2 className="font-semibold">{t("settings.branches")}</h2><Button size="sm" onClick={() => setForm({ ...EMPTY })}>{t("common.add")}</Button></div>
      <ul className="divide-y text-sm">
        {(branches.data ?? []).map((b) => (
          <li key={b.id} className="flex flex-wrap items-center justify-between gap-2 py-3">
            <div>
              <div className="font-semibold">{b.name} <span className="font-mono text-xs font-normal text-slate-500">{b.code}</span> {!b.isActive && <Badge tone="slate">{t("common.hidden")}</Badge>} {b.isPickup && <Badge tone="blue">{t("identity.pickup")}</Badge>}</div>
              <div className="text-slate-600">{[b.addressLine1, b.area, governorateName(b.governorateCode, locale) || b.city].filter(Boolean).join("، ")}</div>
              {b.phone && <div dir="ltr" className="text-xs text-slate-500">{b.phone}</div>}
            </div>
            <div className="flex gap-1"><Button size="sm" variant="secondary" onClick={() => setForm({ ...b, address: b.addressLine1 })}>{t("common.edit")}</Button><Button size="sm" variant="ghost" onClick={() => toggle.run(b)}>{b.isActive ? t("common.hide") : t("common.show")}</Button></div>
          </li>
        ))}
        {branches.data?.length === 0 && <li className="py-3 text-slate-500">{t("identity.noBranches")}</li>}
      </ul>
      <ErrorText error={toggle.error} />
      <Modal open={!!form} onClose={() => setForm(null)} title={form?.id ? t("identity.editBranch") : t("identity.addBranch")} wide>
        {form && (
          <form onSubmit={(e) => { e.preventDefault(); void save.run(); }} className="space-y-3">
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label={t("products.name")}><Input value={form.name ?? ""} onChange={(e) => setForm({ ...form, name: e.target.value })} required /></Field>
              <Field label={t("identity.branchCode")}><Input dir="ltr" value={form.code ?? ""} disabled={!!form.id} onChange={(e) => setForm({ ...form, code: e.target.value })} required /></Field>
              <Field label={t("identity.phone")}><PhoneInput value={form.phone ?? ""} onValue={(v) => setForm({ ...form, phone: v })} /></Field>
              <Field label="WhatsApp"><PhoneInput value={form.whatsapp ?? ""} onValue={(v) => setForm({ ...form, whatsapp: v })} /></Field>
              <Field label={t("checkout.governorate")}><Select value={form.governorateCode ?? "CAI"} onChange={(e) => setForm({ ...form, governorateCode: e.target.value })}>{GOVERNORATES.map((g) => <option key={g.code} value={g.code}>{locale === "ar" ? g.ar : g.en}</option>)}</Select></Field>
              <Field label={t("checkout.city")}><Input value={form.city ?? ""} onChange={(e) => setForm({ ...form, city: e.target.value })} /></Field>
              <Field label={t("checkout.area")}><Input value={form.area ?? ""} onChange={(e) => setForm({ ...form, area: e.target.value })} /></Field>
              <Field label={t("checkout.landmark")}><Input value={form.landmark ?? ""} onChange={(e) => setForm({ ...form, landmark: e.target.value })} /></Field>
            </div>
            <Field label={t("identity.address")}><Input value={form.address ?? ""} onChange={(e) => setForm({ ...form, address: e.target.value })} /></Field>
            <Field label={t("identity.mapsUrl")}><Input dir="ltr" value={form.mapsUrl ?? ""} onChange={(e) => setForm({ ...form, mapsUrl: e.target.value })} placeholder="https://maps.google.com/…" /></Field>
            <label className="flex items-center gap-2 text-sm"><input type="checkbox" className="h-5 w-5" checked={!!form.isPickup} onChange={(e) => setForm({ ...form, isPickup: e.target.checked })} />{t("identity.pickupHere")}</label>
            <details className="rounded-lg border p-3"><summary className="cursor-pointer text-sm font-semibold">{t("identity.hours")}</summary><div className="mt-3"><HoursEditor value={form.workingHours ?? {}} onChange={(workingHours) => setForm({ ...form, workingHours })} /></div></details>
            <ErrorText error={save.error} />
            <Button type="submit" loading={save.loading}>{t("common.save")}</Button>
          </form>
        )}
      </Modal>
    </Card>
  );
}

type Step = { key: string; done: boolean; href: string };

/** A short checklist that tells a new owner what a complete shop needs, with a progress bar. */
export function SetupChecklist({ profile, hasLogo }: { profile: StoreProfile; hasLogo: boolean }) {
  const { t } = useI18n();
  const { can } = useMe();
  const branches = useApi<unknown[]>(can("branch.manage") ? "store/branches" : null);
  const cats = useApi<unknown[]>(can("category.manage") ? "store/categories" : null);
  const prods = useApi<{ meta?: { total: number } }>("store/products?pageSize=1");
  const ships = useApi<unknown[]>(can("shipping.manage") ? "store/shipping-methods" : null);
  const steps: Step[] = [
    { key: "logo", done: hasLogo, href: "/dashboard/settings#branding" },
    { key: "phone", done: !!profile.supportPhone, href: "/dashboard/settings#contact" },
    { key: "whatsapp", done: !!profile.whatsapp, href: "/dashboard/settings#contact" },
    { key: "address", done: !!profile.addressText, href: "/dashboard/settings#contact" },
    { key: "hours", done: !!profile.workingHours && Object.keys(profile.workingHours).length > 0, href: "/dashboard/settings#contact" },
    { key: "branch", done: (branches.data?.length ?? 0) > 0, href: "/dashboard/settings#branches" },
    { key: "category", done: (cats.data?.length ?? 0) > 0, href: "/dashboard/categories" },
    { key: "product", done: (prods.data?.meta?.total ?? 0) > 0, href: "/dashboard/products/new" },
    { key: "shipping", done: (ships.data?.length ?? 0) > 0, href: "/dashboard/settings#shipping" },
  ];
  const done = steps.filter((s) => s.done).length;
  if (done === steps.length) return null;
  return (
    <Card className="space-y-3 border-brand/30">
      <div className="flex items-center justify-between"><h2 className="font-semibold">{t("setup.title")}</h2><span className="text-sm font-semibold text-brand">{done}/{steps.length}</span></div>
      <div className="h-2 overflow-hidden rounded-full bg-slate-100"><div className="h-full rounded-full bg-brand-gradient transition-all" style={{ width: `${(done / steps.length) * 100}%` }} /></div>
      <ul className="grid gap-2 sm:grid-cols-2">
        {steps.map((s) => (
          <li key={s.key}><Link href={s.href} className={`flex items-center gap-2 rounded-lg px-3 py-2 text-sm transition hover:bg-brand-soft ${s.done ? "text-slate-400 line-through" : "font-medium"}`}><span aria-hidden>{s.done ? "✅" : "⬜"}</span>{t(`setup.${s.key}`)}</Link></li>
        ))}
      </ul>
    </Card>
  );
}

/** Ads and analytics: paste the IDs from Meta Business, TikTok Ads and Google Analytics. Nothing loads until an ID is set. */
export function MarketingCard({ profile, reload }: { profile: StoreProfile; reload: () => void }) {
  const { t } = useI18n();
  const [f, setF] = useState({ metaPixelId: profile.metaPixelId ?? "", tiktokPixelId: profile.tiktokPixelId ?? "", gaId: profile.gaId ?? "" });
  const [saved, setSaved] = useState(false);
  const save = useAction(async () => { await api("store/profile", { method: "PATCH", body: f }); setSaved(true); reload(); });
  const set = (patch: Partial<typeof f>) => { setF({ ...f, ...patch }); setSaved(false); };
  return (
    <Card className="space-y-4">
      <div><h2 className="font-semibold">📈 {t("marketing.title")}</h2><p className="text-sm text-slate-500">{t("marketing.hint")}</p></div>
      <div className="grid gap-4 sm:grid-cols-3">
        <Field label="Meta (Facebook) Pixel ID"><Input dir="ltr" value={f.metaPixelId} onChange={(e) => set({ metaPixelId: e.target.value })} placeholder="1234567890" /></Field>
        <Field label="TikTok Pixel ID"><Input dir="ltr" value={f.tiktokPixelId} onChange={(e) => set({ tiktokPixelId: e.target.value })} placeholder="C1ABCDEF…" /></Field>
        <Field label="Google Analytics ID"><Input dir="ltr" value={f.gaId} onChange={(e) => set({ gaId: e.target.value })} placeholder="G-XXXXXXXXXX" /></Field>
      </div>
      <ErrorText error={save.error} />{saved && <Alert tone="green">{t("common.saved")}</Alert>}
      <Button loading={save.loading} onClick={() => save.run()}>{t("common.save")}</Button>
    </Card>
  );
}
