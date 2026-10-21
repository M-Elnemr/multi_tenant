"use client";

import { useState } from "react";
import { useI18n } from "../i18n-provider";
import { errMsg, oapi } from "@/lib/open-client";

/** "Rate this product" for an item that arrived; the review waits for the shop's moderation like any review. */
export function ReviewButton({ productId }: { productId: string }) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const [rating, setRating] = useState(5);
  const [text, setText] = useState("");
  const [state, setState] = useState<"idle" | "busy" | "done">("idle");
  const [err, setErr] = useState("");
  if (state === "done") return <p className="mt-1.5 text-xs font-bold text-[#067647]">✓ {t("open.review.thanks")}</p>;
  if (!open) return <button type="button" onClick={() => setOpen(true)} className="o-link mt-1.5 text-xs">★ {t("open.review.rate")}</button>;
  const send = async () => {
    setState("busy"); setErr("");
    try { await oapi("reviews", { body: { productId, rating, text: text.trim() || undefined } }); setState("done"); }
    catch (e) { setErr(errMsg(t, e)); setState("idle"); }
  };
  return (
    <div className="o-pop mt-2 grid gap-2 rounded-2xl bg-[var(--o-soft)] p-3">
      <div className="flex gap-1" role="radiogroup" aria-label={t("open.review.rate")}>
        {[1, 2, 3, 4, 5].map((n) => <button key={n} type="button" role="radio" aria-checked={rating === n} onClick={() => setRating(n)} className={`text-2xl leading-none transition hover:scale-125 ${n <= rating ? "o-stars" : "text-[var(--o-line)]"}`}>★</button>)}
      </div>
      <textarea value={text} onChange={(e) => setText(e.target.value)} rows={2} maxLength={600} placeholder={t("open.review.placeholder")} className="o-input" />
      {err && <p className="text-xs font-bold text-[var(--o-red)]">{err}</p>}
      <div className="flex gap-2"><button type="button" disabled={state === "busy"} onClick={send} className="o-btn o-btn-sm">{t("open.review.send")}</button><button type="button" onClick={() => setOpen(false)} className="o-btn o-btn-ghost o-btn-sm">{t("common.cancel")}</button></div>
    </div>
  );
}
