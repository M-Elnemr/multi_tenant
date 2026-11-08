"use client";

import { useState } from "react";
import Link from "next/link";
import { api } from "@/lib/client";
import { toMinor, fromMinor } from "@/lib/format";
import BrandingCard from "@/components/dashboard/branding";
import { CategoryPicker, categoriesValid } from "@/components/category-picker";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useT } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { Switch } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { Button, ErrorText, Field, Input, Loading, PageHeader, Textarea } from "@/components/ui";
import { PhoneInput, EmailInput } from "@/components/inputs";
import { BranchesWebsiteCard, DoctorsWebsiteCard, WebsiteCard } from "@/components/dashboard/clinic-website";
import { Paper, PaperHead, Pill, linkBtn } from "@/components/clinic/dash";

type Profile = {
  clinicName: string; about?: string; phone?: string; email?: string; addressText?: string; bookingEnabled: boolean; takeNewPatients: boolean; requiresConfirmation: boolean;
  minimumBookingNoticeMinutes: number; maximumDaysAhead: number; cancellationWindowHours: number; cardEnabled: boolean; cashEnabled: boolean; cardAvailable: boolean;
};
type Doctor = { id: string; displayName: string; bio?: string; publicPhone?: string; otherSpecialty?: string | null; defaultAppointmentFeeMinor?: number; specialties: { code: string; nameEn: string; nameAr: string }[] };
type Specialty = { code: string; nameAr: string; nameEn: string; popular?: boolean };
type Branch = { id: string; name: string; code: string; city?: string };

