/* eslint-disable @next/next/no-img-element */
import Link from "next/link";
import { Icon } from "@/components/icons";
import { OfferStrip } from "@/components/shop/offer-strip";
import { buildTree } from "@/components/shop/types";
import type { ShopProduct } from "@/components/shop/product-card";
import { MarketCard } from "./card";
import type { HomeProps } from "../types";

/** Marketplace home: a banner + side promos, department tiles, then dense product shelves. Empty shelves are skipped. */
export function MarketHome({ home, slides, cats, t, locale }: HomeProps) {
  const tops = buildTree(cats).filter((c) => c.productCount > 0).slice(0, 12);
  const all = [...home.featured, ...home.bestSellers, ...home.newest].filter((x, i, a) => a.findIndex((y) => y.id === x.id) === i);
  const lead = slides[0];
  const shelf = (title: string, href: string, items: ShopProduct[], rail = false) => items.length === 0 ? null : (
    <section className="lg-wrap mk-sec">
      <div className="mk-sec-head"><h2>{title}</h2><Link href={href}>{t("shop.viewAll")}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Link></div>
      <div className={rail ? "mk-rail" : "mk-grid"}>{items.slice(0, rail ? 12 : 10).map((p) => <MarketCard key={p.id} p={p} t={t} locale={locale} />)}</div>
    </section>
  );
  return (
    <>
      {(home.offerBanners?.length ?? 0) > 0 && <section className="lg-wrap mk-sec"><OfferStrip banners={home.offerBanners!} currency={all[0]?.currency ?? "EGP"} /></section>}
      <section className="lg-wrap mk-hero">
        <div className="mk-hero-main">
          {lead ? (
            <Link href={lead.href ?? "/products"} className="mk-hero-slide">
              <img src={lead.imageUrl} alt="" />
              {(lead.title || lead.subtitle) && <span className="mk-hero-text"><b>{lead.title}</b><span>{lead.subtitle}</span></span>}
            </Link>
          ) : (
            <Link href="/products" className="mk-hero-slide mk-hero-plain"><span className="mk-hero-text"><b>{t("shop.allProducts")}</b><span>{t("shop.handpicked")}</span></span></Link>
          )}
        </div>
        <div className="mk-hero-side">
          <Link href="/products?onSale=true&sort=discount" className="mk-promo" data-k="sale"><Icon name="tag" className="h-6 w-6" /><b>{t("shop.offers")}</b><span>{t("shop.limitedTime")}</span></Link>
          <Link href="/products?sort=newest" className="mk-promo" data-k="new"><Icon name="sparkles" className="h-6 w-6" /><b>{t("shop.newest")}</b><span>{t("shop.justIn")}</span></Link>
        </div>
      </section>
      <ul className="lg-wrap mk-trust">
        {[["truck", t("trust.delivery")], ["shield", t("trust.cod")], ["return",t("trust.returns")], ["chat", t("trust.support")]].map(([ic, label]) => <li key={label}><Icon name={ic as "truck"} className="h-5 w-5" />{label}</li>)}
      </ul>
      {tops.length > 0 && (
        <section className="lg-wrap mk-sec">
          <div className="mk-sec-head"><h2>{t("shop.categories")}</h2><Link href="/products">{t("shop.allProducts")}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Link></div>
          <ul className="mk-tiles">{tops.map((c) => (
            <li key={c.id}><Link href={`/c/${c.slug}`} className="mk-tile"><span className="mk-tile-ico"><Icon name="grid" className="h-6 w-6" /></span><span>{c.name}</span><small>{c.productCount}</small></Link></li>))}</ul>
        </section>
      )}
      {shelf(t("shop.offers"), "/products?onSale=true&sort=discount", home.offers)}
      {shelf(t("shop.bestSellers"), "/products?sort=popular", home.bestSellers, true)}
      {shelf(t("shop.featured"), "/products", home.featured)}
      {shelf(t("shop.newest"), "/products?sort=newest", home.newest)}
      {all.length === 0 && <p className="lg-wrap lg-empty"><span>{t("shop.empty")}</span></p>}
    </>
  );
}
