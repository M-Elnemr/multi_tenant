import Link from "next/link";
import type { ShopProduct } from "@/components/shop/product-card";
import type { Locale, Translate } from "@/lib/i18n";
import { Stars } from "../kit";
import { badgeOf, Pic, PriceLine } from "../parts";

/** Marketplace card: picture, badge, brand, two-line name, rating, price. `row` lays it out sideways for list view. */
export function MarketCard({ p, t, locale, row }: { p: ShopProduct; t: Translate; locale: Locale; row?: boolean }) {
  const b = badgeOf(p, t);
  return (
    <article className="mk-card" data-row={row || undefined} data-sold={!p.inStock || undefined}>
      <Link href={`/products/${p.slug}`} className="mk-card-link">
        <span className="mk-card-pic">
          <Pic p={p} />
          {b && <span className="lg-badge mk-card-badge" data-kind={b.kind}>{b.text}</span>}
          {!p.inStock && <span className="mk-card-sold">{t("shop.outOfStock")}</span>}
        </span>
        <span className="mk-card-body">
          {p.brand && <span className="mk-card-brand">{p.brand}</span>}
          <span className="mk-card-name">{p.name}</span>
          {p.rating ? <Stars value={p.rating} count={p.ratingCount} /> : null}
          <PriceLine p={p} locale={locale} />
        </span>
      </Link>
    </article>
  );
}
