import Link from "next/link";
import type { ShopProduct } from "@/components/shop/product-card";
import type { Locale, Translate } from "@/lib/i18n";
import { Stars } from "../kit";
import { badgeOf, Pic, PriceLine } from "../parts";

/** Bold card: thick border, hard offset shadow that lifts on hover, a loud badge sticker, price in a coloured tab. */
export function BoldCard({ p, t, locale }: { p: ShopProduct; t: Translate; locale: Locale }) {
  const b = badgeOf(p, t);
  return (
    <article className="bd-card" data-sold={!p.inStock || undefined}>
      <Link href={`/products/${p.slug}`}>
        <span className="bd-card-pic"><Pic p={p} />{b && <span className="bd-sticker" data-kind={b.kind}>{b.text}</span>}{!p.inStock && <span className="bd-soldout">{t("shop.outOfStock")}</span>}</span>
        <span className="bd-card-body">
          <span className="bd-card-name">{p.name}</span>
          {p.rating ? <Stars value={p.rating} count={p.ratingCount} /> : null}
          <PriceLine p={p} locale={locale} />
        </span>
      </Link>
    </article>
  );
}
