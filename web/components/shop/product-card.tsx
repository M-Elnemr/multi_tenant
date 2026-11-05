import Link from "next/link";
import { money } from "@/lib/format";
import { mediaUrl } from "@/lib/media";
import type { Locale } from "@/lib/i18n";
import { Icon } from "../icons";
import type { CardKind } from "./templates/registry";

export type ShopProduct = {
  id: string; name: string; slug: string; brand?: string; currency: string; minPriceMinor: number | null; compareAtMinor?: number | null; discountPct?: number | null;
  badge?: string; offer?: { type: string; badge?: string | null; percent?: number; salePriceMinor?: number; endsAt?: string } | null; rating?: number | null; ratingCount?: number; imageUrl?: string | null; imageMediaBase?: string | null; imageMediaExt?: string | null; inStock: boolean;
};

const BADGE_CLASS: Record<string, string> = { NEW: "s-badge-new", SALE: "s-badge-sale", BEST_SELLER: "s-badge-best", LIMITED: "s-badge-dark" };
type Labels = { outOfStock: string; off: string; badges: Record<string, string> };

/** Price facts shared by every card style: a running offer wins over the stored old price (its sale price is what checkout charges). */
function pricing(p: ShopProduct) {
  const sale = p.offer?.salePriceMinor !== undefined && p.minPriceMinor !== null && p.offer.salePriceMinor < p.minPriceMinor ? p.offer.salePriceMinor : null;
  const price = sale ?? p.minPriceMinor;
  const was = sale !== null ? p.minPriceMinor : p.compareAtMinor;
  const discount = sale !== null && p.minPriceMinor ? Math.round(((p.minPriceMinor - sale) * 100) / p.minPriceMinor) : p.discountPct ? Math.round(Number(p.discountPct)) : 0;
  return { sale, price, was, discount };
}

function Img({ p, className }: { p: ShopProduct; className?: string }) {
  const src = p.imageUrl || p.imageMediaBase ? mediaUrl({ url: p.imageUrl, mediaBase: p.imageMediaBase, mediaExt: p.imageMediaExt }, "medium") : null;
  return src
    // eslint-disable-next-line @next/next/no-img-element
    ? <img src={src} alt={p.name} width={480} height={600} decoding="async" loading="lazy" className={`s-pimg h-full w-full object-cover ${p.inStock ? "" : "opacity-60 grayscale"} ${className ?? ""}`} />
    : <div className="grid h-full w-full place-items-center text-[var(--s-mute)] opacity-40"><Icon name="bag" className="h-10 w-10" /></div>;
}

function Badges({ p, labels, discount, sale }: { p: ShopProduct; labels: Labels; discount: number; sale: number | null }) {
  const flash = p.offer?.type === "FLASH_SALE";
  const offerBadge = p.offer?.badge || "";
  const badge = p.badge || (discount > 0 ? "SALE" : "");
  if (offerBadge) return <span className={`s-badge s-badge-sale inline-flex items-center gap-1 ${flash ? "animate-pulse" : ""}`}>{flash && <Icon name="bolt" className="h-3 w-3" />}{offerBadge}</span>;
  if (p.offer && sale === null) return <span className="s-badge s-badge-sale inline-flex items-center gap-1"><Icon name="tag" className="h-3 w-3" />{labels.off}</span>;
  if (!badge) return null;
  return <span className={`s-badge inline-flex items-center gap-1 ${BADGE_CLASS[badge] ?? ""}`}>{flash && <Icon name="bolt" className="h-3 w-3" />}{discount > 0 && badge === "SALE" ? `-${discount}%` : labels.badges[badge] ?? badge}</span>;
}

function Price({ p, locale, sale, price, was, className = "" }: { p: ShopProduct; locale: Locale; sale: number | null; price: number | null; was?: number | null; className?: string }) {
  return (
    <p className={`flex flex-wrap items-baseline gap-x-2 ${className}`}>
      <span className="s-price font-extrabold" data-sale={sale !== null || undefined}>{money(price, p.currency, locale)}</span>
      {was ? <span className="s-was text-[0.8em] text-[var(--s-mute)] line-through">{money(was, p.currency, locale)}</span> : null}
    </p>
  );
}

/**
 * Product tile. `variant` comes from the shop's template (see templates/registry.ts): the same product, presented the way that design presents it —
 * a framed tile, a quiet editorial portrait, a loud sticker, a spec card, a menu row, or a caption over the photo.
 */
