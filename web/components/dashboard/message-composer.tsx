"use client";

import { useRef } from "react";
import { useI18n } from "../i18n-provider";
import { ImageUploader } from "../uploader";

const EMOJI = ["😀", "😍", "🥰", "😎", "🤩", "🙏", "👍", "👏", "🔥", "✨", "🎉", "🎁", "💥", "💯", "❤️", "🛍️", "🛒", "🏷️", "💸", "💰", "⭐", "🌟", "⏰", "📣", "📦", "🚚", "✅", "👇", "👉", "🌸", "☀️", "🌙", "🎊", "🥳", "😉", "🤝", "💐", "🧡", "💙", "🆕"];
const PLACEHOLDERS = [["{name}", "msg.insName"], ["{shop}", "msg.insShop"]] as const;

/**
 * A WhatsApp-style message editor: placeholder buttons that drop {name} or {shop} at the cursor, an emoji picker, a picture, a character counter and a live preview bubble.
 * The server replaces the placeholders for each customer when it sends.
 */
export default function MessageComposer({ text, onText, imageId, onImage, max = 1000 }: { text: string; onText: (v: string) => void; imageId: string | null; onImage: (id: string | null) => void; max?: number }) {
  const { t } = useI18n();
  const ref = useRef<HTMLTextAreaElement>(null);
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
  const preview = text.replace(/\{name\}/g, t("msg.sampleName")).replace(/\{shop\}/g, t("msg.sampleShop"));
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
          <div className="mb-1 text-xs font-medium text-slate-500">{t("msg.preview")}</div>
          <div className="max-w-sm rounded-2xl rounded-ss-sm bg-[#dcf8c6] p-2 text-sm text-slate-900 shadow-sm" dir="auto">
            {imageId && /* eslint-disable-next-line @next/next/no-img-element */ <img src={`/api/bff/files/${imageId}/content?variant=medium`} alt="" className="mb-1 max-h-48 w-full rounded-xl object-cover" />}
            {html.length === 1 && html[0] === "" ? <span className="text-slate-400">{t("msg.previewEmpty")}</span> : html.map((l, i) => <div key={i} className="whitespace-pre-wrap break-words">{l || " "}</div>)}
          </div>
        </div>
      </div>
    </div>
  );
}
