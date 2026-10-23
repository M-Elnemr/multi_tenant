"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { api } from "@/lib/client";
import { useI18n } from "../i18n-provider";
import { ImageUploader } from "../uploader";
import { Button, Field, Input, Textarea } from "../ui";

export const thumbUrl = (id: string) => `/api/bff/files/${id}/content?variant=thumb`;
export const validUrl = (s?: string | null) => !s || /^https?:\/\/\S+$/i.test(s.trim());

/** A group of related fields with a small heading (keeps long editors scannable). */
export function Group({ title, hint, children }: { title?: string; hint?: string; children: ReactNode }) {
  return (
    <section className="space-y-3">
      {title && <div><h3 className="text-sm font-bold text-ink">{title}</h3>{hint && <p className="text-xs text-slate-500">{hint}</p>}</div>}
      {children}
    </section>
  );
}

export function Counter({ value, max }: { value?: string | null; max: number }) {
  const n = (value ?? "").length;
  return <span className={`block text-end text-[11px] tabular-nums ${n > max * 0.9 ? "text-amber-600" : "text-slate-400"}`}>{n}/{max}</span>;
}

/** A single-line text field with a character counter. */
export function TextField({ label, hint, value, onChange, max = 200, error, ltr, placeholder }: { label: string; hint?: string; value?: string | null; onChange: (v: string) => void; max?: number; error?: string; ltr?: boolean; placeholder?: string }) {
  return (
    <div><Field label={label} hint={hint} error={error}><Input value={value ?? ""} maxLength={max} dir={ltr ? "ltr" : undefined} placeholder={placeholder} onChange={(e) => onChange(e.target.value)} /></Field><Counter value={value} max={max} /></div>
  );
}

export function AreaField({ label, hint, value, onChange, max = 2000, rows = 5 }: { label: string; hint?: string; value?: string | null; onChange: (v: string) => void; max?: number; rows?: number }) {
  return (
    <div><Field label={label} hint={hint}><Textarea rows={rows} value={value ?? ""} maxLength={max} onChange={(e) => onChange(e.target.value)} /></Field><Counter value={value} max={max} /></div>
  );
}

/** One picture: preview, replace and remove. */
export function PhotoField({ label, hint, value, onChange }: { label: string; hint?: string; value?: string | null; onChange: (id: string | null) => void }) {
  const { t } = useI18n();
  return (
    <Group title={label} hint={hint}>
      <div className="flex flex-wrap items-center gap-4 rounded-2xl border border-dashed border-slate-300 bg-slate-50/60 p-4">
        {value ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={thumbUrl(value)} alt="" className="h-24 w-40 rounded-xl object-cover shadow-sm ring-1 ring-slate-200" />
        ) : <div className="flex h-24 w-40 items-center justify-center rounded-xl bg-slate-100 text-xs text-slate-400">{t("se.noPhoto")}</div>}
        <div className="flex flex-col items-start gap-2">
          <ImageUploader category="PRODUCT_IMAGE" aspect={16 / 9} label={value ? t("se.replacePhoto") : t("identity.coverUpload")} onUploaded={(id) => onChange(id)} />
          {value && <Button size="sm" variant="ghost" onClick={() => onChange(null)}>{t("common.remove")}</Button>}
        </div>
      </div>
    </Group>
  );
}

/** A grid of pictures with add and remove (up to [max]). */
export function GalleryField({ ids, onChange, max = 12 }: { ids: string[]; onChange: (ids: string[]) => void; max?: number }) {
  const { t } = useI18n();
  return (
    <Group title={t("cweb.gallery")} hint={t("cweb.galleryHint")}>
      <div className="grid grid-cols-3 gap-3 sm:grid-cols-4">
        {ids.map((id) => (
          <div key={id} className="group relative aspect-square overflow-hidden rounded-2xl ring-1 ring-slate-200">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={thumbUrl(id)} alt="" className="h-full w-full object-cover" />
            <button type="button" aria-label={t("common.remove")} onClick={() => onChange(ids.filter((x) => x !== id))} className="absolute end-1.5 top-1.5 grid h-7 w-7 place-items-center rounded-full bg-black/60 text-sm text-white opacity-0 transition hover:bg-red-600 focus:opacity-100 group-hover:opacity-100">×</button>
          </div>
        ))}
        {ids.length < max && <div className="flex aspect-square items-center justify-center rounded-2xl border-2 border-dashed border-slate-300 bg-slate-50 p-2 text-center"><ImageUploader category="PRODUCT_IMAGE" label={t("cweb.galleryAdd")} onUploaded={(id) => onChange([...ids, id])} /></div>}
      </div>
      <p className="text-xs text-slate-500">{ids.length}/{max}</p>
    </Group>
  );
}

