"use client";

import { useRef, useState } from "react";
import { useI18n } from "../i18n-provider";
import { ImageUploader } from "../uploader";

const EMOJI = ["😀", "😍", "🥰", "😎", "🤩", "🙏", "👍", "👏", "🔥", "✨", "🎉", "🎁", "💥", "💯", "❤️", "🛍️", "🛒", "🏷️", "💸", "💰", "⭐", "🌟", "⏰", "📣", "📦", "🚚", "✅", "👇", "👉", "🌸", "☀️", "🌙", "🎊", "🥳", "😉", "🤝", "💐", "🧡", "💙", "🆕"];
const PLACEHOLDERS = [["{name}", "msg.insName"], ["{shop}", "msg.insShop"], ["{last_product}", "msg.insLastProduct"], ["{last_order}", "msg.insLastOrder"], ["{last_date}", "msg.insLastDate"], ["{orders_count}", "msg.insOrdersCount"]] as const;
/** {{a|b|c}}: each customer gets one of the wordings, so the messages are not all identical. Ready-made sets (label, inserted text). */
const SPINS: [string, string][] = [["هلا|أهلاً|مرحباً", "{{هلا|أهلاً|مرحباً}}"], ["عندنا|متاح عندنا|وصلنا", "{{عندنا|متاح عندنا|وصلنا}}"], ["شكراً|ميرسي|تسلم", "{{شكراً|ميرسي|تسلم}}"], ["قريب|دلوقتي|النهارده", "{{قريب|دلوقتي|النهارده}}"]];
/** Pairs written {masculine|feminine}: the server picks the side that fits each customer. [label shown on the button, token inserted] */
const FORMS: [string, string][] = [["أهلاً بيك/بيكي", "أهلاً {بيك|بيكي}"], ["ليك/ليكي", "{ليك|ليكي}"], ["تحب/تحبي", "{تحب|تحبي}"], ["اتفضل/اتفضلي", "{اتفضل|اتفضلي}"], ["تعالى/تعالي", "{تعالى|تعالي}"], ["شوف/شوفي", "{شوف|شوفي}"], ["كمّل/كمّلي", "{كمّل|كمّلي}"], ["معاك/معاكي", "{معاك|معاكي}"]];

/**
 * A WhatsApp-style message editor: placeholder buttons that drop {name} or {shop} at the cursor, an emoji picker, a picture, a character counter and a live preview bubble.
 * The server replaces the placeholders for each customer when it sends.
 */
