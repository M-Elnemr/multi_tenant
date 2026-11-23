import Link from "next/link";
import type { ShopProduct } from "@/components/shop/product-card";
import type { Locale, Translate } from "@/lib/i18n";
import { Stars } from "../kit";
import { badgeOf, Pic, PriceLine } from "../parts";

/** Lifestyle card: a softly rounded picture with a round badge, brand line, name and price below. */
export function LifestyleCard({ p, t, locale }: { p: ShopProduct; t: Translate; locale: Locale }) {
  const b = badgeOf(p, t);
  return (
    <article className="lf-card" data-sold={!p.inStock || undefined}>
      <Link href={`/products/${p.slug}`}>
        <span className="lf-card-pic"><Pic p={p} />{b && <span className="lf-dot" data-kind={b.kind}>{b.text}</span>}{!p.inStock && <span className="lf-sold">{t("shop.outOfStock")}</span>}</span>
        <span className="lf-card-body">
          {p.brand && <span className="lf-card-brand">{p.brand}</span>}
          <span className="lf-card-name">{p.name}</span>
          {p.rating ? <Stars value={p.rating} count={p.ratingCount} /> : null}
          <PriceLine p={p} locale={locale} />
        </span>
      </Link>
    </article>
  );
}
