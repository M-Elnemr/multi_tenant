import Link from "next/link";
import type { ShopProduct } from "@/components/shop/product-card";
import type { Locale, Translate } from "@/lib/i18n";
import { badgeOf, Pic, PriceLine } from "../parts";

/** Editorial card: a tall picture with no frame, then a quiet caption (name, price). Sold-out is a small word under the price. */
export function EditorialCard({ p, t, locale, tall }: { p: ShopProduct; t: Translate; locale: Locale; tall?: boolean }) {
  const b = badgeOf(p, t);
  return (
    <article className="ed-card" data-tall={tall || undefined} data-sold={!p.inStock || undefined}>
      <Link href={`/products/${p.slug}`}>
        <span className="ed-card-pic"><Pic p={p} />{b && <span className="ed-card-tag">{b.text}</span>}</span>
        <span className="ed-card-cap">
          <span className="ed-card-name">{p.name}</span>
          <PriceLine p={p} locale={locale} />
          {!p.inStock && <span className="ed-card-sold">{t("shop.outOfStock")}</span>}
        </span>
      </Link>
    </article>
  );
}
