"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { defaultHours, type WeekHours } from "@/lib/hours";
import { BranchesWebsiteCard, DoctorsWebsiteCard } from "../dashboard/clinic-website";
import { HoursEditor } from "../dashboard/hours-editor";
import { useI18n } from "../i18n-provider";
import { EmailInput, PhoneInput } from "../inputs";
import { Button, Field, Input } from "../ui";
import { DrawerShell } from "./drawer-shell";
import { AreaField, FaqField, GalleryField, Group, LogoField, OpenStatusField, PhotoField, TagsField, TextField, validUrl } from "./fields";
import { useSectionForm } from "./use-section-form";

type P = {
  clinicName: string; about: string; phone: string; email: string; addressText: string; tagline: string; whatsapp: string; extraPhones: string[];
  facebookUrl: string; instagramUrl: string; tiktokUrl: string; websiteUrl: string; mapsUrl: string; workingHours: WeekHours; coverFileId: string | null;
  gallery: string[]; announcement: string; isOpen: boolean; closedMessage: string; insurance: string[]; faqs: { q: string; a: string }[]; establishedYear: number | null;
};
const PATH = "clinic/profile";
/** The server wants "" (not null) to clear the cover. */
const toBody = (c: Partial<P>): Record<string, unknown> => ({ ...c, ...("coverFileId" in c ? { coverFileId: c.coverFileId ?? "" } : {}) });
const list = (s: string) => s.split(/[,،]/).map((x) => x.trim()).filter(Boolean);

function useClinicForm(keys: readonly (keyof P & string)[], validate?: (v: Partial<P>) => Record<string, string>) {
  const router = useRouter();
  return useSectionForm<P>(PATH, keys, { toBody, validate, onSaved: () => router.refresh() });
}
const urlErr = (t: (k: string) => string, ...pairs: [string, string | undefined][]) => Object.fromEntries(pairs.filter(([, v]) => !validUrl(v)).map(([k]) => [k, t("se.badUrl")]));

export type EditorProps = { onClose: () => void };

export function HeroEditor({ onClose }: EditorProps) {
  const { t } = useI18n();
  const f = useClinicForm(["clinicName", "tagline", "coverFileId"], (v): Record<string, string> => (v.clinicName?.trim() ? {} : { clinicName: t("se.required") }));
  return (
    <DrawerShell icon="stethoscope" title={t("site.section.hero")} description={t("se.d.hero")} form={f} onClose={onClose}>
      <LogoField />
      <TextField label={t("register.clinicName")} value={f.v.clinicName} max={120} error={f.errors.clinicName} onChange={(v) => f.set({ clinicName: v })} />
      <TextField label={t("cweb.tagline")} hint={t("cweb.taglineHint")} value={f.v.tagline} max={200} onChange={(v) => f.set({ tagline: v })} />
      <PhotoField label={t("identity.cover")} hint={t("identity.coverHint")} value={f.v.coverFileId} onChange={(id) => f.set({ coverFileId: id })} />
    </DrawerShell>
  );
}

export function AboutEditor({ onClose }: EditorProps) {
  const { t } = useI18n();
  const f = useClinicForm(["about", "establishedYear"]);
  return (
    <DrawerShell icon="file" title={t("site.section.about")} description={t("se.d.about")} form={f} onClose={onClose}>
      <AreaField label={t("settings.about")} hint={t("se.aboutHint")} value={f.v.about} max={2000} rows={8} onChange={(v) => f.set({ about: v })} />
      <Field label={t("cweb.established")}><Input type="number" dir="ltr" min={1900} max={2100} value={f.v.establishedYear ?? ""} onChange={(e) => f.set({ establishedYear: e.target.value ? Number(e.target.value) : null })} className="max-w-[10rem]" /></Field>
    </DrawerShell>
  );
}

export function LinkEditor({ onClose, title, description, icon, href, label }: EditorProps & { title: string; description: string; icon: "services" | "box" | "tag" | "star"; href: string; label: string }) {
  return (
    <DrawerShell icon={icon} title={title} description={description} onClose={onClose}>
      <Link href={href} className="flex items-center justify-between rounded-2xl border border-brand/20 bg-brand-soft px-5 py-4 font-bold text-brand-dark transition hover:shadow-md">{label}<span aria-hidden>→</span></Link>
    </DrawerShell>
  );
}

export function InfoEditor({ onClose, title, description, icon, body }: EditorProps & { title: string; description: string; icon: "sparkles" | "services" | "chart" | "star" | "tag"; body: string }) {
  return (
    <DrawerShell icon={icon} title={title} description={description} onClose={onClose}>
      <p className="rounded-2xl bg-slate-50 p-4 text-sm leading-relaxed text-slate-600">{body}</p>
    </DrawerShell>
  );
}

export function DoctorsEditor({ onClose }: EditorProps) {
  const { t } = useI18n();
  return <DrawerShell icon="users" title={t("site.section.doctors")} description={t("se.d.doctors")} onClose={onClose} wide><DoctorsWebsiteCard /></DrawerShell>;
}

