import Link from "next/link";
import { money } from "@/lib/format";
import { mediaUrl } from "@/lib/media";
import type { Locale } from "@/lib/i18n";

export type ShopProduct = {
  id: string; name: string; slug: string; brand?: string; currency: string; minPriceMinor: number | null; compareAtMinor?: number | null; discountPct?: number | null;
  badge?: string; rating?: number | null; ratingCount?: number; imageUrl?: string | null; imageMediaBase?: string | null; imageMediaExt?: string | null; inStock: boolean;
};

const BADGE_CLASS: Record<string, string> = { NEW: "s-badge-new", SALE: "s-badge-sale", BEST_SELLER: "s-badge-best", LIMITED: "s-badge-dark" };

/** Product tile: big image with zoom on hover, price with the old price struck through, discount and badge chips, rating. */
export function ShopProductCard({ p, locale, labels }: { p: ShopProduct; locale: Locale; labels: { outOfStock: string; off: string; badges: Record<string, string> } }) {
  const discount = p.discountPct ? Math.round(Number(p.discountPct)) : 0;
  const badge = p.badge || (discount > 0 ? "SALE" : "");
  return (
    <Link href={`/products/${p.slug}`} className="s-lift group block h-full overflow-hidden rounded-[22px] border border-[var(--s-line)] bg-white">
      <div className="relative aspect-[4/5] overflow-hidden bg-[var(--s-soft)]">
        {p.imageUrl || p.imageMediaBase ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={mediaUrl({ url: p.imageUrl, mediaBase: p.imageMediaBase, mediaExt: p.imageMediaExt }, "medium") ?? ""} alt={p.name} width={480} height={600} decoding="async" loading="lazy" className={`h-full w-full object-cover transition duration-700 ease-out group-hover:scale-[1.07] ${p.inStock ? "" : "opacity-60 grayscale"}`} />
        ) : (
          <div className="flex h-full items-center justify-center text-5xl opacity-30">🛍️</div>
        )}
        <div className="absolute start-3 top-3 flex flex-col items-start gap-1.5">
          {badge && <span className={`s-badge ${BADGE_CLASS[badge] ?? ""}`}>{discount > 0 && badge === "SALE" ? `-${discount}%` : labels.badges[badge] ?? badge}</span>}
        </div>
        {!p.inStock && <span className="absolute inset-x-3 bottom-3 rounded-full bg-[var(--s-ink)]/85 py-1.5 text-center text-xs font-bold text-white backdrop-blur">{labels.outOfStock}</span>}
      </div>
      <div className="space-y-1 p-4">
        {p.brand && <p className="text-[11px] font-bold uppercase tracking-wider text-[var(--s-mute)]">{p.brand}</p>}
        <p className="line-clamp-2 min-h-[2.6rem] text-[15px] font-bold leading-snug">{p.name}</p>
        {p.rating ? <p className="text-xs font-semibold text-amber-600">★ {p.rating} <span className="font-normal text-[var(--s-mute)]">({p.ratingCount})</span></p> : null}
        <p className="flex items-baseline gap-2 pt-0.5">
          <span className="text-lg font-extrabold" style={{ color: "var(--brand)" }}>{money(p.minPriceMinor, p.currency, locale)}</span>
          {p.compareAtMinor ? <span className="text-sm text-[var(--s-mute)] line-through">{money(p.compareAtMinor, p.currency, locale)}</span> : null}
        </p>
      </div>
    </Link>
  );
}
