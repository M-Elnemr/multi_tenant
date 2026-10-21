"use client";

import Link from "next/link";
import { money } from "@/lib/format";
import { toggleWish, useWishlist } from "@/lib/open-client";
import { num, type OpenProduct } from "@/lib/open-types";
import { Icon } from "../icons";
import { useI18n } from "../i18n-provider";

export function WishButton({ p, className = "" }: { p: OpenProduct; className?: string }) {
  const { t } = useI18n();
  const on = useWishlist().some((w) => w.id === p.id);
  return (
    <button type="button" aria-pressed={on} aria-label={on ? t("open.removeFromWishlist") : t("open.addToWishlist")}
      onClick={(e) => { e.preventDefault(); e.stopPropagation(); toggleWish({ id: p.id, shopSlug: p.shop.slug, slug: p.slug, name: p.name, image: p.image?.medium ?? null, priceMinor: p.minPriceMinor, currency: p.currency }); }}
      className={`grid h-9 w-9 place-items-center rounded-full bg-white/95 shadow-md backdrop-blur transition hover:scale-110 active:scale-95 ${className}`}>
      <Icon name="heart" className={`h-[18px] w-[18px] transition ${on ? "fill-[var(--o-red)] text-[var(--o-red)]" : "text-[var(--o-ink)]"}`} />
    </button>
  );
}

/** One product tile: picture, discount/badge, shop name, rating, price with the old price struck through. */
export function OpenProductCard({ p, className = "" }: { p: OpenProduct; className?: string }) {
  const { t, locale } = useI18n();
  const discount = Math.round(num(p.discountPct));
  const rating = num(p.rating);
  const badge = p.badge || "";
  return (
    <Link href={`/p/${p.shop.slug}/${p.slug}`} className={`o-pc group ${className}`}>
      <div className="o-pc-img">
        {p.image ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={p.image.medium} alt={p.name} width={420} height={420} loading="lazy" decoding="async" className={p.inStock === false ? "opacity-55 grayscale" : ""} />
        ) : (
          <div className="grid h-full place-items-center text-5xl opacity-30">🛍️</div>
        )}
        <div className="absolute start-2.5 top-2.5 flex flex-col items-start gap-1">
          {discount > 0 && <span className="o-badge o-badge-sale">-{discount}%</span>}
          {badge === "NEW" && <span className="o-badge o-badge-new">{t("open.badge.NEW")}</span>}
          {badge === "BEST_SELLER" && <span className="o-badge o-badge-best">{t("open.badge.BEST_SELLER")}</span>}
        </div>
        <WishButton p={p} className="absolute end-2.5 top-2.5" />
        {p.inStock === false && <span className="absolute inset-x-2.5 bottom-2.5 rounded-full bg-[var(--o-navy)]/85 py-1 text-center text-xs font-bold text-white backdrop-blur">{t("open.outOfStock")}</span>}
      </div>
      <div className="flex flex-1 flex-col gap-1 p-3">
        <p className="line-clamp-1 text-[11px] font-bold uppercase tracking-wide text-[var(--o-mute)]">{p.shop.name}</p>
        <p className="line-clamp-2 min-h-[2.5rem] text-[14px] font-bold leading-snug">{p.name}</p>
        {rating > 0 ? <p className="flex items-center gap-1 text-xs font-bold"><span className="o-stars">★</span>{rating.toFixed(1)}<span className="font-medium text-[var(--o-mute)]">({p.ratingCount})</span></p> : <p className="h-4" />}
        <div className="mt-auto flex flex-wrap items-baseline gap-x-2 pt-1">
          <span className="text-[17px] font-extrabold text-[var(--o-ink)]">{money(p.minPriceMinor, p.currency, locale)}</span>
          {p.compareAtMinor ? <span className="text-xs text-[var(--o-mute)] line-through">{money(p.compareAtMinor, p.currency, locale)}</span> : null}
        </div>
        {p.freeShipping && <span className="o-badge o-badge-free w-fit">{t("open.freeShipping")}</span>}
      </div>
    </Link>
  );
}

export function ProductGrid({ products }: { products: OpenProduct[] }) {
  return (
    <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 md:gap-4 lg:grid-cols-4 xl:grid-cols-5">
      {products.map((p, i) => <div key={p.id} className="o-reveal" style={{ animationDelay: `${Math.min(i, 10) * 30}ms` }}><OpenProductCard p={p} /></div>)}
    </div>
  );
}
