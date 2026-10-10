"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { toMinor, fromMinor } from "@/lib/format";
import { defaultHours, type WeekHours } from "@/lib/hours";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { PhoneInput } from "../inputs";
import { ImageUploader } from "../uploader";
import { Alert, Button, Card, ErrorText, Field, Input, Textarea } from "../ui";
import { HoursEditor } from "./hours-editor";

type Identity = {
  tagline?: string; whatsapp?: string; extraPhones?: string[]; facebookUrl?: string; instagramUrl?: string; tiktokUrl?: string; websiteUrl?: string; mapsUrl?: string;
  workingHours?: WeekHours; coverFileId?: string | null; gallery?: string[]; announcement?: string; isOpen?: boolean; closedMessage?: string;
  insurance?: string[]; faqs?: { q: string; a: string }[]; establishedYear?: number | null;
};
const thumb = (id: string) => `/api/bff/files/${id}/content?variant=thumb`;
const list = (s: string) => s.split(/[,،]/).map((x) => x.trim()).filter(Boolean);

/** Everything the public clinic website shows besides the basics: contact, hours, photos, insurance, FAQs, announcement. */
export function WebsiteCard() {
  const { t } = useI18n();
  const profile = useApi<Identity>("clinic/profile");
  const [edited, setEdited] = useState<Identity | null>(null);
  const [saved, setSaved] = useState(false);
  const p = edited ?? profile.data;
  const save = useAction(async () => {
    if (!p) return;
    const { tagline, whatsapp, extraPhones, facebookUrl, instagramUrl, tiktokUrl, websiteUrl, mapsUrl, workingHours, coverFileId, gallery, announcement, isOpen, closedMessage, insurance, faqs, establishedYear } = p;
    await api("clinic/profile", { method: "PATCH", body: { tagline, whatsapp, extraPhones, facebookUrl, instagramUrl, tiktokUrl, websiteUrl, mapsUrl, workingHours, coverFileId: coverFileId ?? "", gallery, announcement, isOpen, closedMessage, insurance, faqs, establishedYear } });
    setSaved(true); setEdited(null); await profile.reload();
  });
  if (!p) return null;
  const set = (patch: Partial<Identity>) => { setSaved(false); setEdited({ ...p, ...patch }); };
  const faqs = p.faqs ?? [];
  const gallery = p.gallery ?? [];
  return (
    <Card className="space-y-6">
      <h2 className="font-semibold">{t("cweb.title")}</h2>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label={t("cweb.tagline")} hint={t("cweb.taglineHint")}><Input value={p.tagline ?? ""} maxLength={200} onChange={(e) => set({ tagline: e.target.value })} /></Field>
        <Field label={t("cweb.established")}><Input type="number" dir="ltr" min={1900} max={2100} value={p.establishedYear ?? ""} onChange={(e) => set({ establishedYear: e.target.value ? Number(e.target.value) : null })} className="max-w-40" /></Field>
        <Field label="WhatsApp" hint={t("identity.whatsappHint")}><PhoneInput value={p.whatsapp ?? ""} onValue={(v) => set({ whatsapp: v })} /></Field>
        <Field label={t("identity.extraPhones")} hint={t("identity.extraPhonesHint")}><Input dir="ltr" value={(p.extraPhones ?? []).join(", ")} onChange={(e) => set({ extraPhones: list(e.target.value) })} /></Field>
      </div>
      <Field label={t("identity.mapsUrl")} hint={t("identity.mapsHint")}><Input dir="ltr" placeholder="https://maps.google.com/…" value={p.mapsUrl ?? ""} onChange={(e) => set({ mapsUrl: e.target.value })} /></Field>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Facebook"><Input dir="ltr" placeholder="https://facebook.com/…" value={p.facebookUrl ?? ""} onChange={(e) => set({ facebookUrl: e.target.value })} /></Field>
        <Field label="Instagram"><Input dir="ltr" placeholder="https://instagram.com/…" value={p.instagramUrl ?? ""} onChange={(e) => set({ instagramUrl: e.target.value })} /></Field>
        <Field label="TikTok"><Input dir="ltr" placeholder="https://tiktok.com/@…" value={p.tiktokUrl ?? ""} onChange={(e) => set({ tiktokUrl: e.target.value })} /></Field>
        <Field label={t("identity.website")}><Input dir="ltr" placeholder="https://…" value={p.websiteUrl ?? ""} onChange={(e) => set({ websiteUrl: e.target.value })} /></Field>
      </div>

      <div className="space-y-2">
        <h3 className="text-sm font-semibold text-slate-700">{t("identity.hours")}</h3>
        <HoursEditor value={p.workingHours ?? {}} onChange={(workingHours) => set({ workingHours })} />
        <Button size="sm" variant="ghost" onClick={() => set({ workingHours: defaultHours() })}>{t("identity.hoursDefault")}</Button>
      </div>

      <div className="space-y-2">
        <h3 className="text-sm font-semibold text-slate-700">{t("identity.cover")}</h3>
        <div className="flex flex-wrap items-center gap-3">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          {p.coverFileId && <img src={thumb(p.coverFileId)} alt="" className="h-16 w-28 rounded-lg object-cover" />}
          <ImageUploader category="PRODUCT_IMAGE" label={t("identity.coverUpload")} onUploaded={(id) => set({ coverFileId: id })} />
          {p.coverFileId && <Button size="sm" variant="ghost" onClick={() => set({ coverFileId: null })}>{t("common.remove")}</Button>}
        </div>
        <p className="text-xs text-slate-500">{t("identity.coverHint")}</p>
      </div>

      <div className="space-y-2">
        <h3 className="text-sm font-semibold text-slate-700">{t("cweb.gallery")}</h3>
        <div className="flex flex-wrap gap-3">
          {gallery.map((id) => (
            <div key={id} className="relative">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={thumb(id)} alt="" className="h-20 w-20 rounded-lg object-cover" />
              <button type="button" aria-label={t("common.remove")} onClick={() => set({ gallery: gallery.filter((x) => x !== id) })} className="absolute -end-2 -top-2 grid h-6 w-6 place-items-center rounded-full bg-red-600 text-xs text-white shadow">✕</button>
            </div>
          ))}
          {gallery.length < 12 && <ImageUploader category="PRODUCT_IMAGE" label={t("cweb.galleryAdd")} onUploaded={(id) => set({ gallery: [...gallery, id] })} />}
        </div>
        <p className="text-xs text-slate-500">{t("cweb.galleryHint")}</p>
      </div>

      <Field label={t("cweb.insurance")}><Input value={(p.insurance ?? []).join(", ")} onChange={(e) => set({ insurance: list(e.target.value) })} /></Field>

      <div className="space-y-3">
        <h3 className="text-sm font-semibold text-slate-700">{t("cweb.faqs")}</h3>
        {faqs.map((f, i) => (
          <div key={i} className="space-y-2 rounded-xl border border-slate-200 p-3">
            <Input placeholder={t("cweb.faqQ")} value={f.q} maxLength={200} onChange={(e) => set({ faqs: faqs.map((x, j) => (j === i ? { ...x, q: e.target.value } : x)) })} />
            <Textarea rows={2} placeholder={t("cweb.faqA")} value={f.a} maxLength={1500} onChange={(e) => set({ faqs: faqs.map((x, j) => (j === i ? { ...x, a: e.target.value } : x)) })} />
            <Button size="sm" variant="ghost" onClick={() => set({ faqs: faqs.filter((_, j) => j !== i) })}>{t("common.remove")}</Button>
          </div>
        ))}
        {faqs.length < 20 && <Button size="sm" variant="secondary" onClick={() => set({ faqs: [...faqs, { q: "", a: "" }] })}>{t("cweb.addFaq")}</Button>}
      </div>

      <Field label={t("identity.announcement")} hint={t("identity.announcementHint")}><Input value={p.announcement ?? ""} maxLength={300} onChange={(e) => set({ announcement: e.target.value })} /></Field>
      <div className="space-y-2 rounded-xl border border-amber-200 bg-amber-50/60 p-4">
        <label className="flex items-center gap-2 text-sm font-semibold"><input type="checkbox" checked={p.isOpen === false} onChange={(e) => set({ isOpen: !e.target.checked })} className="h-5 w-5" />{t("cweb.closed")}</label>
        {p.isOpen === false && <Input placeholder={t("identity.vacationMsg")} value={p.closedMessage ?? ""} maxLength={300} onChange={(e) => set({ closedMessage: e.target.value })} />}
        <p className="text-xs text-slate-600">{t("cweb.closedHint")}</p>
      </div>

      <ErrorText error={save.error} />{saved && <Alert tone="green">{t("common.saved")}</Alert>}
      <Button loading={save.loading} onClick={() => { setSaved(false); void save.run(); }}>{t("common.save")}</Button>
    </Card>
  );
}

