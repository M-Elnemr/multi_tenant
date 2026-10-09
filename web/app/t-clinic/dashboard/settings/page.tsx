"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { toMinor, fromMinor } from "@/lib/format";
import BrandingCard from "@/components/dashboard/branding";
import { CategoryPicker, categoriesValid } from "@/components/category-picker";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useT } from "@/components/i18n-provider";
import { Alert, Button, Card, ErrorText, Field, Input, PageHeader, Textarea } from "@/components/ui";
import { PhoneInput, EmailInput } from "@/components/inputs";

type Profile = {
  clinicName: string; about?: string; phone?: string; email?: string; addressText?: string; bookingEnabled: boolean; takeNewPatients: boolean; requiresConfirmation: boolean;
  minimumBookingNoticeMinutes: number; maximumDaysAhead: number; cancellationWindowHours: number; cardEnabled: boolean; cashEnabled: boolean; cardAvailable: boolean;
};
type Doctor = { id: string; displayName: string; bio?: string; publicPhone?: string; otherSpecialty?: string | null; defaultAppointmentFeeMinor?: number; specialties: { code: string; nameEn: string; nameAr: string }[] };
type Specialty = { code: string; nameAr: string; nameEn: string; popular?: boolean };
type Branch = { id: string; name: string; code: string; city?: string };

export default function ClinicSettings() {
  const t = useT();
  const { can } = useMe();
  const profile = useApi<Profile>(can("settings.manage") ? "clinic/profile" : null);
  const mineApi = useApi<Doctor>(can("schedule.manage") ? "clinic/doctors/me" : null);
  const specialties = useApi<Specialty[]>("clinic/specialties");
  const branches = useApi<Branch[]>(can("settings.manage") ? "clinic/branches" : null);
  const [edited, setP] = useState<Profile | null>(null);
  const p = edited ?? profile.data;
  const [saved, setSaved] = useState(false);
  const save = useAction(async () => { await api("clinic/profile", { method: "PATCH", body: p }); setSaved(true); });

  // my doctor profile (only for users that have one)
  const mine = mineApi.data;
  type DoctorForm = { displayName: string; bio: string; publicPhone: string; fee: string; codes: string[]; other: string };
  const [editedDp, setDp] = useState<DoctorForm | null>(null);
  const dp: DoctorForm | null = editedDp ?? (mine ? { displayName: mine.displayName, bio: mine.bio ?? "", publicPhone: mine.publicPhone ?? "", fee: fromMinor(mine.defaultAppointmentFeeMinor), codes: mine.specialties.map((s) => s.code), other: mine.otherSpecialty ?? "" } : null);
  const saveDoctor = useAction(async () => {
    if (!dp) return;
    await api("clinic/doctors/me", { method: "PATCH", body: { fields: { displayName: dp.displayName, bio: dp.bio, publicPhone: dp.publicPhone || undefined, defaultAppointmentFeeMinor: dp.fee ? toMinor(dp.fee) : undefined, otherSpecialty: dp.codes.includes("other") ? dp.other.trim() : undefined }, specialties: dp.codes } });
    setSaved(true);
    await mineApi.reload();
  });

  const [b, setB] = useState({ name: "", code: "", city: "" });
  const addBranch = useAction(async () => { await api("clinic/branches", { body: b }); setB({ name: "", code: "", city: "" }); await branches.reload(); });

  const toggle = (k: keyof Profile) => p && (
    <label className="flex items-center justify-between rounded-lg border p-3 text-sm"><span>{t(`clinicSettings.${k}`)}</span><input type="checkbox" className="h-5 w-5" checked={Boolean(p[k])} onChange={(e) => setP({ ...p, [k]: e.target.checked })} /></label>
  );
  const num = (k: "minimumBookingNoticeMinutes" | "maximumDaysAhead" | "cancellationWindowHours") => p && (
    <Field label={t(`clinicSettings.${k}`)}><Input type="number" min={0} value={p[k]} onChange={(e) => setP({ ...p, [k]: Number(e.target.value) })} dir="ltr" /></Field>
  );

  return (
    <>
      <PageHeader title={t("nav.settings")} />
      <div className="space-y-6">
        {p && (
          <Card className="space-y-4">
            <h2 className="font-medium">{t("clinicSettings.profile")}</h2>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label={t("register.clinicName")}><Input value={p.clinicName} onChange={(e) => setP({ ...p, clinicName: e.target.value })} /></Field>
              <Field label={t("register.phone")}><PhoneInput value={p.phone ?? ""} onValue={(v) => setP({ ...p, phone: v })} /></Field>
              <Field label={t("register.email")}><EmailInput value={p.email ?? ""} onValue={(v) => setP({ ...p, email: v })} /></Field>
              <Field label={t("checkout.address")}><Input value={p.addressText ?? ""} onChange={(e) => setP({ ...p, addressText: e.target.value })} /></Field>
            </div>
            <Field label={t("settings.about")}><Textarea value={p.about ?? ""} onChange={(e) => setP({ ...p, about: e.target.value })} /></Field>
            <h3 className="pt-2 text-sm font-medium">{t("clinicSettings.booking")}</h3>
            <div className="grid gap-3 sm:grid-cols-2">{toggle("bookingEnabled")}{toggle("takeNewPatients")}{toggle("requiresConfirmation")}{toggle("cashEnabled")}{p.cardAvailable && toggle("cardEnabled")}</div>
            <div className="grid gap-3 sm:grid-cols-3">{num("minimumBookingNoticeMinutes")}{num("maximumDaysAhead")}{num("cancellationWindowHours")}</div>
            <ErrorText error={save.error} />
            {saved && <Alert tone="green">{t("common.saved")}</Alert>}
            <Button loading={save.loading} onClick={() => { setSaved(false); void save.run(); }}>{t("common.save")}</Button>
          </Card>
        )}
        {dp && (
          <Card className="space-y-4">
            <h2 className="font-medium">{t("clinicSettings.myProfile")}</h2>
            <Field label={t("clinicSettings.displayName")}><Input value={dp.displayName} onChange={(e) => setDp({ ...dp, displayName: e.target.value })} /></Field>
            <Field label={t("clinicSettings.doctorPhone")} hint={t("clinicSettings.doctorPhoneHint")}><PhoneInput value={dp.publicPhone} onValue={(v) => setDp({ ...dp, publicPhone: v })} /></Field>
            <Field label={t("clinicSettings.bio")}><Textarea value={dp.bio} onChange={(e) => setDp({ ...dp, bio: e.target.value })} /></Field>
            <Field label={t("clinicSettings.fee")} hint={t("services.priceHint")}><Input value={dp.fee} onChange={(e) => setDp({ ...dp, fee: e.target.value })} inputMode="decimal" dir="ltr" className="max-w-40" /></Field>
            <div>
              <p className="mb-2 text-sm font-medium">{t("clinicSettings.specialties")}</p>
              {specialties.data && <CategoryPicker options={specialties.data} value={dp.codes} onChange={(codes) => setDp({ ...dp, codes })} other={dp.other} onOther={(other) => setDp({ ...dp, other })} />}
            </div>
            <ErrorText error={saveDoctor.error} />
            <Button loading={saveDoctor.loading} disabled={!categoriesValid(dp.codes, dp.other)} onClick={() => { setSaved(false); void saveDoctor.run(); }}>{t("common.save")}</Button>
          </Card>
        )}
        {can("settings.manage") && <BrandingCard />}
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