export default function MessageComposer({ text, onText, imageId, onImage, max = 1000, footer }: { text: string; onText: (v: string) => void; imageId: string | null; onImage: (id: string | null) => void; max?: number; footer?: string }) {
  const { t } = useI18n();
  const ref = useRef<HTMLTextAreaElement>(null);
  const [sample, setSample] = useState<"MALE" | "FEMALE">("FEMALE");
  const [custom, setCustom] = useState({ m: "", f: "" });
  const [spin, setSpin] = useState("");
  const insert = (s: string) => {
    const el = ref.current;
    const a = el?.selectionStart ?? text.length, b = el?.selectionEnd ?? text.length;
    const next = text.slice(0, a) + s + text.slice(b);
    onText(next.slice(0, max));
    requestAnimationFrame(() => { el?.focus(); el?.setSelectionRange(a + s.length, a + s.length); });
  };
  const wrap = (mark: string) => {   // WhatsApp styling: *bold*, _italic_
    const el = ref.current;
    const a = el?.selectionStart ?? 0, b = el?.selectionEnd ?? 0;
    onText((text.slice(0, a) + mark + text.slice(a, b) + mark + text.slice(b)).slice(0, max));
    requestAnimationFrame(() => { el?.focus(); el?.setSelectionRange(a + mark.length, b + mark.length); });
  };
  const preview = text.replace(/\{name\}/g, t("msg.sampleName")).replace(/\{shop\}/g, t("msg.sampleShop")).replace(/\{last_product\}/g, t("msg.sampleProduct")).replace(/\{last_order\}/g, "ST-20261001-000012").replace(/\{last_date\}/g, "1/10/2026").replace(/\{orders_count\}/g, "3").replace(/\{\{([^{}]{1,300})\}\}/g, (_m, a: string) => a.split("|")[0].trim()).replace(/\{([^{}|]{1,40})\|([^{}|]{1,40})\}/g, (_m, a: string, b: string) => (sample === "FEMALE" ? b : a));
  const html = preview.split("\n").map((l) => l);
  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center gap-1.5">
        <span className="text-xs font-medium text-slate-500">{t("msg.insert")}</span>
        {PLACEHOLDERS.map(([tok, label]) => <button key={tok} type="button" onClick={() => insert(tok)} className="rounded-full bg-brand/10 px-3 py-1 text-sm font-semibold text-brand hover:bg-brand/20">＋ {t(label)}</button>)}
        <span className="mx-1 h-5 w-px bg-slate-200" />
        <button type="button" onClick={() => wrap("*")} className="h-8 w-8 rounded-lg border bg-white text-sm font-black hover:bg-slate-50" title={t("msg.bold")}>B</button>
        <button type="button" onClick={() => wrap("_")} className="h-8 w-8 rounded-lg border bg-white text-sm italic hover:bg-slate-50" title={t("msg.italic")}>I</button>
        <details className="relative">
          <summary className="flex h-8 cursor-pointer list-none items-center rounded-lg border bg-white px-2 text-lg hover:bg-slate-50" title={t("msg.emoji")}>😊</summary>
          <div className="absolute z-20 mt-1 grid w-64 grid-cols-8 gap-1 rounded-xl border bg-white p-2 shadow-lg">{EMOJI.map((e) => <button key={e} type="button" onClick={(ev) => { insert(e); (ev.currentTarget.closest("details") as HTMLDetailsElement | null)?.removeAttribute("open"); }} className="rounded p-1 text-xl hover:bg-slate-100">{e}</button>)}</div>
        </details>
      </div>
      <div className="flex flex-wrap items-center gap-1.5">
        <span className="text-xs font-medium text-slate-500">{t("msg.forms")}</span>
        {FORMS.map(([label, token]) => <button key={token} type="button" onClick={() => insert(token)} className="rounded-full bg-violet-100 px-3 py-1 text-sm font-semibold text-violet-800 hover:bg-violet-200">＋ {label}</button>)}
        <details className="relative">
          <summary className="cursor-pointer list-none rounded-full border bg-white px-3 py-1 text-sm font-semibold hover:bg-slate-50">＋ {t("msg.formCustom")}</summary>
          <div className="absolute z-20 mt-1 w-64 space-y-2 rounded-xl border bg-white p-3 shadow-lg">
            <input value={custom.m} onChange={(e) => setCustom({ ...custom, m: e.target.value.replace(/[{}|]/g, "") })} placeholder={t("msg.formMale")} className="w-full rounded-lg border px-2 py-1.5 text-sm" />
            <input value={custom.f} onChange={(e) => setCustom({ ...custom, f: e.target.value.replace(/[{}|]/g, "") })} placeholder={t("msg.formFemale")} className="w-full rounded-lg border px-2 py-1.5 text-sm" />
            <button type="button" disabled={!custom.m.trim() || !custom.f.trim()} onClick={(ev) => { insert(`{${custom.m.trim()}|${custom.f.trim()}}`); setCustom({ m: "", f: "" }); (ev.currentTarget.closest("details") as HTMLDetailsElement | null)?.removeAttribute("open"); }} className="w-full rounded-lg bg-brand px-3 py-1.5 text-sm font-semibold text-white disabled:opacity-50">{t("common.add")}</button>
          </div>
        </details>
      </div>
      <div className="flex flex-wrap items-center gap-1.5">
        <span className="text-xs font-medium text-slate-500">{t("msg.spin")}</span>
        {SPINS.map(([label, token]) => <button key={token} type="button" onClick={() => insert(token)} className="rounded-full bg-sky-100 px-3 py-1 text-sm font-semibold text-sky-800 hover:bg-sky-200">＋ {label}</button>)}
        <details className="relative">
          <summary className="cursor-pointer list-none rounded-full border bg-white px-3 py-1 text-sm font-semibold hover:bg-slate-50">＋ {t("msg.spinCustom")}</summary>
          <div className="absolute z-20 mt-1 w-72 space-y-2 rounded-xl border bg-white p-3 shadow-lg">
            <input value={spin} onChange={(e) => setSpin(e.target.value.replace(/[{}]/g, ""))} placeholder={t("msg.spinHint")} className="w-full rounded-lg border px-2 py-1.5 text-sm" />
            <button type="button" disabled={spin.split(/[|،,]/).filter((x) => x.trim()).length < 2} onClick={(ev) => { insert(`{{${spin.split(/[|،,]/).map((x) => x.trim()).filter(Boolean).join("|")}}}`); setSpin(""); (ev.currentTarget.closest("details") as HTMLDetailsElement | null)?.removeAttribute("open"); }} className="w-full rounded-lg bg-brand px-3 py-1.5 text-sm font-semibold text-white disabled:opacity-50">{t("common.add")}</button>
          </div>
        </details>
      </div>
      <textarea ref={ref} rows={5} maxLength={max} value={text} onChange={(e) => onText(e.target.value)} placeholder={t("msg.placeholder")} className="w-full rounded-xl border px-3 py-2 text-sm outline-none focus:border-brand" />
      <div className="flex items-center justify-between text-xs text-slate-500"><span>{t("msg.hint")}</span><span dir="ltr">{text.length}/{max}</span></div>
      <div className="flex flex-wrap items-start gap-3">
        {imageId ? (
          <div className="relative">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={`/api/bff/files/${imageId}/content?variant=thumb`} alt="" className="h-24 w-24 rounded-xl border object-cover" />
            <button type="button" onClick={() => onImage(null)} className="absolute -end-2 -top-2 h-6 w-6 rounded-full bg-red-600 text-xs font-bold text-white" aria-label={t("common.delete")}>✕</button>
          </div>
        ) : <ImageUploader category="PRODUCT_IMAGE" aspect={4 / 3} label={t("msg.addImage")} onUploaded={(id) => onImage(id)} />}
        <div className="min-w-0 flex-1 basis-60">
          <div className="mb-1 flex items-center justify-between text-xs font-medium text-slate-500"><span>{t("msg.preview")}</span>
            <span className="inline-flex gap-1">{(["MALE", "FEMALE"] as const).map((g) => <button key={g} type="button" onClick={() => setSample(g)} className={`rounded-full px-2 py-0.5 ${sample === g ? "bg-brand text-white" : "bg-slate-100"}`}>{t(`gender.${g}`)}</button>)}</span></div>
          <div className="max-w-sm rounded-2xl rounded-ss-sm bg-[#dcf8c6] p-2 text-sm text-slate-900 shadow-sm" dir="auto">
            {imageId && /* eslint-disable-next-line @next/next/no-img-element */ <img src={`/api/bff/files/${imageId}/content?variant=medium`} alt="" className="mb-1 max-h-48 w-full rounded-xl object-cover" />}
            {html.length === 1 && html[0] === "" ? <span className="text-slate-400">{t("msg.previewEmpty")}</span> : html.map((l, i) => <div key={i} className="whitespace-pre-wrap break-words">{l || " "}</div>)}
          </div>
        </div>
      </div>
    </div>
  );
}