export function ShopProductCard({ p, locale, labels, variant = "classic" }: { p: ShopProduct; locale: Locale; labels: Labels; variant?: CardKind }) {
  const { sale, price, was, discount } = pricing(p);

  if (variant === "row") {
    // menu row (food): photo to the side, name, price and a round add button; reads like a menu, not a catalogue
    return (
      <Link href={`/products/${p.slug}`} data-card="row" className="s-pcard group flex h-full items-center gap-4 rounded-[18px] border border-[var(--s-line)] bg-[var(--s-card)] p-3 transition hover:border-[color-mix(in_srgb,var(--brand)_40%,var(--s-line))]">
        <div className="s-pcard-img relative h-24 w-24 shrink-0 overflow-hidden rounded-[14px] bg-[var(--s-soft)] sm:h-28 sm:w-28"><Img p={p} className="transition duration-700 group-hover:scale-110" /></div>
        <div className="min-w-0 flex-1 space-y-1">
          <div className="flex items-center gap-2"><Badges p={p} labels={labels} discount={discount} sale={sale} /></div>
          <p className="line-clamp-2 text-[15px] font-bold leading-snug">{p.name}</p>
          {p.brand && <p className="truncate text-xs text-[var(--s-mute)]">{p.brand}</p>}
          <Price p={p} locale={locale} sale={sale} price={price} was={was} className="pt-0.5 text-base" />
          {!p.inStock && <p className="text-xs font-semibold text-[var(--s-mute)]">{labels.outOfStock}</p>}
        </div>
        <span className="s-add grid h-10 w-10 shrink-0 place-items-center rounded-full text-white transition group-hover:scale-110" style={{ background: "var(--brand)" }} aria-hidden><Icon name="plus" className="h-5 w-5" /></span>
      </Link>
    );
  }

  if (variant === "overlay") {
    // lifestyle: the photo is the card; the caption rises on hover (always visible on touch)
    return (
      <Link href={`/products/${p.slug}`} data-card="overlay" className="s-pcard group relative block aspect-[4/5] overflow-hidden rounded-[6px] bg-[var(--s-soft)]">
        <Img p={p} className="transition duration-[1.2s] ease-out group-hover:scale-[1.06]" />
        <div className="absolute start-3 top-3"><Badges p={p} labels={labels} discount={discount} sale={sale} /></div>
        <div className="absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/70 via-black/25 to-transparent p-4 pt-16 text-white transition duration-500 sm:translate-y-3 sm:opacity-90 sm:group-hover:translate-y-0 sm:group-hover:opacity-100">
          {p.brand && <p className="text-[11px] uppercase tracking-[0.18em] text-white/70">{p.brand}</p>}
          <p className="line-clamp-1 text-[15px] font-semibold">{p.name}</p>
          <Price p={p} locale={locale} sale={sale} price={price} was={was} className="mt-0.5 text-sm [&_.s-price]:!text-white [&_.s-was]:!text-white/60" />
        </div>
        {!p.inStock && <span className="absolute end-3 top-3 rounded-full bg-black/60 px-2.5 py-1 text-[11px] font-bold text-white backdrop-blur">{labels.outOfStock}</span>}
      </Link>
    );
  }

  const aspect = variant === "tall" ? "aspect-[3/4]" : variant === "spec" || variant === "round" ? "aspect-square" : variant === "arch" ? "aspect-[4/5]" : "aspect-[4/5]";
  return (
    <Link href={`/products/${p.slug}`} data-card={variant} className="s-lift s-pcard group block h-full overflow-hidden rounded-[22px] border border-[var(--s-line)] bg-[var(--s-card)]">
      <div className={`s-pcard-img relative ${aspect} overflow-hidden bg-[var(--s-soft)]`}>
        <Img p={p} className="transition duration-700 ease-out group-hover:scale-[1.06]" />
        <div className="absolute start-3 top-3 flex flex-col items-start gap-1.5"><Badges p={p} labels={labels} discount={discount} sale={sale} /></div>
        {!p.inStock && <span className="s-oos absolute inset-x-3 bottom-3 rounded-full bg-[var(--s-ink)]/85 py-1.5 text-center text-xs font-bold text-white backdrop-blur">{labels.outOfStock}</span>}
        {variant === "sticker" && price !== null && <span className="s-sticker absolute bottom-3 end-3 rotate-[-6deg] px-2.5 py-1 text-sm font-black">{money(price, p.currency, locale)}</span>}
      </div>
      <div className="s-pcard-body space-y-1 p-4">
        {p.brand && <p className="s-pbrand text-[11px] font-bold uppercase tracking-wider text-[var(--s-mute)]">{p.brand}</p>}
        <p className="s-pname line-clamp-2 min-h-[2.6rem] text-[15px] font-bold leading-snug">{p.name}</p>
        {p.rating ? <p className="s-rating flex items-center gap-1 text-xs font-semibold text-amber-600"><Icon name="star" className="h-3.5 w-3.5 fill-current" />{p.rating} <span className="font-normal text-[var(--s-mute)]">({p.ratingCount})</span></p> : null}
        <Price p={p} locale={locale} sale={sale} price={price} was={was} className="pt-0.5 text-lg" />
      </div>
    </Link>
  );
}
