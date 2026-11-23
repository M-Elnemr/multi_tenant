/* eslint-disable @next/next/no-img-element */
import { Icon } from "@/components/icons";
import type { ShopProduct } from "@/components/shop/product-card";
import { money } from "@/lib/format";
import type { Locale, Translate } from "@/lib/i18n";
import { mediaUrl } from "@/lib/media";

/** Price facts shared by every large template: a running offer's sale price is what checkout charges; otherwise the shop's own old price shows the saving. */
export function priceOf(p: ShopProduct) {
  const sale = p.offer?.salePriceMinor !== undefined && p.minPriceMinor !== null && p.offer.salePriceMinor < p.minPriceMinor ? p.offer.salePriceMinor : null;
  const price = sale ?? p.minPriceMinor;
  const was = sale !== null ? p.minPriceMinor : p.compareAtMinor;
  const discount = sale !== null && p.minPriceMinor ? Math.round(((p.minPriceMinor - sale) * 100) / p.minPriceMinor) : p.discountPct ? Math.round(Number(p.discountPct)) : 0;
  return { sale, price, was, discount };
}

export type BadgeInfo = { text: string; kind: "sale" | "new" | "best" | "limited" | "offer" } | null;
export function badgeOf(p: ShopProduct, t: Translate): BadgeInfo {
  const { discount, sale } = priceOf(p);
  if (p.offer?.badge) return { text: p.offer.badge, kind: "offer" };
  if (p.offer && sale === null) return { text: t("shop.off"), kind: "offer" };
  const b = p.badge || (discount > 0 ? "SALE" : "");
  if (!b) return null;
  const kind = b === "SALE" ? "sale" : b === "NEW" ? "new" : b === "BEST_SELLER" ? "best" : "limited";
  return { text: discount > 0 && b === "SALE" ? `-${discount}%` : t(`badge.${b}`), kind };
}

export const productImage = (p: ShopProduct, v: "thumb" | "medium" | "original" = "medium") =>
  p.imageUrl || p.imageMediaBase ? mediaUrl({ url: p.imageUrl, mediaBase: p.imageMediaBase, mediaExt: p.imageMediaExt }, v) : null;

/** The product picture, or a quiet placeholder when it has none. Sold-out products are dimmed by the template's CSS through data-sold. */
export function Pic({ p, className = "", v = "medium" }: { p: ShopProduct; className?: string; v?: "thumb" | "medium" | "original" }) {
  const src = productImage(p, v);
  return src
    ? <img src={src} alt={p.name} width={480} height={600} decoding="async" loading="lazy" className={`lg-img ${className}`} data-sold={!p.inStock || undefined} />
    : <div className={`lg-img lg-img-ph ${className}`} aria-hidden><Icon name="bag" className="h-9 w-9" /></div>;
}

export function Money({ minor, currency, locale }: { minor: number | null | undefined; currency: string; locale: Locale }) {
  return <>{money(minor, currency, locale)}</>;
}

/** Price block: current price, old price struck through when there is one. */
export function PriceLine({ p, locale, className = "" }: { p: ShopProduct; locale: Locale; className?: string }) {
  const { sale, price, was } = priceOf(p);
  return (
    <p className={`lg-price ${className}`}>
      <span className="lg-price-now" data-sale={sale !== null || (was ? true : undefined) || undefined}>{money(price, p.currency, locale)}</span>
      {was ? <s className="lg-price-was">{money(was, p.currency, locale)}</s> : null}
    </p>
  );
}

export type CardLabels = { outOfStock: string; off: string };
export const cardLabels = (t: Translate): CardLabels => ({ outOfStock: t("shop.outOfStock"), off: t("shop.off") });
