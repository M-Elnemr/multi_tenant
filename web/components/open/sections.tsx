import Link from "next/link";
import { pickName, type OpenCategory, type OpenProduct, type OpenShop } from "@/lib/open";
import { OpenProductCard } from "./product-card";
import { Icon } from "../icons";
import type { Locale } from "@/lib/i18n";

/** A titled horizontal row of products (swipe on phones, scroll on desktop). */
export function ProductRail({ title, href, linkLabel, products, eyebrow }: { title: string; href?: string; linkLabel?: string; products: OpenProduct[]; eyebrow?: string }) {
  if (products.length === 0) return null;
  return (
    <section className="o-container mt-8 md:mt-12">
      <div className="mb-3 flex items-end justify-between gap-3">
        <div>
          {eyebrow && <p className="text-xs font-extrabold uppercase tracking-[.14em] text-[var(--o-accent)]">{eyebrow}</p>}
          <h2 className="o-title">{title}</h2>
        </div>
        {href && <Link href={href} className="o-link flex shrink-0 items-center gap-1">{linkLabel}<Icon name="arrow" className="h-4 w-4 rtl:rotate-180" /></Link>}
      </div>
      <div className="o-rail">
        {products.map((p) => <div key={p.id} className="w-[10.5rem] sm:w-[12.5rem] lg:w-[14rem]"><OpenProductCard p={p} /></div>)}
      </div>
    </section>
  );
}

const TILE_TINTS = ["#fff1ea", "#eaf1ff", "#eafaf2", "#fff7e0", "#f4ecff", "#e9f8fb", "#ffeef3", "#eef7e6"];

export function CategoryTiles({ categories, locale }: { categories: OpenCategory[]; locale: Locale }) {
  if (categories.length === 0) return null;
  return (
    <div className="o-container mt-6 md:mt-10">
      <div className="o-rail">
        {categories.map((c, i) => (
          <Link key={c.slug} href={`/c/${c.slug}`} className="group flex w-[5.6rem] flex-col items-center gap-2 text-center md:w-[7.2rem]">
            <span className="grid h-[4.6rem] w-[4.6rem] place-items-center rounded-[1.6rem] text-3xl shadow-sm transition duration-300 group-hover:-translate-y-1 group-hover:shadow-lg md:h-24 md:w-24 md:text-4xl" style={{ background: TILE_TINTS[i % TILE_TINTS.length] }}>{c.icon || "🛍️"}</span>
            <span className="line-clamp-2 text-xs font-bold leading-tight md:text-[13px]">{pickName(c, locale)}</span>
          </Link>
        ))}
      </div>
    </div>
  );
}

export function ShopCard({ s, productsLabel }: { s: OpenShop; locale?: Locale; productsLabel: string }) {
  const color = s.primaryColor && /^#[0-9a-fA-F]{3,8}$/.test(s.primaryColor) ? s.primaryColor : "#18266b";
  const rating = Number(s.rating ?? 0);
  return (
    <Link href={`/shop/${s.slug}`} className="o-card group block overflow-hidden transition duration-300 hover:-translate-y-1 hover:shadow-[var(--o-shadow-lg)]">
      <div className="relative h-16" style={{ background: `linear-gradient(135deg, ${color}, color-mix(in srgb, ${color} 55%, #0d1535))` }} />
      <div className="relative px-4 pb-4">
        <span className="-mt-8 grid h-16 w-16 place-items-center overflow-hidden rounded-2xl border-4 border-white bg-white text-2xl font-black shadow-md" style={{ color }}>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          {s.logoUrl ? <img src={s.logoUrl} alt="" width={64} height={64} className="h-full w-full object-cover" loading="lazy" /> : s.name.slice(0, 1)}
        </span>
        <p className="mt-2 line-clamp-1 font-extrabold">{s.name}</p>
        <p className="mt-0.5 flex items-center gap-2 text-xs text-[var(--o-mute)]">
          {rating > 0 && <span className="flex items-center gap-0.5 font-bold text-[var(--o-ink)]"><span className="o-stars">★</span>{rating.toFixed(1)}</span>}
          <span>{s.productCount} {productsLabel}</span>
        </p>
        {s.shortDescription && <p className="mt-2 line-clamp-2 text-xs text-[var(--o-mute)]">{s.shortDescription}</p>}
        {s.isOpen === false && <span className="o-badge mt-2" style={{ background: "#98a2b3" }}>{s.closedMessage || "—"}</span>}
      </div>
    </Link>
  );
}
