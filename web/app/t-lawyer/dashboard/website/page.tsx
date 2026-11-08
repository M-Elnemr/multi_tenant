/* eslint-disable @next/next/no-img-element */
"use client";

import { useState, type ReactNode } from "react";
import { api } from "@/lib/client";
import { fileUrl } from "@/lib/media";
import { useFieldErrors } from "@/lib/use-field-errors";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { ImageUploader } from "@/components/uploader";
import BrandingCard from "@/components/dashboard/branding";
import { useToast } from "@/components/toast";
import { Eyebrow, LinkButton } from "@/components/lawyer/kit";
import { Button, ErrorText, Field, Input, Loading, PageHeader, Section, Select, Textarea } from "@/components/ui";

type Area = { key: string; title: string; description: string; icon: string };
type Profile = {
  displayName: string; title: string; headline: string; bio: string; photoUrl?: string | null; coverUrl?: string | null; barNumber: string; barLevel: string; yearsExperience: number;
  officeAddress: string; officeCity: string; mapUrl: string; phone: string; whatsapp: string; email: string; template: "classic" | "modern" | "heritage";
  practiceAreas: Area[]; languages: string[]; education: { title: string; place: string; year: string }[]; achievements: { value: string; label: string }[];
  testimonials: { name: string; text: string; rating: number }[]; faq: { q: string; a: string }[]; social: Record<string, string>;
};
type Meta = { practiceAreas: { key: string; nameAr: string; nameEn: string; icon: string }[] };

const TEMPLATES = [
  { id: "classic", bg: "linear-gradient(135deg,#0b1d3a,#1e3a5f)", accent: "#c9a227" },
  { id: "modern", bg: "linear-gradient(135deg,#f8fafc,#e2e8f0)", accent: "#0f766e" },
  { id: "heritage", bg: "linear-gradient(135deg,#2b1d14,#5c3b23)", accent: "#d4a373" },
] as const;
const AREA_ICONS = ["⚖️", "🛡️", "👪", "🏢", "👷", "🏠", "🏛️", "📊", "📜", "✍️", "💡", "🧾", "🤝", "🔨", "📁"];

/** List editor: rows of small inputs with add / remove / move up. */
function ListEditor<T extends Record<string, unknown>>({ items, onChange, blank, render, addLabel }: { items: T[]; onChange: (v: T[]) => void; blank: T; render: (it: T, set: (p: Partial<T>) => void, i: number) => ReactNode; addLabel: string }) {
  return (
    <div className="space-y-2">
      {items.map((it, i) => (
        <div key={i} className="flex items-start gap-2 rounded-[14px] bg-surface-2 p-3 ring-1 ring-line">
          <div className="min-w-0 flex-1">{render(it, (p) => onChange(items.map((x, k) => (k === i ? { ...x, ...p } : x))), i)}</div>
          <div className="flex flex-col gap-1">
            {i > 0 && <button type="button" aria-label="Up" className="grid h-8 w-8 place-items-center rounded-full text-muted hover:bg-surface hover:text-ink pointer-coarse:h-11 pointer-coarse:w-11" onClick={() => { const n = [...items]; [n[i - 1], n[i]] = [n[i], n[i - 1]]; onChange(n); }}>↑</button>}
            <button type="button" aria-label="Remove" className="grid h-8 w-8 place-items-center rounded-full text-muted hover:bg-surface hover:text-red-600 pointer-coarse:h-11 pointer-coarse:w-11" onClick={() => onChange(items.filter((_, k) => k !== i))}><Icon name="close" className="h-4 w-4" /></button>
          </div>
        </div>
      ))}
      <Button type="button" size="sm" variant="secondary" onClick={() => onChange([...items, { ...blank }])}>+ {addLabel}</Button>
    </div>
  );
}

