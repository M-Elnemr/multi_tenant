import Link from "next/link";
import type { ShopProduct } from "@/components/shop/product-card";
import type { Locale, Translate } from "@/lib/i18n";
import { badgeOf, Pic, PriceLine } from "../parts";

/** Minimal card: a square-ish picture and one line of text each for name and price. `row` is the index-style line used in lists. */
export function MinimalCard({ p, t, locale, row }: { p: ShopProduct; t: Translate; locale: Locale; row?: boolean }) {
  const b = badgeOf(p, t);
  return (
    <article className="mn-card" data-row={row || undefined} data-sold={!p.inStock || undefined}>
      <Link href={`/products/${p.slug}`}>
        <span className="mn-card-pic"><Pic p={p} /></span>
        <span className="mn-card-txt">
          <span className="mn-card-name">{p.name}</span>
          <span className="mn-card-meta">{b && <em>{b.text}</em>}{!p.inStock && <em>{t("shop.outOfStock")}</em>}</span>
          <PriceLine p={p} locale={locale} />
        </span>
      </Link>
    </article>
  );
}