type SpecialtyRef = { code: string; nameAr: string; nameEn: string };
type DoctorRow = {
  id: string; displayName: string; bio?: string; publicPhone?: string; defaultAppointmentFeeMinor?: number | null; profileImageFileId?: string | null;
  yearsExperience?: number | null; qualifications?: string; languages?: string[]; specialties: SpecialtyRef[];
};

/** The owner edits how each doctor appears on the website: photo, bio, experience, qualifications, languages, fee. */
export function DoctorsWebsiteCard() {
  const { t, locale } = useI18n();
  const doctors = useApi<DoctorRow[]>("clinic/doctors");
  const [open, setOpen] = useState<string | null>(null);
  const [form, setForm] = useState<DoctorRow & { fee: string } | null>(null);
  const [saved, setSaved] = useState(false);
  const start = (d: DoctorRow) => { setSaved(false); setOpen(d.id); setForm({ ...d, fee: fromMinor(d.defaultAppointmentFeeMinor ?? undefined) }); };
  const save = useAction(async () => {
    if (!form) return;
    await api(`clinic/doctors/${form.id}`, { method: "PATCH", body: { fields: {
      displayName: form.displayName, bio: form.bio ?? "", publicPhone: form.publicPhone || undefined, qualifications: form.qualifications ?? "", yearsExperience: form.yearsExperience ?? null,
      languages: form.languages ?? [], profileImageFileId: form.profileImageFileId ?? "", defaultAppointmentFeeMinor: form.fee ? toMinor(form.fee) : undefined } } });
    setSaved(true); await doctors.reload();
  });
  if (!doctors.data?.length) return null;
  return (
    <Card className="space-y-3">
      <h2 className="font-semibold">{t("cweb.doctors")}</h2>
      <ul className="divide-y text-sm">
        {doctors.data.map((d) => (
          <li key={d.id} className="py-3">
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-3">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                {d.profileImageFileId ? <img src={thumb(d.profileImageFileId)} alt="" className="h-10 w-10 rounded-full object-cover" /> : <span className="grid h-10 w-10 place-items-center rounded-full bg-slate-100 font-bold text-slate-500">{d.displayName.charAt(0)}</span>}
                <div><div className="font-semibold">{d.displayName}</div><div className="text-xs text-slate-500">{d.specialties.map((s) => (locale === "ar" ? s.nameAr : s.nameEn)).join(" · ")}</div></div>
              </div>
              <Button size="sm" variant="secondary" onClick={() => (open === d.id ? setOpen(null) : start(d))}>{open === d.id ? t("common.cancel") : t("common.edit")}</Button>
            </div>
            {open === d.id && form && (
              <div className="mt-4 space-y-4 rounded-xl bg-slate-50 p-4">
                <div className="flex items-center gap-3">
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  {form.profileImageFileId && <img src={thumb(form.profileImageFileId)} alt="" className="h-16 w-16 rounded-xl object-cover" />}
                  <ImageUploader category="PRODUCT_IMAGE" label={t("cweb.photo")} onUploaded={(id) => setForm({ ...form, profileImageFileId: id })} />
                  {form.profileImageFileId && <Button size="sm" variant="ghost" onClick={() => setForm({ ...form, profileImageFileId: null })}>{t("common.remove")}</Button>}
                </div>
                <div className="grid gap-4 sm:grid-cols-2">
                  <Field label={t("clinicSettings.displayName")}><Input value={form.displayName} onChange={(e) => setForm({ ...form, displayName: e.target.value })} /></Field>
                  <Field label={t("clinicSettings.doctorPhone")} hint={t("clinicSettings.doctorPhoneHint")}><PhoneInput value={form.publicPhone ?? ""} onValue={(v) => setForm({ ...form, publicPhone: v })} /></Field>
                  <Field label={t("cweb.years")}><Input type="number" dir="ltr" min={0} max={80} value={form.yearsExperience ?? ""} onChange={(e) => setForm({ ...form, yearsExperience: e.target.value ? Number(e.target.value) : null })} className="max-w-32" /></Field>
                  <Field label={t("clinicSettings.fee")} hint={t("services.priceHint")}><Input inputMode="decimal" dir="ltr" value={form.fee} onChange={(e) => setForm({ ...form, fee: e.target.value })} className="max-w-40" /></Field>
                </div>
                <Field label={t("cweb.qualifications")}><Input value={form.qualifications ?? ""} maxLength={400} onChange={(e) => setForm({ ...form, qualifications: e.target.value })} /></Field>
                <Field label={t("cweb.languages")}><Input value={(form.languages ?? []).join(", ")} onChange={(e) => setForm({ ...form, languages: list(e.target.value) })} /></Field>
                <Field label={t("clinicSettings.bio")}><Textarea value={form.bio ?? ""} onChange={(e) => setForm({ ...form, bio: e.target.value })} /></Field>
                <ErrorText error={save.error} />{saved && <Alert tone="green">{t("common.saved")}</Alert>}
                <Button loading={save.loading} onClick={() => { setSaved(false); void save.run(); }}>{t("common.save")}</Button>
              </div>
            )}
          </li>
        ))}
      </ul>
    </Card>
  );
}

