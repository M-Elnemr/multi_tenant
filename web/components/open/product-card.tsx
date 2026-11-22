"use client";

import Link from "next/link";
import { money } from "@/lib/format";
import { toggleWish, useWishlist } from "@/lib/open-client";
import { num, type OpenProduct } from "@/lib/open-types";
import { Icon } from "../icons";
import { useI18n } from "../i18n-provider";
import { OImg } from "./media";

export function WishButton({ p, className = "" }: { p: OpenProduct; className?: string }) {
  const { t } = useI18n();
  const on = useWishlist().some((w) => w.id === p.id);
  return (
    <button type="button" aria-pressed={on} aria-label={on ? t("open.removeFromWishlist") : t("open.addToWishlist")}
      onClick={(e) => { e.preventDefault(); e.stopPropagation(); toggleWish({ id: p.id, shopSlug: p.shop.slug, slug: p.slug, name: p.name, image: p.image?.medium ?? null, priceMinor: p.minPriceMinor, currency: p.currency }); }}
      className={`grid h-10 w-10 place-items-center rounded-full bg-[var(--o-paper)]/95 shadow-[0_2px_10px_-2px_rgb(23_19_14/.25)] backdrop-blur transition duration-200 hover:scale-105 active:scale-90 ${className}`}>
      <Icon name="heart" className={`h-[18px] w-[18px] transition duration-200 ${on ? "scale-110 fill-[var(--o-accent)] text-[var(--o-accent)]" : "text-[var(--o-ink)]"}`} />
    </button>
  );
}

/** One product tile: a 4:5 picture, discount/badge, the shop, the name, rating, and the price with the old price struck through. */
export function OpenProductCard({ p, className = "" }: { p: OpenProduct; className?: string }) {
  const { t, locale } = useI18n();
  const discount = Math.round(num(p.discountPct));
  const rating = num(p.rating);
  const badge = p.badge || "";
  return (
    <Link href={`/p/${p.shop.slug}/${p.slug}`} className={`o-pc group ${className}`}>
      <div className="o-pc-img">
        <OImg src={p.image?.medium} alt={p.name} name={p.name} className={p.inStock === false ? "opacity-50 grayscale" : ""} />
        <div className="absolute start-2.5 top-2.5 flex flex-col items-start gap-1">
          {discount > 0 && <span className="o-badge o-badge-sale o-num">-{discount}%</span>}
          {badge === "NEW" && <span className="o-badge o-badge-new">{t("open.badge.NEW")}</span>}
          {badge === "BEST_SELLER" && <span className="o-badge o-badge-best">{t("open.badge.BEST_SELLER")}</span>}
        </div>
        <WishButton p={p} className="absolute end-2.5 top-2.5" />
        {p.inStock === false && <span className="absolute inset-x-2.5 bottom-2.5 rounded-full bg-[var(--o-ink)]/85 py-1.5 text-center text-xs font-bold text-white backdrop-blur">{t("open.outOfStock")}</span>}
      </div>
      <div className="flex flex-1 flex-col gap-1 pt-3">
        <p className="line-clamp-1 text-[11.5px] font-semibold text-[var(--o-mute)]">{p.shop.name}</p>
        <p className="line-clamp-2 min-h-[2.6rem] text-[14.5px] font-semibold leading-snug transition-colors group-hover:text-[var(--o-accent)]">{p.name}</p>
        <div className="mt-auto flex flex-wrap items-baseline gap-x-2 pt-1">
          <span className="o-num text-[16.5px] font-extrabold">{money(p.minPriceMinor, p.currency, locale)}</span>
          {p.compareAtMinor ? <span className="o-num text-xs text-[var(--o-faint)] line-through">{money(p.compareAtMinor, p.currency, locale)}</span> : null}
        </div>
        <div className="flex min-h-[1.25rem] flex-wrap items-center gap-x-3 gap-y-1 text-xs">
          {rating > 0 && <span className="flex items-center gap-1 font-bold"><span className="o-stars">★</span><span className="o-num">{rating.toFixed(1)}</span><span className="o-num font-medium text-[var(--o-mute)]">({p.ratingCount})</span></span>}
          {p.freeShipping && <span className="flex items-center gap-1 font-semibold text-[var(--o-green)]"><Icon name="truck" className="h-3.5 w-3.5" />{t("open.freeShipping")}</span>}
        </div>
      </div>
    </Link>
  );
}

export function ProductGrid({ products }: { products: OpenProduct[] }) {
  return (
    <div className="grid grid-cols-2 gap-x-3 gap-y-7 sm:grid-cols-3 md:gap-x-5 lg:grid-cols-4 xl:grid-cols-5">
      {products.map((p, i) => <div key={p.id} className="o-reveal" style={{ animationDelay: `${Math.min(i, 10) * 30}ms` }}><OpenProductCard p={p} /></div>)}
    </div>
  );
}
