"use client";
/* eslint-disable @next/next/no-img-element */

import Link from "next/link";
import { useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { clearCart, lineKey, removeLine, setNote, setQuantity, useCart } from "@/lib/cart";
import { money } from "@/lib/format";

type ShipProfile = { shippingMethods: { type: string; freeAboveMinor?: number | null }[]; shippingZones: { freeAboveMinor?: number | null }[] };

/** Line list on one side, a sticky order summary with the free-delivery nudge on the other. Cart storage and totals are the shop's own (lib/cart). */
export function MarketCart() {
  const { t, locale, currency } = useI18n();
  const lines = useCart();
  const total = lines.reduce((n, l) => n + l.unitPriceMinor * l.quantity, 0);
  const count = lines.reduce((n, l) => n + l.quantity, 0);
  const ship = useApi<ShipProfile>("shop/profile");
  const th = [...(ship.data?.shippingMethods ?? []).filter((m) => m.type === "FREE_ABOVE").map((m) => m.freeAboveMinor), ...(ship.data?.shippingZones ?? []).map((z) => z.freeAboveMinor)].filter((x): x is number => typeof x === "number" && x > 0);
  const freeAt = th.length ? Math.min(...th) : null;
  if (lines.length === 0) {
    return (
      <div className="lg-wrap mk-page"><div className="lg-empty mk-empty"><Icon name="cart" className="h-12 w-12" /><h1 className="mk-h2">{t("cart.empty")}</h1><p>{t("cart.emptyHint")}</p><Link href="/products" className="mk-btn">{t("shop.browse")}</Link></div></div>
    );
  }
  return (
    <div className="lg-wrap mk-page">
      <div className="mk-cart-head"><h1 className="mk-title">{t("nav.cart")} <small>({count})</small></h1><button className="mk-link" onClick={clearCart}>{t("cart.clear")}</button></div>
      <div className="mk-cart">
        <ul className="mk-lines">
          {lines.map((l) => (
            <li key={lineKey(l)} className="mk-line">
              <Link href={`/products/${l.slug}`} className="mk-line-pic">{l.imageUrl ? <img src={l.imageUrl} alt="" /> : <Icon name="bag" className="h-8 w-8" />}</Link>
              <div className="mk-line-main">
                <Link href={`/products/${l.slug}`} className="mk-line-name">{l.name}</Link>
                {l.variantLabel && <span className="mk-muted">{l.variantLabel}</span>}
                <input className="mk-note" defaultValue={l.note ?? ""} onBlur={(e) => setNote(lineKey(l), e.target.value.trim())} maxLength={200} placeholder={t("shop.itemNote")} aria-label={t("shop.itemNote")} />
                <div className="mk-line-foot">
                  <div className="lg-qty" role="group" aria-label={t("lg.quantity")}>
                    <button onClick={() => setQuantity(lineKey(l), l.quantity - 1)} aria-label="-"><Icon name="minus" className="h-4 w-4" /></button>
                    <span>{l.quantity}</span>
                    <button onClick={() => setQuantity(lineKey(l), l.quantity + 1)} aria-label="+"><Icon name="plus" className="h-4 w-4" /></button>
                  </div>
                  <b>{money(l.unitPriceMinor * l.quantity, currency, locale)}</b>
                </div>
              </div>
              <button className="lg-iconbtn mk-remove" onClick={() => removeLine(lineKey(l))} aria-label={t("common.delete")}><Icon name="close" className="h-4 w-4" /></button>
            </li>
          ))}
        </ul>
        <aside className="mk-summary">
          <h2>{t("checkout.summary")}</h2>
          <p className="mk-sum-row"><span>{t("checkout.subtotal")}</span><b>{money(total, currency, locale)}</b></p>
          {freeAt !== null && (
            <div className="mk-free">
              <p>{total >= freeAt ? t("cart.freeReached") : t("cart.freeMore", { amount: money(freeAt - total, currency, locale) })}</p>
              <div className="mk-meter" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.min(100, Math.round((total / freeAt) * 100))}><i style={{ width: `${Math.min(100, Math.round((total / freeAt) * 100))}%` }} /></div>
            </div>
          )}
          <p className="mk-muted">{t("cart.shippingNote")}</p>
          <Link href="/checkout" className="mk-btn">{t("cart.checkout")}</Link>
          <p className="mk-muted mk-center">{t("trust.codText")}</p>
          <Link href="/products" className="mk-link mk-center">{t("cart.continue")}</Link>
        </aside>
      </div>
    </div>
  );
}
