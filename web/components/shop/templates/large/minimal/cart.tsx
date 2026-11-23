"use client";
/* eslint-disable @next/next/no-img-element */

import Link from "next/link";
import { useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { clearCart, lineKey, removeLine, setNote, setQuantity, useCart } from "@/lib/cart";
import { money } from "@/lib/format";

type ShipProfile = { shippingMethods: { type: string; freeAboveMinor?: number | null }[]; shippingZones: { freeAboveMinor?: number | null }[] };

/** Cart as a ruled table: product, quantity, total, with the order total in one line at the foot. */
export function MinimalCart() {
  const { t, locale, currency } = useI18n();
  const lines = useCart();
  const total = lines.reduce((n, l) => n + l.unitPriceMinor * l.quantity, 0);
  const ship = useApi<ShipProfile>("shop/profile");
  const th = [...(ship.data?.shippingMethods ?? []).filter((m) => m.type === "FREE_ABOVE").map((m) => m.freeAboveMinor), ...(ship.data?.shippingZones ?? []).map((z) => z.freeAboveMinor)].filter((x): x is number => typeof x === "number" && x > 0);
  const freeAt = th.length ? Math.min(...th) : null;
  if (lines.length === 0) return <div className="mn-wrap mn-page mn-cart-empty"><h1 className="mn-title">{t("cart.empty")}</h1><p className="mn-muted">{t("cart.emptyHint")}</p><Link href="/products" className="mn-btn">{t("shop.browse")}</Link></div>;
  return (
    <div className="mn-wrap mn-page">
      <h1 className="mn-title">{t("nav.cart")}</h1>
      <table className="mn-table">
        <thead><tr><th scope="col">{t("nav.cart")}</th><th scope="col">{t("lg.quantity")}</th><th scope="col" className="mn-r">{t("checkout.subtotal")}</th></tr></thead>
        <tbody>
          {lines.map((l) => (
            <tr key={lineKey(l)}>
              <td>
                <div className="mn-prod">
                  <Link href={`/products/${l.slug}`} className="mn-thumb">{l.imageUrl ? <img src={l.imageUrl} alt="" /> : null}</Link>
                  <div><Link href={`/products/${l.slug}`} className="mn-pname">{l.name}</Link>{l.variantLabel && <p className="mn-muted">{l.variantLabel}</p>}
                    <input className="mn-input" defaultValue={l.note ?? ""} onBlur={(e) => setNote(lineKey(l), e.target.value.trim())} maxLength={200} placeholder={t("shop.itemNote")} aria-label={t("shop.itemNote")} />
                    <button className="mn-under mn-rm" onClick={() => removeLine(lineKey(l))}>{t("common.delete")}</button></div>
                </div>
              </td>
              <td><div className="lg-qty" role="group" aria-label={t("lg.quantity")}><button onClick={() => setQuantity(lineKey(l), l.quantity - 1)} aria-label="-">−</button><span>{l.quantity}</span><button onClick={() => setQuantity(lineKey(l), l.quantity + 1)} aria-label="+">+</button></div></td>
              <td className="mn-r mn-sum">{money(l.unitPriceMinor * l.quantity, currency, locale)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="mn-foot-total">
        <button className="mn-under" onClick={clearCart}>{t("cart.clear")}</button>
        <div>
          <p className="mn-total"><span>{t("checkout.subtotal")}</span><b>{money(total, currency, locale)}</b></p>
          {freeAt !== null && <p className="mn-muted">{total >= freeAt ? t("cart.freeReached") : t("cart.freeMore", { amount: money(freeAt - total, currency, locale) })}</p>}
          <p className="mn-muted">{t("cart.shippingNote")}</p>
          <Link href="/checkout" className="mn-btn">{t("cart.checkout")}</Link>
        </div>
      </div>
    </div>
  );
}
