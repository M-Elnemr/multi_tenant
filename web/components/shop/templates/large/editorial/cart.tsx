"use client";
/* eslint-disable @next/next/no-img-element */

import Link from "next/link";
import { useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { clearCart, lineKey, removeLine, setNote, setQuantity, useCart } from "@/lib/cart";
import { money } from "@/lib/format";

type ShipProfile = { shippingMethods: { type: string; freeAboveMinor?: number | null }[]; shippingZones: { freeAboveMinor?: number | null }[] };

/** The bag: ruled lines of serif titles with small pictures, totals set below as a letter-spaced statement. Same cart storage and totals as every shop. */
export function EditorialCart() {
  const { t, locale, currency } = useI18n();
  const lines = useCart();
  const total = lines.reduce((n, l) => n + l.unitPriceMinor * l.quantity, 0);
  const count = lines.reduce((n, l) => n + l.quantity, 0);
  const ship = useApi<ShipProfile>("shop/profile");
  const th = [...(ship.data?.shippingMethods ?? []).filter((m) => m.type === "FREE_ABOVE").map((m) => m.freeAboveMinor), ...(ship.data?.shippingZones ?? []).map((z) => z.freeAboveMinor)].filter((x): x is number => typeof x === "number" && x > 0);
  const freeAt = th.length ? Math.min(...th) : null;
  if (lines.length === 0) return <div className="ed-wrap ed-page ed-bag-empty"><h1>{t("cart.empty")}</h1><p>{t("cart.emptyHint")}</p><Link href="/products" className="ed-btn">{t("shop.browse")}</Link></div>;
  return (
    <div className="ed-wrap ed-page">
      <header className="ed-title-block"><p className="ed-eyebrow">{count} {t("nav.cart")}</p><h1>{t("nav.cart")}</h1></header>
      <ul className="ed-bag">
        {lines.map((l) => (
          <li key={lineKey(l)}>
            <Link href={`/products/${l.slug}`} className="ed-bag-pic">{l.imageUrl ? <img src={l.imageUrl} alt="" /> : null}</Link>
            <div className="ed-bag-main">
              <Link href={`/products/${l.slug}`} className="ed-bag-name">{l.name}</Link>
              {l.variantLabel && <span className="ed-muted">{l.variantLabel}</span>}
              <input className="ed-input" defaultValue={l.note ?? ""} onBlur={(e) => setNote(lineKey(l), e.target.value.trim())} maxLength={200} placeholder={t("shop.itemNote")} aria-label={t("shop.itemNote")} />
              <button className="ed-link ed-rm" onClick={() => removeLine(lineKey(l))}>{t("common.delete")}</button>
            </div>
            <div className="lg-qty" role="group" aria-label={t("lg.quantity")}>
              <button onClick={() => setQuantity(lineKey(l), l.quantity - 1)} aria-label="-">−</button><span>{l.quantity}</span><button onClick={() => setQuantity(lineKey(l), l.quantity + 1)} aria-label="+">+</button>
            </div>
            <b className="ed-bag-sum">{money(l.unitPriceMinor * l.quantity, currency, locale)}</b>
          </li>
        ))}
      </ul>
      <div className="ed-total">
        <button className="ed-link" onClick={clearCart}>{t("cart.clear")}</button>
        <div>
          <p className="ed-total-row"><span>{t("checkout.subtotal")}</span><b>{money(total, currency, locale)}</b></p>
          {freeAt !== null && <p className="ed-muted">{total >= freeAt ? t("cart.freeReached") : t("cart.freeMore", { amount: money(freeAt - total, currency, locale) })}</p>}
          <p className="ed-muted">{t("cart.shippingNote")}</p>
          <Link href="/checkout" className="ed-btn">{t("cart.checkout")}</Link>
          <Link href="/products" className="ed-link">{t("cart.continue")}</Link>
        </div>
      </div>
    </div>
  );
}
