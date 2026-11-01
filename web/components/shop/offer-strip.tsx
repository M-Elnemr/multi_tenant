"use client";

import { useEffect, useState } from "react";
import { useI18n } from "../i18n-provider";
import { money } from "@/lib/format";
import { offerSummary } from "@/lib/offers";

export type OfferBanner = { id: string; name: string; type: string; badge?: string; description?: string; endsAt?: string | null; config: Record<string, unknown> };

const GRAD: Record<string, string> = {
  FLASH_SALE: "linear-gradient(110deg,#f59e0b,#ef4444)", FREE_SHIPPING: "linear-gradient(110deg,#0d9488,#22d3ee)", BUY_X_GET_Y: "linear-gradient(110deg,#8b5cf6,#ec4899)",
  GIFT_WITH_PURCHASE: "linear-gradient(110deg,#ec4899,#f472b6)", ORDER_THRESHOLD: "linear-gradient(110deg,#06b6d4,#3b82f6)", FIRST_ORDER: "linear-gradient(110deg,#6366f1,#a855f7)",
};

/** Live hh:mm:ss (and days) until the offer ends. */
export function OfferCountdown({ endsAt, compact }: { endsAt: string; compact?: boolean }) {
  const { t } = useI18n();
  const [now, setNow] = useState<number | null>(null);
  useEffect(() => { const tick = () => setNow(Date.now()); tick(); const id = setInterval(tick, 1000); return () => clearInterval(id); }, []);
  if (now === null) return null;   // no countdown on the server render (avoids a hydration mismatch)
  const ms = Math.max(0, new Date(endsAt).getTime() - now);
  const d = Math.floor(ms / 86400000), h = Math.floor((ms % 86400000) / 3600000), m = Math.floor((ms % 3600000) / 60000), s = Math.floor((ms % 60000) / 1000);
  const p = (n: number) => String(n).padStart(2, "0");
  const cells = [...(d > 0 ? [[d, t("shop.cd.d")]] : []), [h, t("shop.cd.h")], [m, t("shop.cd.m")], [s, t("shop.cd.s")]] as [number, string][];
  return (
    <span className="inline-flex items-center gap-1" dir="ltr">
      {cells.map(([n, u], i) => <span key={i} className={`rounded-lg bg-black/25 text-center font-mono font-extrabold tabular-nums ${compact ? "px-1.5 py-0.5 text-sm" : "min-w-12 px-2 py-1 text-lg"}`}>{p(n)}<span className="block text-[9px] font-semibold opacity-80">{u}</span></span>)}
    </span>
  );
}

/** Coloured strips for running offers the shop chose to announce (flash sales always). */
export function OfferStrip({ banners, currency }: { banners: OfferBanner[]; currency: string }) {
  const { t, locale } = useI18n();
  if (!banners.length) return null;
  const fmt = (n: number) => money(n, currency, locale);
  return (
    <div className="space-y-3">
      {banners.map((b) => (
        <div key={b.id} className="relative flex flex-wrap items-center justify-between gap-3 overflow-hidden rounded-[22px] px-5 py-4 text-white shadow-[var(--s-shadow-lg)]" style={{ background: GRAD[b.type] ?? "linear-gradient(110deg,#f43f5e,#fb923c)" }}>
          <span className="pointer-events-none absolute -end-10 -top-10 h-40 w-40 rounded-full bg-white/15 blur-2xl" />
          <div className="min-w-0">
            <p className="text-xs font-bold uppercase tracking-wider opacity-90">{b.type === "FLASH_SALE" ? "⚡ " : "🎉 "}{b.name}</p>
            <p className="text-lg font-extrabold leading-tight sm:text-xl">{b.badge || offerSummary(b, t, fmt)}</p>
            {b.description && <p className="mt-0.5 text-sm opacity-90">{b.description}</p>}
          </div>
          {b.endsAt && <div className="text-center"><p className="mb-1 text-[11px] font-semibold opacity-90">{t("shop.endsIn")}</p><OfferCountdown endsAt={b.endsAt} /></div>}
        </div>
      ))}
    </div>
  );
}

/** On a product page: the countdown of its sale and the cart offers it is part of. */
export function ProductOffers({ offer, hints, currency }: { offer?: { type: string; endsAt?: string } | null; hints?: { type: string; name: string; badge?: string; config: Record<string, unknown>; endsAt?: string }[]; currency: string }) {
  const { t, locale } = useI18n();
  const fmt = (n: number) => money(n, currency, locale);
  if (!offer?.endsAt && !hints?.length) return null;
  return (
    <div className="space-y-2">
      {offer?.endsAt && (
        <div className="flex flex-wrap items-center justify-between gap-2 rounded-2xl px-4 py-3 text-white" style={{ background: GRAD.FLASH_SALE }}>
          <span className="text-sm font-extrabold">{offer.type === "FLASH_SALE" ? "⚡ " : ""}{t("shop.offerEnds")}</span><OfferCountdown endsAt={offer.endsAt} compact />
        </div>
      )}
      {hints?.map((h, i) => (
        <div key={i} className="flex items-center gap-2 rounded-2xl border border-violet-200 bg-violet-50 px-4 py-3 text-sm font-bold text-violet-800">
          <span className="text-lg">🎁</span><span>{h.badge || offerSummary(h, t, fmt)}</span>
        </div>
      ))}
    </div>
  );
}
