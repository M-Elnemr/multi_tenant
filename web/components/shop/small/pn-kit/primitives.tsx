/* eslint-disable @next/next/no-img-element */
import Link from "next/link";
import { Icon } from "@/components/icons";
import type { ShopProduct } from "@/components/shop/product-card";
import { money } from "@/lib/format";
import type { Locale } from "@/lib/i18n";
import { mediaUrl } from "@/lib/media";

/** Shared building blocks of the five small-shop designs (pn-*): real product data only, no invented numbers or claims. */
export type T = (k: string, v?: Record<string, string | number>) => string;

type MediaRef = { url?: string | null; mediaBase?: string | null; mediaExt?: string | null };
export const picOf = (m?: MediaRef | null, size: "medium" | "original" = "original") => (m && (m.url || m.mediaBase) ? mediaUrl({ url: m.url, mediaBase: m.mediaBase, mediaExt: m.mediaExt }, size) : null);
export const cardPic = (x?: ShopProduct, size: "medium" | "original" = "original") => (x ? picOf({ url: x.imageUrl, mediaBase: x.imageMediaBase, mediaExt: x.imageMediaExt }, size) : null);

/** The price a visitor pays (a running offer wins), with the old price struck through only when it is really higher. */
export function PnPrice({ x, locale, className }: { x: ShopProduct; locale: Locale; className?: string }) {
  const sale = x.offer?.salePriceMinor !== undefined && x.minPriceMinor !== null && x.offer.salePriceMinor < x.minPriceMinor ? x.offer.salePriceMinor : null;
  const now = sale ?? x.minPriceMinor;
  const was = sale !== null ? x.minPriceMinor : x.compareAtMinor && x.minPriceMinor !== null && x.compareAtMinor > x.minPriceMinor ? x.compareAtMinor : null;
  if (now === null) return null;
  return <span className={`pn-price tnum ${className ?? ""}`}>{was !== null && <s className="pn-was">{money(was, x.currency, locale)}</s>}<b>{money(now, x.currency, locale)}</b></span>;
}

export function PnStars({ value, count, label }: { value: number; count: number; label: string }) {
  if (!count) return null;
  return <span className="pn-stars" aria-label={`${value.toFixed(1)} / 5 (${count} ${label})`}><span aria-hidden className="pn-gold">{"★".repeat(Math.round(value))}<i>{"★".repeat(5 - Math.round(value))}</i></span><span className="tnum">{value.toFixed(1)}</span><span className="pn-mute">({count})</span></span>;
}

export function PnButton({ href, children, ghost }: { href: string; children: React.ReactNode; ghost?: boolean }) {
  return <Link href={href} className={`pn-btn ${ghost ? "pn-btn-ghost" : ""}`}>{children}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Link>;
}

/** A photo, or a plain tinted block when the product has none. */
export function PnPhoto({ src, alt = "", className, priority }: { src: string | null; alt?: string; className?: string; priority?: boolean }) {
  return src ? <img src={src} alt={alt} decoding="async" loading={priority ? "eager" : "lazy"} className={`pn-photo ${className ?? ""}`} /> : <div className={`pn-photo pn-photo-empty ${className ?? ""}`} aria-hidden><Icon name="bag" className="h-10 w-10" /></div>;
}

/** Small product card used for the "also from us" band and the trio designs. */
export function PnCard({ x, locale, t }: { x: ShopProduct; locale: Locale; t: T }) {
  return (
    <Link href={`/products/${x.slug}`} className="pn-card">
      <div className="pn-card-media"><PnPhoto src={cardPic(x, "medium")} alt={x.name} />{!x.inStock && <span className="pn-tag">{t("shop.outOfStock")}</span>}</div>
      <div className="pn-card-body"><h3>{x.name}</h3><PnPrice x={x} locale={locale} /></div>
    </Link>
  );
}

export function PnEmpty({ name, t }: { name: string; t: T }) {
  return <section className="pn-wrap pn-empty"><h1>{name}</h1><p>{t("shop.empty")}</p></section>;
}
