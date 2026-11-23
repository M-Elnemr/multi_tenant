"use client";
/* eslint-disable @next/next/no-img-element */

import Link from "next/link";
import { useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { clearCart, lineKey, removeLine, setNote, setQuantity, useCart } from "@/lib/cart";
import { money } from "@/lib/format";

type ShipProfile = { shippingMethods: { type: string; freeAboveMinor?: number | null }[]; shippingZones: { freeAboveMinor?: number | null }[] };

/** Cart: rounded line cards with a roomy picture, and a soft summary card with a friendly free-delivery progress ring of text and bar. */
export function LifestyleCart() {
  const { t, locale, currency } = useI18n();
  const lines = useCart();
  const total = lines.reduce((n, l) => n + l.unitPriceMinor * l.quantity, 0);
  const count = lines.reduce((n, l) => n + l.quantity, 0);
  const ship = useApi<ShipProfile>("shop/profile");
  const th = [...(ship.data?.shippingMethods ?? []).filter((m) => m.type === "FREE_ABOVE").map((m) => m.freeAboveMinor), ...(ship.data?.shippingZones ?? []).map((z) => z.freeAboveMinor)].filter((x): x is number => typeof x === "number" && x > 0);
  const freeAt = th.length ? Math.min(...th) : null;
  const pct = freeAt ? Math.min(100, Math.round((total / freeAt) * 100)) : 0;
  if (lines.length === 0) return <div className="lf-wrap lf-page"><div className="lf-empty"><span className="lf-empty-ico"><Icon name="bag" className="h-10 w-10" /></span><h1>{t("cart.empty")}</h1><p>{t("cart.emptyHint")}</p><Link href="/products" className="lf-btn">{t("shop.browse")}</Link></div></div>;
  return (
    <div className="lf-wrap lf-page">
      <div className="lf-cart-head"><h1>{t("nav.cart")} <small>({count})</small></h1><button className="lf-btn lf-btn-soft" onClick={clearCart}>{t("cart.clear")}</button></div>
      <div className="lf-cart">
        <ul className="lf-lines">
          {lines.map((l) => (
            <li key={lineKey(l)} className="lf-line">
              <Link href={`/products/${l.slug}`} className="lf-line-pic">{l.imageUrl ? <img src={l.imageUrl} alt="" /> : <Icon name="bag" className="h-8 w-8" />}</Link>
              <div className="lf-line-main">
                <Link href={`/products/${l.slug}`} className="lf-line-name">{l.name}</Link>
                {l.variantLabel && <span className="lf-muted">{l.variantLabel}</span>}
                <input className="lf-input" defaultValue={l.note ?? ""} onBlur={(e) => setNote(lineKey(l), e.target.value.trim())} maxLength={200} placeholder={t("shop.itemNote")} aria-label={t("shop.itemNote")} />
                <div className="lf-line-foot">
                  <div className="lg-qty" role="group" aria-label={t("lg.quantity")}><button onClick={() => setQuantity(lineKey(l), l.quantity - 1)} aria-label="-"><Icon name="minus" className="h-4 w-4" /></button><span>{l.quantity}</span><button onClick={() => setQuantity(lineKey(l), l.quantity + 1)} aria-label="+"><Icon name="plus" className="h-4 w-4" /></button></div>
                  <b>{money(l.unitPriceMinor * l.quantity, currency, locale)}</b>
                </div>
              </div>
              <button className="lf-round lf-rm" onClick={() => removeLine(lineKey(l))} aria-label={t("common.delete")}><Icon name="close" className="h-4 w-4" /></button>
            </li>
          ))}
        </ul>
        <aside className="lf-sum">
          <h2>{t("checkout.summary")}</h2>
          {freeAt !== null && <div className="lf-free"><p>{total >= freeAt ? t("cart.freeReached") : t("cart.freeMore", { amount: money(freeAt - total, currency, locale) })}</p><div className="lf-meter" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct}><i style={{ width: `${pct}%` }} /></div></div>}
          <p className="lf-sum-row"><span>{t("checkout.subtotal")}</span><b>{money(total, currency, locale)}</b></p>
          <p className="lf-muted">{t("cart.shippingNote")}</p>
          <Link href="/checkout" className="lf-btn">{t("cart.checkout")}</Link>
          <Link href="/products" className="lf-btn lf-btn-soft">{t("cart.continue")}</Link>
        </aside>
      </div>
    </div>
  );
}