/** A list of short words shown as removable chips (insurance companies...). */
export function TagsField({ label, hint, values, onChange, max = 30 }: { label: string; hint?: string; values: string[]; onChange: (v: string[]) => void; max?: number }) {
  const { t } = useI18n();
  const [text, setText] = useState("");
  const add = () => { const parts = text.split(/[,،]/).map((x) => x.trim()).filter((x) => x && x.length <= 80 && !values.includes(x)); if (parts.length) onChange([...values, ...parts].slice(0, max)); setText(""); };
  return (
    <Group title={label} hint={hint}>
      <div className="flex flex-wrap gap-2">
        {values.map((v) => <span key={v} className="inline-flex items-center gap-1.5 rounded-full bg-brand-soft px-3 py-1.5 text-sm font-semibold text-brand-dark">{v}<button type="button" aria-label={t("common.remove")} onClick={() => onChange(values.filter((x) => x !== v))} className="text-brand/60 hover:text-red-600">×</button></span>)}
        {values.length === 0 && <span className="text-sm text-slate-400">{t("se.nothingYet")}</span>}
      </div>
      <div className="flex gap-2">
        <Input value={text} placeholder={t("se.typeAndAdd")} onChange={(e) => setText(e.target.value)} onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); add(); } }} />
        <Button type="button" variant="secondary" onClick={add} disabled={!text.trim() || values.length >= max}>{t("se.add")}</Button>
      </div>
    </Group>
  );
}

/** Question and answer pairs. */
export function FaqField({ items, onChange, max = 20 }: { items: { q: string; a: string }[]; onChange: (v: { q: string; a: string }[]) => void; max?: number }) {
  const { t } = useI18n();
  const upd = (i: number, patch: Partial<{ q: string; a: string }>) => onChange(items.map((x, j) => (j === i ? { ...x, ...patch } : x)));
  return (
    <Group title={t("cweb.faqs")} hint={t("se.faqHint")}>
      {items.map((f, i) => (
        <div key={i} className="space-y-2 rounded-2xl border border-slate-200 bg-white p-3.5 shadow-sm">
          <div className="flex items-center justify-between"><span className="text-xs font-bold text-slate-400">#{i + 1}</span><button type="button" onClick={() => onChange(items.filter((_, j) => j !== i))} className="text-xs font-semibold text-red-600 hover:underline">{t("common.remove")}</button></div>
          <Input placeholder={t("cweb.faqQ")} value={f.q} maxLength={200} onChange={(e) => upd(i, { q: e.target.value })} />
          <Textarea rows={3} placeholder={t("cweb.faqA")} value={f.a} maxLength={1500} onChange={(e) => upd(i, { a: e.target.value })} />
        </div>
      ))}
      {items.length < max && <Button type="button" variant="secondary" onClick={() => onChange([...items, { q: "", a: "" }])}>+ {t("cweb.addFaq")}</Button>}
    </Group>
  );
}

/** Open / temporarily closed switch with the message visitors see. */
export function OpenStatusField({ isOpen, message, onChange }: { isOpen?: boolean; message?: string | null; onChange: (p: { isOpen?: boolean; closedMessage?: string }) => void }) {
  const { t } = useI18n();
  const closed = isOpen === false;
  return (
    <div className={`space-y-3 rounded-2xl border p-4 transition ${closed ? "border-amber-300 bg-amber-50" : "border-slate-200 bg-slate-50/60"}`}>
      <label className="flex cursor-pointer items-center justify-between gap-3">
        <span><span className="block text-sm font-bold">{t("cweb.closed")}</span><span className="text-xs text-slate-500">{t("cweb.closedHint")}</span></span>
        <input type="checkbox" role="switch" checked={closed} onChange={(e) => onChange({ isOpen: !e.target.checked })} className="h-6 w-11 cursor-pointer" />
      </label>
      {closed && <TextField label={t("identity.vacationMsg")} value={message} max={300} onChange={(v) => onChange({ closedMessage: v })} />}
    </div>
  );
}

/** The logo shown in the header and the browser tab. It is saved on its own right after the upload (it belongs to the brand, not to this section's form). */
export function LogoField() {
  const { t } = useI18n();
  const router = useRouter();
  const [logo, setLogo] = useState<string | null | undefined>(undefined);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => { api<{ logoFileId?: string | null }>("tenant/branding").then((b) => setLogo(b.logoFileId ?? null)).catch(() => setLogo(null)); }, []);
  const save = async (id: string) => {
    setBusy(true); setError(""); setDone(false);
    try { await api("tenant/branding", { method: "PATCH", body: { logoFileId: id } }); setLogo(id); setDone(true); router.refresh(); }
    catch { setError(t("se.logoFailed")); } finally { setBusy(false); }
  };
  return (
    <Group title={t("se.logo")} hint={t("se.logoHint")}>
      <div className="flex flex-wrap items-center gap-4 rounded-2xl border border-dashed border-slate-300 bg-slate-50/60 p-4">
        {logo ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={thumbUrl(logo)} alt="" className="h-20 w-20 rounded-2xl bg-white object-contain p-1 shadow-sm ring-1 ring-slate-200" />
        ) : <div className="flex h-20 w-20 items-center justify-center rounded-2xl bg-slate-100 text-xs text-slate-400">{logo === undefined ? "…" : t("se.noPhoto")}</div>}
        <div className="flex flex-col items-start gap-1.5">
          <ImageUploader category="LOGO" label={logo ? t("se.replaceLogo") : t("se.uploadLogo")} onUploaded={(id) => void save(id)} />
          {busy && <span className="text-xs text-slate-500">{t("se.saving")}</span>}
          {done && !busy && <span className="text-xs font-semibold text-emerald-600">✓ {t("common.saved")}</span>}
          {error && <span className="text-xs text-red-600">{error}</span>}
        </div>
      </div>
    </Group>
  );
}
