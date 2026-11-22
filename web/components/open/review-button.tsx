"use client";

import { useState } from "react";
import { useI18n } from "../i18n-provider";
import { Icon } from "../icons";
import { errMsg, oapi } from "@/lib/open-client";

/** "Rate this product" for an item that arrived; the review waits for the shop's moderation like any review. */
export function ReviewButton({ productId }: { productId: string }) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const [rating, setRating] = useState(5);
  const [text, setText] = useState("");
  const [state, setState] = useState<"idle" | "busy" | "done">("idle");
  const [err, setErr] = useState("");
  if (state === "done") return <p className="mt-1.5 text-xs font-bold text-[#067647]"><span className="inline-flex items-center gap-1"><Icon name="check" className="h-3.5 w-3.5" />{t("open.review.thanks")}</span></p>;
  if (!open) return <button type="button" onClick={() => setOpen(true)} className="o-link mt-1.5 inline-flex items-center gap-1 text-xs"><Icon name="star" className="h-3.5 w-3.5" />{t("open.review.rate")}</button>;
  const send = async () => {
    setState("busy"); setErr("");
    try { await oapi("reviews", { body: { productId, rating, text: text.trim() || undefined } }); setState("done"); }
    catch (e) { setErr(errMsg(t, e)); setState("idle"); }
  };
  return (
    <div className="o-pop mt-2 grid gap-2 rounded-2xl bg-[var(--o-soft)] p-3">
      <div className="flex gap-1" role="radiogroup" aria-label={t("open.review.rate")}>
        {[1, 2, 3, 4, 5].map((n) => <button key={n} type="button" role="radio" aria-checked={rating === n} aria-label={`${n}`} onClick={() => setRating(n)} className={`grid h-9 w-9 place-items-center transition hover:scale-110 ${n <= rating ? "o-stars" : "text-[var(--o-line)]"}`}><Icon name="star" className={`h-6 w-6 ${n <= rating ? "fill-current" : "fill-none"}`} /></button>)}
      </div>
      <textarea value={text} onChange={(e) => setText(e.target.value)} rows={2} maxLength={600} placeholder={t("open.review.placeholder")} className="o-input" />
      {err && <p className="text-xs font-bold text-[var(--o-red)]">{err}</p>}
      <div className="flex gap-2"><button type="button" disabled={state === "busy"} onClick={send} className="o-btn o-btn-sm">{t("open.review.send")}</button><button type="button" onClick={() => setOpen(false)} className="o-btn o-btn-ghost o-btn-sm">{t("common.cancel")}</button></div>
    </div>
  );
}