export default function ClinicSettings() {
  const t = useT();
  const toast = useToast();
  const { can, canWrite } = useMe();
  const readSettings = can("settings.manage") || can("settings.read");   // read only access still sees the page, with every field disabled
  const profile = useApi<Profile>(readSettings ? "clinic/profile" : null);
  const mineApi = useApi<Doctor>(can("schedule.manage") || can("schedule.read") ? "clinic/doctors/me" : null);
  const specialties = useApi<Specialty[]>("clinic/specialties");
  const branches = useApi<Branch[]>(readSettings ? "clinic/branches" : null);
  const [edited, setP] = useState<Profile | null>(null);
  const p = edited ?? profile.data;
  const save = useAction(async () => { await api("clinic/profile", { method: "PATCH", body: p }); toast(t("common.saved")); });

  // my doctor profile (only for users that have one)
  const mine = mineApi.data;
  type DoctorForm = { displayName: string; bio: string; publicPhone: string; fee: string; codes: string[]; other: string };
  const [editedDp, setDp] = useState<DoctorForm | null>(null);
  const dp: DoctorForm | null = editedDp ?? (mine ? { displayName: mine.displayName, bio: mine.bio ?? "", publicPhone: mine.publicPhone ?? "", fee: fromMinor(mine.defaultAppointmentFeeMinor), codes: mine.specialties.map((s) => s.code), other: mine.otherSpecialty ?? "" } : null);
  const saveDoctor = useAction(async () => {
    if (!dp) return;
    await api("clinic/doctors/me", { method: "PATCH", body: { fields: { displayName: dp.displayName, bio: dp.bio, publicPhone: dp.publicPhone || undefined, defaultAppointmentFeeMinor: dp.fee ? toMinor(dp.fee) : undefined, otherSpecialty: dp.codes.includes("other") ? dp.other.trim() : undefined }, specialties: dp.codes } });
    await mineApi.reload();
    toast(t("common.saved"));
  });

  const [b, setB] = useState({ name: "", code: "", city: "" });
  const addBranch = useAction(async () => { await api("clinic/branches", { body: b }); setB({ name: "", code: "", city: "" }); await branches.reload(); toast(t("common.saved")); });

  const toggle = (k: keyof Profile) => p && (
    <div key={k} className="flex items-center justify-between gap-3 rounded-[14px] bg-surface-2 p-3.5 ring-1 ring-inset ring-line">
      <span className="min-w-0 text-[13.5px] font-medium text-ink-2">{t(`clinicSettings.${k}`)}</span>
      <Switch checked={Boolean(p[k])} onChange={(v) => setP({ ...p, [k]: v })} label={t(`clinicSettings.${k}`)} disabled={!canWrite("settings")} />
    </div>
  );
  const num = (k: "minimumBookingNoticeMinutes" | "maximumDaysAhead" | "cancellationWindowHours") => p && (
    <Field label={t(`clinicSettings.${k}`)}><Input type="number" min={0} value={p[k]} onChange={(e) => setP({ ...p, [k]: Number(e.target.value) })} dir="ltr" /></Field>
  );

  if (readSettings && profile.loading && !profile.data) return <Loading variant="detail" />;
  const jumps: [string, string][] = [["profile", t("clinicSettings.profile")], ...(dp ? [["doctor", t("clinicSettings.myProfile")] as [string, string]] : []), ...(can("settings.manage") ? [["site", t("cld.siteAndBrand")] as [string, string]] : []), ...(branches.data ? [["branches", t("settings.branches")] as [string, string]] : [])];

  return (
    <>
      <PageHeader eyebrow={t("navgrp.setup")} title={t("nav.settings")} subtitle={t("cld.settingsSubtitle")}
        actions={can("settings.manage") ? <a href="/?edit=1" className={linkBtn.secondary}><Icon name="edit" className="h-4 w-4" />{t("site.customize")}</a> : undefined} />
      <div className="space-y-5">
        <ErrorText error={profile.error} />
        {jumps.length > 1 && (
          <nav className="-mx-1 flex gap-1.5 overflow-x-auto px-1 pb-1 [scrollbar-width:none]" aria-label={t("nav.settings")}>
            {jumps.map(([k, label]) => <a key={k} href={`#cs-${k}`} className="chip">{label}</a>)}
          </nav>
        )}
        {p && (
          <fieldset disabled={!canWrite("settings")} className="m-0 min-w-0 border-0 p-0">
            <Paper id="cs-profile">
              <PaperHead icon="building" title={t("clinicSettings.profile")} />
              <div className="space-y-5 px-5 pb-5">
                <div className="grid gap-4 sm:grid-cols-2">
                  <Field label={t("register.clinicName")}><Input value={p.clinicName} onChange={(e) => setP({ ...p, clinicName: e.target.value })} /></Field>
                  <Field label={t("register.phone")}><PhoneInput value={p.phone ?? ""} onValue={(v) => setP({ ...p, phone: v })} /></Field>
                  <Field label={t("register.email")}><EmailInput value={p.email ?? ""} onValue={(v) => setP({ ...p, email: v })} /></Field>
                  <Field label={t("checkout.address")}><Input value={p.addressText ?? ""} onChange={(e) => setP({ ...p, addressText: e.target.value })} /></Field>
                </div>
                <Field label={t("settings.about")}><Textarea value={p.about ?? ""} onChange={(e) => setP({ ...p, about: e.target.value })} /></Field>
                <div>
                  <h3 className="mb-3 font-display text-[14.5px] font-semibold text-ink">{t("clinicSettings.booking")}</h3>
                  <div className="grid gap-3 sm:grid-cols-2">{toggle("bookingEnabled")}{toggle("takeNewPatients")}{toggle("requiresConfirmation")}{toggle("cashEnabled")}{p.cardAvailable && toggle("cardEnabled")}</div>
                  <div className="mt-4 grid gap-3 sm:grid-cols-3">{num("minimumBookingNoticeMinutes")}{num("maximumDaysAhead")}{num("cancellationWindowHours")}</div>
                </div>
                <ErrorText error={save.error} />
                <Button loading={save.loading} onClick={() => void save.run()}>{t("common.save")}</Button>
              </div>
            </Paper>
          </fieldset>
        )}
        {dp && (
          <fieldset disabled={!canWrite("schedule")} className="m-0 min-w-0 border-0 p-0">
            <Paper id="cs-doctor">
              <PaperHead icon="doctor" title={t("clinicSettings.myProfile")} />
              <div className="space-y-4 px-5 pb-5">
                <div className="grid gap-4 sm:grid-cols-2">
                  <Field label={t("clinicSettings.displayName")}><Input value={dp.displayName} onChange={(e) => setDp({ ...dp, displayName: e.target.value })} /></Field>
                  <Field label={t("clinicSettings.doctorPhone")} hint={t("clinicSettings.doctorPhoneHint")}><PhoneInput value={dp.publicPhone} onValue={(v) => setDp({ ...dp, publicPhone: v })} /></Field>
                </div>
                <Field label={t("clinicSettings.bio")}><Textarea value={dp.bio} onChange={(e) => setDp({ ...dp, bio: e.target.value })} /></Field>
                <Field label={t("clinicSettings.fee")} hint={t("services.priceHint")}><Input value={dp.fee} onChange={(e) => setDp({ ...dp, fee: e.target.value })} inputMode="decimal" dir="ltr" className="max-w-40" /></Field>
                <div>
                  <p className="mb-2 text-[13px] font-semibold text-ink-2">{t("clinicSettings.specialties")}</p>
                  {specialties.data && <CategoryPicker options={specialties.data} value={dp.codes} onChange={(codes) => setDp({ ...dp, codes })} other={dp.other} onOther={(other) => setDp({ ...dp, other })} />}
                </div>
                <ErrorText error={saveDoctor.error} />
                <Button loading={saveDoctor.loading} disabled={!categoriesValid(dp.codes, dp.other)} onClick={() => void saveDoctor.run()}>{t("common.save")}</Button>
              </div>
            </Paper>
          </fieldset>
        )}
        {can("settings.manage") && (
          <div id="cs-site" className="scroll-mt-24 space-y-5">
            <WebsiteCard />
            <DoctorsWebsiteCard />
            <BranchesWebsiteCard />
            <BrandingCard />
            <Link href="/dashboard/website" className="group flex items-center justify-between gap-3 rounded-[18px] bg-surface p-4 shadow-card transition hover:bg-surface-2">
              <span className="flex items-center gap-3 text-sm font-semibold text-ink"><Icon name="palette" className="h-5 w-5 text-[var(--rail-accent-ink)]" />{t("nav.websiteDesign")}</span>
              <Icon name="chevron" className="h-4 w-4 text-faint flip-rtl" />
            </Link>
          </div>
        )}
        {branches.data && (
          <Paper id="cs-branches">
            <PaperHead icon="pin" title={t("settings.branches")} action={<Pill>{branches.data.length}</Pill>} />
            <div className="space-y-4 px-5 pb-5">
              <ul className="divide-y divide-line rounded-[14px] ring-1 ring-line">{branches.data.map((x) => <li key={x.id} className="flex flex-wrap items-center gap-x-3 px-4 py-3 text-sm"><span className="font-semibold text-ink">{x.name}</span><span className="font-mono text-xs text-faint">{x.code}</span>{x.city && <span className="text-muted">{x.city}</span>}</li>)}</ul>
              {(
                <form onSubmit={(e) => { e.preventDefault(); void addBranch.run(); }} className="grid gap-3 sm:grid-cols-[1fr_9rem_1fr_auto]">
                  <Input placeholder={t("products.name")} value={b.name} onChange={(e) => setB({ ...b, name: e.target.value })} required />
                  <Input placeholder="CODE" value={b.code} onChange={(e) => setB({ ...b, code: e.target.value })} dir="ltr" required />
                  <Input placeholder={t("checkout.city")} value={b.city} onChange={(e) => setB({ ...b, city: e.target.value })} />
                  <Button type="submit" loading={addBranch.loading}>{t("common.add")}</Button>
                </form>
              )}
              <ErrorText error={addBranch.error} />
            </div>
          </Paper>
        )}
      </div>
    </>
  );
}