export default function Website() {
  const { t, locale } = useI18n();
  const toast = useToast();
  const { canWrite } = useMe();
  const { data, loading, error, reload } = useApi<Profile>("lawyer/profile");
  const meta = useApi<Meta>("lawyer/meta");
  const [draft, setDraft] = useState<Profile | null>(null);
  const [saved, setSaved] = useState(false);
  const fe = useFieldErrors();
  const p = draft ?? data;
  const set = (x: Partial<Profile>) => { setDraft({ ...(p as Profile), ...x }); setSaved(false); for (const k of Object.keys(x)) fe.clear(k); };
  const save = useAction(async () => {
    if (!p) return;
    if (!fe.check({ displayName: !p.displayName.trim() && t("srv.Write your name as clients should see it") })) return;
    try {
      await api("lawyer/profile", { method: "PATCH", body: {
        displayName: p.displayName, title: p.title, headline: p.headline, bio: p.bio, photoUrl: p.photoUrl ?? null, coverUrl: p.coverUrl ?? null, barNumber: p.barNumber, barLevel: p.barLevel, yearsExperience: Number(p.yearsExperience) || 0,
        officeAddress: p.officeAddress, officeCity: p.officeCity, mapUrl: p.mapUrl, phone: p.phone, whatsapp: p.whatsapp, email: p.email, template: p.template,
        practiceAreas: p.practiceAreas, languages: p.languages, education: p.education, achievements: p.achievements, testimonials: p.testimonials, faq: p.faq, social: p.social,
      } });
    } catch (e) { if (!fe.fromError(e)) throw e; return; }
    setDraft(null); setSaved(true); await reload(); toast(t("lw.web.saved"));
  });
  if (loading && !data) return <Loading />;
  if (!p) return <ErrorText error={error} />;
  const write = canWrite("settings");
  return (
    <>
      <PageHeader eyebrow={<Eyebrow>{t("lw.eb.setup")}</Eyebrow>} title={t("lw.nav.website")} subtitle={t("lw.web.subtitle")} actions={<LinkButton href="/" icon="globe" external>{t("lw.web.view")}</LinkButton>} />
      <fieldset disabled={!write} className="min-w-0 space-y-5">
        <Section title={t("lw.web.design")} icon="palette">
          <div className="grid gap-3 sm:grid-cols-3">
            {TEMPLATES.map((tp) => (
              <button key={tp.id} type="button" onClick={() => set({ template: tp.id })} className={`overflow-hidden rounded-2xl text-start ring-2 transition ${p.template === tp.id ? "ring-[var(--rail-accent)]" : "ring-line hover:ring-line-strong"}`}>
                <div className="relative h-28 p-4" style={{ background: tp.bg }}>
                  <div className="h-2 w-16 rounded-full" style={{ background: tp.accent }} /><div className="mt-3 h-3 w-32 rounded-full bg-white/70" /><div className="mt-2 h-2 w-24 rounded-full bg-white/40" />
                  <div className="absolute bottom-3 end-3 h-7 w-16 rounded-lg" style={{ background: tp.accent }} />
                  {p.template === tp.id && <span className="absolute start-3 top-3 grid h-6 w-6 place-items-center rounded-full bg-white text-[#14213d] shadow"><Icon name="check" className="h-4 w-4" /></span>}
                </div>
                <div className="bg-surface p-3"><p className="font-semibold text-ink">{t(`lw.tpl.${tp.id}`)}</p><p className="text-xs text-muted">{t(`lw.tpl.${tp.id}.d`)}</p></div>
              </button>
            ))}
          </div>
        </Section>

        <Section title={t("lw.web.identity")} icon="doctor">
          <div className="grid gap-4 lg:grid-cols-[220px_1fr]">
            <div className="space-y-3">
              <div className="aspect-[4/5] overflow-hidden rounded-[16px] bg-surface-2 ring-1 ring-line">{p.photoUrl ? <img src={p.photoUrl} alt="" className="h-full w-full object-cover" /> : <div className="grid h-full place-items-center text-faint"><Icon name="gavel" className="h-14 w-14" /></div>}</div>
              {write && <ImageUploader category="LOGO" aspect={4 / 5} label={t("lw.web.photo")} onUploaded={(id) => set({ photoUrl: fileUrl(id, "medium") })} />}
              {p.photoUrl && write && <button type="button" className="text-xs font-semibold text-red-600" onClick={() => set({ photoUrl: null })}>{t("offer.delete")}</button>}
            </div>
            <div className="space-y-3">
              <div className="grid gap-3 sm:grid-cols-[1fr_2fr]">
                <Field name="title" error={fe.err("title")} label={t("lw.web.title")}><Input value={p.title} onChange={(e) => set({ title: e.target.value })} placeholder={t("lw.web.titlePh")} /></Field>
                <Field name="displayName" error={fe.err("displayName")} label={t("lw.web.name")}><Input value={p.displayName} onChange={(e) => set({ displayName: e.target.value })} /></Field>
              </div>
              <Field name="headline" error={fe.err("headline")} label={t("lw.web.headline")}><Input value={p.headline} onChange={(e) => set({ headline: e.target.value })} placeholder={t("lw.web.headlinePh")} /></Field>
              <Field label={t("lw.web.bio")}><Textarea rows={5} value={p.bio} onChange={(e) => set({ bio: e.target.value })} placeholder={t("lw.web.bioPh")} /></Field>
              <div className="grid gap-3 sm:grid-cols-3">
                <Field name="yearsExperience" error={fe.err("yearsExperience")} label={t("lw.web.years")}><Input dir="ltr" inputMode="numeric" value={String(p.yearsExperience ?? "")} onChange={(e) => set({ yearsExperience: Number(e.target.value.replace(/[^0-9]/g, "")) || 0 })} /></Field>
                <Field label={t("lw.web.barLevel")}><Input value={p.barLevel} onChange={(e) => set({ barLevel: e.target.value })} placeholder={t("lw.web.barLevelPh")} /></Field>
                <Field label={t("lw.web.barNo")}><Input dir="ltr" value={p.barNumber} onChange={(e) => set({ barNumber: e.target.value })} /></Field>
              </div>
            </div>
          </div>
        </Section>

        <Section title={t("lw.web.cover")}>
          <div className="space-y-3">
            <div className="h-40 overflow-hidden rounded-[16px] bg-surface-2 ring-1 ring-line">{p.coverUrl ? <img src={p.coverUrl} alt="" className="h-full w-full object-cover" /> : <div className="grid h-full place-items-center text-sm text-faint">{t("lw.web.coverHint")}</div>}</div>
            {write && <div className="flex gap-2"><ImageUploader category="LOGO" aspect={16 / 7} label={t("lw.web.coverUpload")} onUploaded={(id) => set({ coverUrl: fileUrl(id, "original") })} />{p.coverUrl && <Button type="button" size="sm" variant="ghost" onClick={() => set({ coverUrl: null })}>{t("offer.delete")}</Button>}</div>}
          </div>
        </Section>

        <Section title={t("lw.web.areas")} icon="scale">
          <div className="mb-3 flex flex-wrap gap-1.5">{meta.data?.practiceAreas.filter((a) => !p.practiceAreas.some((x) => x.key === a.key)).map((a) => (
            <button key={a.key} type="button" className="chip" onClick={() => set({ practiceAreas: [...p.practiceAreas, { key: a.key, title: locale === "ar" ? a.nameAr : a.nameEn, description: "", icon: a.icon }] })}>+ {locale === "ar" ? a.nameAr : a.nameEn}</button>
          ))}</div>
          <ListEditor items={p.practiceAreas} onChange={(v) => set({ practiceAreas: v })} blank={{ key: "custom", title: "", description: "", icon: "⚖️" }} addLabel={t("lw.web.addArea")}
            render={(a, s) => (
              <div className="grid gap-2 sm:grid-cols-[80px_1fr]">
                <Select value={AREA_ICONS.includes(a.icon) ? a.icon : "⚖️"} onChange={(e) => s({ icon: e.target.value })}>{AREA_ICONS.map((i) => <option key={i} value={i}>{i}</option>)}</Select>
                <div className="space-y-2"><Input value={a.title} onChange={(e) => s({ title: e.target.value })} placeholder={t("lw.web.areaTitle")} /><Textarea rows={2} value={a.description} onChange={(e) => s({ description: e.target.value })} placeholder={t("lw.web.areaDesc")} /></div>
              </div>
            )} />
        </Section>

        <div className="grid gap-5 lg:grid-cols-2">
          <Section title={t("lw.web.achievements")}>
            <ListEditor items={p.achievements} onChange={(v) => set({ achievements: v })} blank={{ value: "", label: "" }} addLabel={t("common.add")}
              render={(a, s) => <div className="grid grid-cols-[100px_1fr] gap-2"><Input value={a.value} onChange={(e) => s({ value: e.target.value })} placeholder="500+" /><Input value={a.label} onChange={(e) => s({ label: e.target.value })} placeholder={t("lw.web.achPh")} /></div>} />
          </Section>
          <Section title={t("lw.web.education")}>
            <ListEditor items={p.education} onChange={(v) => set({ education: v })} blank={{ title: "", place: "", year: "" }} addLabel={t("common.add")}
              render={(a, s) => <div className="grid gap-2 sm:grid-cols-[1fr_1fr_80px]"><Input value={a.title} onChange={(e) => s({ title: e.target.value })} placeholder={t("lw.web.eduTitle")} /><Input value={a.place} onChange={(e) => s({ place: e.target.value })} placeholder={t("lw.web.eduPlace")} /><Input dir="ltr" value={a.year} onChange={(e) => s({ year: e.target.value })} placeholder="2005" /></div>} />
          </Section>
        </div>

        <Section title={t("lw.web.testimonials")}>
          <ListEditor items={p.testimonials} onChange={(v) => set({ testimonials: v })} blank={{ name: "", text: "", rating: 5 }} addLabel={t("common.add")}
            render={(a, s) => <div className="space-y-2"><div className="grid grid-cols-[1fr_120px] gap-2"><Input value={a.name} onChange={(e) => s({ name: e.target.value })} placeholder={t("lw.web.tName")} /><Select value={String(a.rating)} onChange={(e) => s({ rating: Number(e.target.value) })}>{[5, 4, 3].map((r) => <option key={r} value={r}>{"★".repeat(r)}</option>)}</Select></div><Textarea rows={2} value={a.text} onChange={(e) => s({ text: e.target.value })} placeholder={t("lw.web.tText")} /></div>} />
        </Section>

        <Section title={t("lw.web.faq")}>
          <ListEditor items={p.faq} onChange={(v) => set({ faq: v })} blank={{ q: "", a: "" }} addLabel={t("common.add")}
            render={(a, s) => <div className="space-y-2"><Input value={a.q} onChange={(e) => s({ q: e.target.value })} placeholder={t("lw.web.q")} /><Textarea rows={2} value={a.a} onChange={(e) => s({ a: e.target.value })} placeholder={t("lw.web.a")} /></div>} />
        </Section>

        <Section title={t("lw.web.contact")} icon="phone">
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            <Field name="phone" error={fe.err("phone")} label={t("lw.client.phone")}><Input dir="ltr" value={p.phone} onChange={(e) => set({ phone: e.target.value })} /></Field>
            <Field name="whatsapp" error={fe.err("whatsapp")} label="WhatsApp"><Input dir="ltr" value={p.whatsapp} onChange={(e) => set({ whatsapp: e.target.value })} /></Field>
            <Field name="email" error={fe.err("email")} label={t("lw.client.email")}><Input dir="ltr" value={p.email} onChange={(e) => set({ email: e.target.value })} /></Field>
            <Field label={t("lw.web.city")}><Input value={p.officeCity} onChange={(e) => set({ officeCity: e.target.value })} /></Field>
            <Field label={t("lw.client.address")}><Input value={p.officeAddress} onChange={(e) => set({ officeAddress: e.target.value })} /></Field>
            <Field name="mapUrl" error={fe.err("mapUrl")} label={t("lw.web.map")} hint={t("lw.web.mapHint")}><Input dir="ltr" value={p.mapUrl} onChange={(e) => set({ mapUrl: e.target.value })} placeholder="https://maps.google.com/..." /></Field>
          </div>
          <div className="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            {["facebook", "linkedin", "instagram", "youtube"].map((k) => <Field key={k} name={`social.${k}`} error={fe.err(`social.${k}`)} label={k[0].toUpperCase() + k.slice(1)}><Input dir="ltr" value={p.social?.[k] ?? ""} onChange={(e) => set({ social: { ...(p.social ?? {}), [k]: e.target.value } })} placeholder="https://" /></Field>)}
          </div>
        </Section>
      </fieldset>
      <div className="mt-5"><BrandingCard /></div>
      <div className="sticky bottom-3 z-10 mt-5 flex items-center gap-3 rounded-[16px] bg-surface/90 p-2 shadow-card backdrop-blur">
        {saved && !draft && <span className="ps-2 inline-flex items-center gap-1 text-[13px] font-semibold text-emerald-700"><Icon name="check" className="h-4 w-4" />{t("lw.web.saved")}</span>}
        {fe.any ? <p className="text-sm font-medium text-red-700">{t("form.fixMarked")}</p> : <ErrorText error={save.error} />}
        {write && <Button className="ms-auto" loading={save.loading} disabled={!draft} onClick={() => save.run()}>{t("lw.web.publish")}</Button>}
      </div>
    </>
  );
}