type BranchRow = { id: string; name: string; code: string; addressLine1?: string; city?: string; phone?: string; whatsapp?: string; mapsUrl?: string; landmark?: string; workingHours?: WeekHours };

/** Edit a location's address, phone, WhatsApp, map link, landmark and its own opening hours. */
export function BranchesWebsiteCard() {
  const { t } = useI18n();
  const branches = useApi<BranchRow[]>("clinic/branches");
  const [form, setForm] = useState<BranchRow | null>(null);
  const [saved, setSaved] = useState(false);
  const save = useAction(async () => {
    if (!form) return;
    const { name, addressLine1, city, phone, whatsapp, mapsUrl, landmark, workingHours } = form;
    await api(`clinic/branches/${form.id}`, { method: "PATCH", body: { name, addressLine1, city, phone, whatsapp, mapsUrl, landmark, workingHours } });
    setSaved(true); await branches.reload();
  });
  if (!branches.data?.length) return null;
  return (
    <Card className="space-y-3">
      <h2 className="font-semibold">{t("cweb.branches")}</h2>
      <ul className="divide-y text-sm">
        {branches.data.map((b) => (
          <li key={b.id} className="py-3">
            <div className="flex items-center justify-between gap-3">
              <div><span className="font-semibold">{b.name}</span> <span className="font-mono text-xs text-slate-500">{b.code}</span><div className="text-slate-600">{[b.addressLine1, b.city].filter(Boolean).join("، ")}</div></div>
              <Button size="sm" variant="secondary" onClick={() => { setSaved(false); setForm(form?.id === b.id ? null : { ...b }); }}>{form?.id === b.id ? t("common.cancel") : t("common.edit")}</Button>
            </div>
            {form?.id === b.id && (
              <div className="mt-4 space-y-4 rounded-xl bg-slate-50 p-4">
                <div className="grid gap-4 sm:grid-cols-2">
                  <Field label={t("products.name")}><Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></Field>
                  <Field label={t("checkout.city")}><Input value={form.city ?? ""} onChange={(e) => setForm({ ...form, city: e.target.value })} /></Field>
                  <Field label={t("identity.address")}><Input value={form.addressLine1 ?? ""} onChange={(e) => setForm({ ...form, addressLine1: e.target.value })} /></Field>
                  <Field label={t("cweb.landmark")}><Input value={form.landmark ?? ""} maxLength={200} onChange={(e) => setForm({ ...form, landmark: e.target.value })} /></Field>
                  <Field label={t("identity.phone")}><PhoneInput value={form.phone ?? ""} onValue={(v) => setForm({ ...form, phone: v })} /></Field>
                  <Field label="WhatsApp"><PhoneInput value={form.whatsapp ?? ""} onValue={(v) => setForm({ ...form, whatsapp: v })} /></Field>
                </div>
                <Field label={t("identity.mapsUrl")} hint={t("identity.mapsHint")}><Input dir="ltr" value={form.mapsUrl ?? ""} onChange={(e) => setForm({ ...form, mapsUrl: e.target.value })} /></Field>
                <div className="space-y-2">
                  <h3 className="text-sm font-semibold text-slate-700">{t("identity.hours")}</h3>
                  <HoursEditor value={form.workingHours ?? {}} onChange={(workingHours) => setForm({ ...form, workingHours })} />
                </div>
                <ErrorText error={save.error} />{saved && <Alert tone="green">{t("common.saved")}</Alert>}
                <Button loading={save.loading} onClick={() => { setSaved(false); void save.run(); }}>{t("common.save")}</Button>
              </div>
            )}
          </li>
        ))}
      </ul>
    </Card>
  );
}
