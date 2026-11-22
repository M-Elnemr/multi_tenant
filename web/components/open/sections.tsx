import Link from "next/link";
import { pickName, type OpenCategory, type OpenProduct, type OpenShop } from "@/lib/open";
import { OpenProductCard } from "./product-card";
import { Monogram } from "./media";
import { Rail } from "./rail";
import { Icon } from "../icons";
import type { Locale } from "@/lib/i18n";

export function SectionHead({ eyebrow, title, href, linkLabel }: { eyebrow?: string; title: string; href?: string; linkLabel?: string }) {
  return (
    <div className="mb-4 flex items-end justify-between gap-4 md:mb-6">
      <div className="min-w-0">
        {eyebrow && <p className="o-eyebrow mb-1.5">{eyebrow}</p>}
        <h2 className="o-title o-display">{title}</h2>
      </div>
      {href && <Link href={href} className="o-link -my-3 flex min-h-11 shrink-0 items-center gap-1.5">{linkLabel}<Icon name="arrow" className="h-4 w-4 rtl:rotate-180" /></Link>}
    </div>
  );
}

/** A titled horizontal row of products (swipe on phones, arrows on desktop). */
export function ProductRail({ title, href, linkLabel, products, eyebrow }: { title: string; href?: string; linkLabel?: string; products: OpenProduct[]; eyebrow?: string }) {
  if (products.length === 0) return null;
  return (
    <section className="o-container o-section">
      <SectionHead eyebrow={eyebrow} title={title} href={href} linkLabel={linkLabel} />
      <Rail>
        {products.map((p) => <div key={p.id} className="w-[10.75rem] sm:w-[13rem] lg:w-[14.5rem]"><OpenProductCard p={p} /></div>)}
      </Rail>
    </section>
  );
}

/** Top-level categories as a scrollable row of round tiles. The icon is the category's own (it comes from the data). */
export function CategoryTiles({ categories, locale }: { categories: OpenCategory[]; locale: Locale }) {
  if (categories.length === 0) return null;
  return (
    <div className="o-container mt-8 md:mt-12">
      <Rail>
        {categories.map((c) => (
          <Link key={c.slug} href={`/c/${c.slug}`} className="group flex w-[5.5rem] flex-col items-center gap-2.5 text-center md:w-[7.25rem]">
            <span className="grid h-[4.5rem] w-[4.5rem] place-items-center rounded-full border border-[var(--o-line)] bg-[var(--o-paper)] text-[1.7rem] shadow-sm transition duration-300 group-hover:-translate-y-1 group-hover:border-[var(--o-ink)] group-hover:shadow-[var(--o-shadow)] md:h-24 md:w-24 md:text-4xl">{c.icon || <Icon name="bag" className="h-7 w-7 text-[var(--o-mute)]" />}</span>
            <span className="line-clamp-2 text-xs font-semibold leading-tight md:text-[13.5px]">{pickName(c, locale)}</span>
          </Link>
        ))}
      </Rail>
    </div>
  );
}

const safeColor = (c?: string | null) => (c && /^#[0-9a-fA-F]{3,8}$/.test(c) ? c : "#3a342b");

/** A merchant tile: the shop's own colour as a quiet cover, its logo (or initial), rating and size. */
export function ShopCard({ s, productsLabel }: { s: OpenShop; locale?: Locale; productsLabel: string }) {
  const color = safeColor(s.primaryColor);
  const rating = Number(s.rating ?? 0);
  return (
    <Link href={`/shop/${s.slug}`} className="o-card group block h-full overflow-hidden transition duration-300 hover:-translate-y-1 hover:shadow-[var(--o-shadow-lg)]">
      <div className="relative h-20" style={{ background: `linear-gradient(135deg, ${color}, color-mix(in srgb, ${color} 60%, #17130e))` }}>
        <span className="absolute inset-0 opacity-20" style={{ backgroundImage: "radial-gradient(circle at 20% 20%, #fff 0, transparent 40%)" }} />
      </div>
      <div className="relative px-4 pb-4">
        <span className="o-frame -mt-8 block h-16 w-16 rounded-2xl border-[3px] border-[var(--o-paper)] shadow-md">
          {s.logoUrl ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={s.logoUrl} alt="" width={64} height={64} className="h-full w-full object-cover" loading="lazy" />
          ) : <Monogram name={s.name} />}
        </span>
        <p className="mt-3 line-clamp-1 text-[15px] font-bold">{s.name}</p>
        <p className="mt-1 flex items-center gap-2 text-xs text-[var(--o-mute)]">
          {rating > 0 && <span className="flex items-center gap-1 font-bold text-[var(--o-ink)]"><Icon name="star" className="o-stars h-3.5 w-3.5 fill-current" /><span className="o-num">{rating.toFixed(1)}</span></span>}
          <span className="o-num">{s.productCount} {productsLabel}</span>
        </p>
        {s.shortDescription && <p className="mt-2 line-clamp-2 text-xs leading-relaxed text-[var(--o-mute)]">{s.shortDescription}</p>}
        {s.isOpen === false && <span className="o-badge mt-3" style={{ background: "var(--o-faint)" }}>{s.closedMessage || "—"}</span>}
      </div>
    </Link>
  );
}
