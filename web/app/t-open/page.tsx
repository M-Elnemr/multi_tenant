import Link from "next/link";
import { OpenHero } from "@/components/open/hero";
import { OpenProductCard } from "@/components/open/product-card";
import { Rail } from "@/components/open/rail";
import { CategoryTiles, ProductRail, SectionHead, ShopCard } from "@/components/open/sections";
import { Icon, type IconName } from "@/components/icons";
import { getT } from "@/lib/i18n-server";
import { num, openJson, pickName, type OpenHome } from "@/lib/open";

export const dynamic = "force-dynamic";

export default async function OpenHomePage() {
  const { t, locale } = await getT();
  const home = await openJson<OpenHome>("/home");
  const maxOff = Math.max(0, ...home.deals.map((d) => Math.round(num(d.discountPct))));
  const rows = home.categoryRows;
  const pool = home.featured.length >= 3 ? home.featured : [...home.featured, ...home.trending];
  const tiles = pool.slice(0, 3).map((p) => ({ src: p.image?.medium ?? null, name: p.name, href: `/p/${p.shop.slug}/${p.slug}` }));
  const pillars: { icon: IconName; title: string; sub: string }[] = [
    { icon: "wallet", title: t("open.trust.cod"), sub: t("open.trust.codSub") },
    { icon: "bag", title: t("open.trust.shops"), sub: t("open.trust.shopsSub") },
    { icon: "navigate", title: t("open.trust.track"), sub: t("open.trust.trackSub") },
  ];

  return (
    <>
      <OpenHero eyebrow={t("open.hero.eyebrow")} title={t("open.hero.title")} sub={t("open.hero.sub", { shops: home.stats.shops, products: home.stats.products })} cta={t("open.hero.cta")} href="/search" secondary={{ label: t("open.allShops"), href: "/shops" }} tiles={tiles} />

      <ul className="o-container mt-5 grid gap-3 sm:grid-cols-3">
        {pillars.map((p) => (
          <li key={p.title} className="flex items-center gap-3 rounded-2xl px-1 py-1.5">
            <span className="grid h-11 w-11 shrink-0 place-items-center rounded-full border border-[var(--o-line-2)] text-[var(--o-accent)]"><Icon name={p.icon} className="h-5 w-5" /></span>
            <span className="min-w-0"><b className="block text-sm">{p.title}</b><span className="block text-xs text-[var(--o-mute)]">{p.sub}</span></span>
          </li>
        ))}
      </ul>

      <CategoryTiles categories={home.categories} locale={locale} />

      {home.deals.length > 0 && (
        <section className="o-section">
          <div className="bg-[var(--o-soft)] py-8 md:py-12">
            <div className="o-container">
              <SectionHead eyebrow={maxOff > 0 ? t("open.hero.dealsTitle", { pct: maxOff }) : t("open.deals")} title={t("open.dealsTitle")} href="/search?onSale=true&sort=discount" linkLabel={t("open.seeAll")} />
              <Rail>{home.deals.map((p) => <div key={p.id} className="w-[10.75rem] sm:w-[13rem] lg:w-[14.5rem]"><OpenProductCard p={p} /></div>)}</Rail>
            </div>
          </div>
        </section>
      )}

      <ProductRail eyebrow={t("open.trending")} title={t("open.trendingTitle")} href="/search?sort=popular" linkLabel={t("open.seeAll")} products={home.trending} />

      {home.shops.length > 0 && (
        <section className="o-container o-section">
          <SectionHead eyebrow={t("open.shopsEyebrow")} title={t("open.shopsTitle")} href="/shops" linkLabel={t("open.allShops")} />
          <Rail>{home.shops.map((s) => <div key={s.id} className="w-[15rem] md:w-[17rem]"><ShopCard s={s} locale={locale} productsLabel={t("open.productsCount")} /></div>)}</Rail>
        </section>
      )}

      <ProductRail eyebrow={t("open.new")} title={t("open.newTitle")} href="/search?sort=newest" linkLabel={t("open.seeAll")} products={home.newArrivals} />

      {rows.map((r) => (
        <ProductRail key={r.slug} eyebrow={t("open.hero.catEyebrow")} title={pickName(r, locale)} href={`/c/${r.slug}`} linkLabel={t("open.seeAll")} products={r.products} />
      ))}

      <ProductRail eyebrow={t("open.topRated")} title={t("open.topRatedTitle")} href="/search?sort=rating" linkLabel={t("open.seeAll")} products={home.topRated} />

      {home.stats.products === 0 && (
        <div className="o-container o-section text-center">
          <span className="mx-auto grid h-16 w-16 place-items-center rounded-full border border-[var(--o-line-2)] text-[var(--o-mute)]"><Icon name="bag" className="h-7 w-7" /></span>
          <h2 className="o-title o-display mt-4">{t("open.empty.title")}</h2>
          <p className="o-mute mx-auto mt-1 max-w-sm">{t("open.empty.body")}</p>
          <Link href="/shops" className="o-btn o-btn-ghost mt-5">{t("open.allShops")}</Link>
        </div>
      )}
    </>
  );
}
