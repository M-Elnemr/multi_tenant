import Link from "next/link";
import { OpenHero, type HeroSlide } from "@/components/open/hero";
import { OpenProductCard } from "@/components/open/product-card";
import { CategoryTiles, ProductRail, ShopCard } from "@/components/open/sections";
import { Icon } from "@/components/icons";
import { getT } from "@/lib/i18n-server";
import { num, openJson, pickName, type OpenHome } from "@/lib/open";

export const dynamic = "force-dynamic";

export default async function OpenHomePage() {
  const { t, locale } = await getT();
  const home = await openJson<OpenHome>("/home");
  const topDeal = home.deals[0];
  const maxOff = Math.max(0, ...home.deals.map((d) => Math.round(num(d.discountPct))));
  const rows = home.categoryRows;

  const slides: HeroSlide[] = [
    { id: "welcome", eyebrow: t("open.hero.eyebrow"), title: t("open.hero.title"), sub: t("open.hero.sub", { shops: home.stats.shops, products: home.stats.products }), cta: t("open.hero.cta"), href: "/search", from: "#0d1535", to: "#2b3bd6", image: home.featured[0]?.image?.medium ?? home.trending[0]?.image?.medium ?? null, emoji: "🛍️" },
  ];
  if (topDeal) slides.push({ id: "deals", eyebrow: t("open.deals"), title: maxOff > 0 ? t("open.hero.dealsTitle", { pct: maxOff }) : t("open.deals"), sub: t("open.hero.dealsSub"), cta: t("open.hero.dealsCta"), href: "/search?onSale=true&sort=discount", from: "#c2210a", to: "#ff7a2f", image: topDeal.image?.medium ?? null, emoji: "🔥" });
  rows.slice(0, 2).forEach((r, i) => slides.push({ id: `cat-${r.slug}`, eyebrow: t("open.hero.catEyebrow"), title: pickName(r, locale), sub: t("open.hero.catSub", { n: r.n }), cta: t("open.hero.catCta"), href: `/c/${r.slug}`, from: i ? "#0b6b5c" : "#5b21b6", to: i ? "#18a58c" : "#c026d3", image: r.products[0]?.image?.medium ?? null, emoji: r.icon ?? "🛍️" }));

  return (
    <>
      <OpenHero slides={slides} />
      <CategoryTiles categories={home.categories} locale={locale} />

      {home.deals.length > 0 && (
        <section className="o-container mt-8 md:mt-12">
          <div className="o-card overflow-hidden !border-0 bg-gradient-to-br from-[#ffefe8] via-white to-[#fff6dc] p-4 md:p-6">
            <div className="mb-3 flex items-end justify-between gap-3">
              <div>
                <p className="text-xs font-extrabold uppercase tracking-[.14em] text-[var(--o-accent)]">🔥 {t("open.deals")}</p>
                <h2 className="o-title">{t("open.dealsTitle")}</h2>
              </div>
              <Link href="/search?onSale=true&sort=discount" className="o-link flex items-center gap-1">{t("open.seeAll")}<Icon name="arrow" className="h-4 w-4 rtl:rotate-180" /></Link>
            </div>
            <div className="o-rail">{home.deals.map((p) => <div key={p.id} className="w-[10.5rem] sm:w-[12.5rem] lg:w-[14rem]"><OpenProductCard p={p} /></div>)}</div>
          </div>
        </section>
      )}

      <ProductRail eyebrow={t("open.trending")} title={t("open.trendingTitle")} href="/search?sort=popular" linkLabel={t("open.seeAll")} products={home.trending} />

      {home.shops.length > 0 && (
        <section className="o-container mt-8 md:mt-12">
          <div className="mb-3 flex items-end justify-between gap-3">
            <div><p className="text-xs font-extrabold uppercase tracking-[.14em] text-[var(--o-accent)]">{t("open.shopsEyebrow")}</p><h2 className="o-title">{t("open.shopsTitle")}</h2></div>
            <Link href="/shops" className="o-link flex items-center gap-1">{t("open.allShops")}<Icon name="arrow" className="h-4 w-4 rtl:rotate-180" /></Link>
          </div>
          <div className="o-rail">{home.shops.map((s) => <div key={s.id} className="w-[14rem] md:w-[16rem]"><ShopCard s={s} locale={locale} productsLabel={t("open.productsCount")} /></div>)}</div>
        </section>
      )}

      <ProductRail eyebrow={t("open.new")} title={t("open.newTitle")} href="/search?sort=newest" linkLabel={t("open.seeAll")} products={home.newArrivals} />

      {rows.map((r) => (
        <ProductRail key={r.slug} eyebrow={r.icon ?? undefined} title={pickName(r, locale)} href={`/c/${r.slug}`} linkLabel={t("open.seeAll")} products={r.products} />
      ))}

      <ProductRail eyebrow={t("open.topRated")} title={t("open.topRatedTitle")} href="/search?sort=rating" linkLabel={t("open.seeAll")} products={home.topRated} />

      {home.stats.products === 0 && (
        <div className="o-container mt-16 text-center"><p className="text-5xl">🏬</p><h2 className="o-title mt-3">{t("open.empty.title")}</h2><p className="o-mute mt-1">{t("open.empty.body")}</p></div>
      )}
    </>
  );
}