export function VisitEditor({ onClose }: EditorProps) {
  const { t } = useI18n();
  const f = useClinicForm(["addressText", "mapsUrl", "workingHours"], (v) => urlErr(t, ["mapsUrl", v.mapsUrl]));
  return (
    <DrawerShell icon="pin" title={t("site.section.visit")} description={t("se.d.visit")} form={f} onClose={onClose} wide>
      <TextField label={t("checkout.address")} value={f.v.addressText} max={300} onChange={(v) => f.set({ addressText: v })} />
      <TextField label={t("identity.mapsUrl")} hint={t("identity.mapsHint")} ltr placeholder="https://maps.google.com/…" value={f.v.mapsUrl} max={500} error={f.errors.mapsUrl} onChange={(v) => f.set({ mapsUrl: v })} />
      <Group title={t("identity.hours")}>
        <HoursEditor value={f.v.workingHours ?? {}} onChange={(workingHours) => f.set({ workingHours })} />
        <Button size="sm" variant="ghost" onClick={() => f.set({ workingHours: defaultHours() })}>{t("identity.hoursDefault")}</Button>
      </Group>
      <Group title={t("settings.branches")} hint={t("se.branchesHint")}><BranchesWebsiteCard /></Group>
    </DrawerShell>
  );
}

export function GalleryEditor({ onClose }: EditorProps) {
  const { t } = useI18n();
  const f = useClinicForm(["gallery"]);
  return (
    <DrawerShell icon="sparkles" title={t("site.section.gallery")} description={t("se.d.gallery")} form={f} onClose={onClose}>
      <GalleryField ids={f.v.gallery ?? []} onChange={(gallery) => f.set({ gallery })} />
    </DrawerShell>
  );
}

export function InsuranceEditor({ onClose }: EditorProps) {
  const { t } = useI18n();
  const f = useClinicForm(["insurance"]);
  return (
    <DrawerShell icon="shield" title={t("site.section.insurance")} description={t("se.d.insurance")} form={f} onClose={onClose}>
      <TagsField label={t("se.insuranceLabel")} hint={t("se.insuranceHint")} values={f.v.insurance ?? []} onChange={(insurance) => f.set({ insurance })} />
    </DrawerShell>
  );
}

export function FaqEditor({ onClose }: EditorProps) {
  const { t } = useI18n();
  const f = useClinicForm(["faqs"]);
  return (
    <DrawerShell icon="chat" title={t("site.section.faq")} description={t("se.d.faq")} form={f} onClose={onClose} wide>
      <FaqField items={f.v.faqs ?? []} onChange={(faqs) => f.set({ faqs })} />
    </DrawerShell>
  );
}

export function ContactEditor({ onClose }: EditorProps) {
  const { t } = useI18n();
  const f = useClinicForm(["phone", "email", "whatsapp", "extraPhones", "facebookUrl", "instagramUrl", "tiktokUrl", "websiteUrl"],
    (v) => urlErr(t, ["facebookUrl", v.facebookUrl], ["instagramUrl", v.instagramUrl], ["tiktokUrl", v.tiktokUrl], ["websiteUrl", v.websiteUrl]));
  return (
    <DrawerShell icon="phone" title={t("site.section.contact")} description={t("se.d.contact")} form={f} onClose={onClose}>
      <Group title={t("se.reach")}>
        <Field label={t("register.phone")}><PhoneInput value={f.v.phone ?? ""} onValue={(phone) => f.set({ phone })} /></Field>
        <Field label="WhatsApp" hint={t("identity.whatsappHint")}><PhoneInput value={f.v.whatsapp ?? ""} onValue={(whatsapp) => f.set({ whatsapp })} /></Field>
        <Field label={t("register.email")}><EmailInput value={f.v.email ?? ""} onValue={(email) => f.set({ email })} /></Field>
        <Field label={t("identity.extraPhones")} hint={t("identity.extraPhonesHint")}><Input dir="ltr" value={(f.v.extraPhones ?? []).join(", ")} onChange={(e) => f.set({ extraPhones: list(e.target.value) })} /></Field>
      </Group>
      <Group title={t("se.social")}>
        <Field label="Facebook" error={f.errors.facebookUrl}><Input dir="ltr" placeholder="https://facebook.com/…" value={f.v.facebookUrl ?? ""} onChange={(e) => f.set({ facebookUrl: e.target.value })} /></Field>
        <Field label="Instagram" error={f.errors.instagramUrl}><Input dir="ltr" placeholder="https://instagram.com/…" value={f.v.instagramUrl ?? ""} onChange={(e) => f.set({ instagramUrl: e.target.value })} /></Field>
        <Field label="TikTok" error={f.errors.tiktokUrl}><Input dir="ltr" placeholder="https://tiktok.com/@…" value={f.v.tiktokUrl ?? ""} onChange={(e) => f.set({ tiktokUrl: e.target.value })} /></Field>
        <Field label={t("identity.website")} error={f.errors.websiteUrl}><Input dir="ltr" placeholder="https://…" value={f.v.websiteUrl ?? ""} onChange={(e) => f.set({ websiteUrl: e.target.value })} /></Field>
      </Group>
    </DrawerShell>
  );
}

/** Site-wide: the announcement bar and the open / temporarily closed switch. */
export function ClinicAnnounceEditor({ onClose }: EditorProps) {
  const { t } = useI18n();
  const f = useClinicForm(["announcement", "isOpen", "closedMessage"]);
  return (
    <DrawerShell icon="bell" title={t("se.announce")} description={t("se.d.announce")} form={f} onClose={onClose}>
      <TextField label={t("identity.announcement")} hint={t("identity.announcementHint")} value={f.v.announcement} max={300} onChange={(v) => f.set({ announcement: v })} />
      <OpenStatusField isOpen={f.v.isOpen} message={f.v.closedMessage} onChange={(p) => f.set(p)} />
    </DrawerShell>
  );
}
