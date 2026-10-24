"use client";

import Link from "next/link";
import { clearCart, removeLine, setQuantity, useCart } from "@/lib/cart";
import { money } from "@/lib/format";
import { useI18n } from "@/components/i18n-provider";
import { useApi } from "@/components/hooks";

type ShipProfile = { shippingMethods: { type: string; freeAboveMinor?: number | null }[]; shippingZones: { freeAboveMinor?: number | null }[] };

export default function Cart() {
  const { t, locale, currency } = useI18n();
  const lines = useCart();
  const total = lines.reduce((n, l) => n + l.unitPriceMinor * l.quantity, 0);
  const ship = useApi<ShipProfile>("shop/profile");
  // the lowest free-delivery threshold the shop offers anywhere: a nudge, the real fee is worked out at checkout
  const thresholds = [...(ship.data?.shippingMethods ?? []).filter((m) => m.type === "FREE_ABOVE").map((m) => m.freeAboveMinor), ...(ship.data?.shippingZones ?? []).map((z) => z.freeAboveMinor)].filter((x): x is number => typeof x === "number" && x > 0);
  const freeAt = thresholds.length ? Math.min(...thresholds) : null;
  if (lines.length === 0) {
    return (
      <div className="s-card mx-auto grid max-w-lg place-items-center gap-4 p-14 text-center">
        <span className="text-6xl">🛒</span>
        <h1 className="text-2xl font-extrabold">{t("cart.empty")}</h1>
        <p className="text-[var(--s-mute)]">{t("cart.emptyHint")}</p>
        <Link href="/products" className="s-btn">{t("shop.browse")}</Link>
      </div>
    );
  }
  return (
    <>
      <div className="mb-6 flex items-end justify-between"><h1 className="text-3xl font-extrabold">{t("nav.cart")} <span className="text-lg font-semibold text-[var(--s-mute)]">({lines.reduce((n, l) => n + l.quantity, 0)})</span></h1><button onClick={clearCart} className="text-sm font-bold text-[var(--s-mute)] hover:text-[#e5484d]">{t("cart.clear")}</button></div>
      <div className="grid items-start gap-6 lg:grid-cols-[1fr_22rem]">
        <ul className="space-y-3">
          {lines.map((l) => (
            <li key={l.variantId} className="s-card flex gap-4 p-4">
              <Link href={`/products/${l.slug}`} className="h-24 w-24 shrink-0 overflow-hidden rounded-2xl bg-[var(--s-soft)] sm:h-28 sm:w-28">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                {l.imageUrl ? <img src={l.imageUrl} alt="" className="h-full w-full object-cover" /> : <div className="grid h-full place-items-center text-3xl opacity-30">🛍️</div>}
              </Link>
              <div className="flex min-w-0 flex-1 flex-col">
                <Link href={`/products/${l.slug}`} className="line-clamp-2 font-extrabold hover:text-[var(--brand)]">{l.name}</Link>
                {l.variantLabel && <p className="text-sm text-[var(--s-mute)]">{l.variantLabel}</p>}
                <div className="mt-auto flex flex-wrap items-center justify-between gap-2 pt-2">
                  <div className="flex items-center rounded-full border border-[var(--s-line)] bg-white">
                    <button className="h-11 w-11 text-lg hover:text-[var(--brand)] md:h-9 md:w-9" onClick={() => setQuantity(l.variantId, l.quantity - 1)} aria-label="-">−</button>
                    <span className="w-7 text-center text-sm font-extrabold">{l.quantity}</span>
                    <button className="h-11 w-11 text-lg hover:text-[var(--brand)] md:h-9 md:w-9" onClick={() => setQuantity(l.variantId, l.quantity + 1)} aria-label="+">+</button>
                  </div>
                  <p className="text-lg font-extrabold" style={{ color: "var(--brand)" }}>{money(l.unitPriceMinor * l.quantity, currency, locale)}</p>
                </div>
              </div>
              <button className="-m-2 grid h-11 w-11 shrink-0 place-items-center self-start text-[var(--s-mute)] transition hover:text-[#e5484d]" onClick={() => removeLine(l.variantId)} aria-label={t("common.delete")}>✕</button>
            </li>
          ))}
        </ul>
        <aside className="s-card space-y-4 p-6 lg:sticky lg:top-28">
          <h2 className="text-lg font-extrabold">{t("checkout.summary")}</h2>
          <div className="flex justify-between text-sm"><span className="text-[var(--s-mute)]">{t("checkout.subtotal")}</span><b>{money(total, currency, locale)}</b></div>
          {freeAt !== null && (
            <div className="rounded-2xl bg-[var(--s-soft)] p-3 text-xs">
              <p className="mb-2 font-bold">{total >= freeAt ? "🎉 " + t("cart.freeReached") : "🚚 " + t("cart.freeMore", { amount: money(freeAt - total, currency, locale) })}</p>
              <div className="h-2 overflow-hidden rounded-full bg-white"><div className="h-full rounded-full transition-all" style={{ width: `${Math.min(100, Math.round((total / freeAt) * 100))}%`, background: "var(--brand)" }} /></div>
            </div>
          )}
          <p className="rounded-2xl bg-[var(--s-soft)] p-3 text-xs text-[var(--s-mute)]">🚚 {t("cart.shippingNote")}</p>
          <Link href="/checkout" className="s-btn w-full !py-3.5">{t("cart.checkout")} →</Link>
          <p className="text-center text-xs text-[var(--s-mute)]">💵 {t("trust.codText")}</p>
          <Link href="/products" className="block text-center text-sm font-bold" style={{ color: "var(--brand)" }}>← {t("cart.continue")}</Link>
        </aside>
      </div>
    </>
  );
}
