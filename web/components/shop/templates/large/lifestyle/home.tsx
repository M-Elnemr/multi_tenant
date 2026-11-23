/* eslint-disable @next/next/no-img-element */
import Link from "next/link";
import { OfferStrip } from "@/components/shop/offer-strip";
import type { ShopProduct } from "@/components/shop/product-card";
import { buildTree } from "@/components/shop/types";
import { fileUrl } from "@/lib/media";
import { LifestyleCard } from "./card";
import type { HomeProps } from "../types";

/** Lifestyle home: campaign banners (one large, two smaller), "shop the collections" tiles, then product shelves each introduced by a short headline. */
export function LifestyleHome({ name, home, slides, cats, t, locale }: HomeProps) {
  const tops = buildTree(cats).filter((c) => c.productCount > 0).slice(0, 6);
  const lead = slides[0];
  const second = slides[1];
  const pool: ShopProduct[] = [...home.featured, ...home.bestSellers, ...home.newest].filter((x, i, a) => a.findIndex((y) => y.id === x.id) === i);
  const shelf = (title: string, sub: string, href: string, items: ShopProduct[], n = 8) => items.length === 0 ? null : (
    <section className="lf-wrap lf-sec"><div className="lf-sec-head"><div><p className="lf-eyebrow">{sub}</p><h2>{title}</h2></div><Link href={href} className="lf-btn lf-btn-soft">{t("shop.viewAll")}</Link></div><div className="lf-grid">{items.slice(0, n).map((p) => <LifestyleCard key={p.id} p={p} t={t} locale={locale} />)}</div></section>
  );
  return (
    <>
      <section className="lf-wrap lf-camp">
        <Link href={lead?.href ?? "/products"} className="lf-banner lf-banner-main" data-plain={!lead || undefined}>
          {lead && <img src={lead.imageUrl} alt="" />}
          <span className="lf-banner-copy"><small>{lead?.subtitle || t("shop.handpicked")}</small><b>{lead?.title || name}</b><i className="lf-btn">{t("shop.browse")}</i></span>
        </Link>
        <Link href={second?.href ?? "/products?onSale=true&sort=discount"} className="lf-banner lf-banner-a">
          {second && <img src={second.imageUrl} alt="" />}
          <span className="lf-banner-copy"><small>{t("shop.limitedTime")}</small><b>{second?.title || t("shop.offers")}</b></span>
        </Link>
        <Link href="/products?sort=newest" className="lf-banner lf-banner-b"><span className="lf-banner-copy"><small>{t("shop.justIn")}</small><b>{t("shop.newest")}</b></span></Link>
      </section>
      {(home.offerBanners?.length ?? 0) > 0 && <section className="lf-wrap lf-sec"><OfferStrip banners={home.offerBanners!} currency={pool[0]?.currency ?? "EGP"} /></section>}
      {tops.length > 0 && (
        <section className="lf-wrap lf-sec" aria-label={t("shop.categories")}>
          <div className="lf-sec-head"><div><p className="lf-eyebrow">{t("shop.shopBy")}</p><h2>{t("shop.categories")}</h2></div></div>
          <ul className="lf-tiles">{tops.map((c, i) => (
            <li key={c.id} data-tone={i % 6}><Link href={`/c/${c.slug}`}>{c.imageFileId && <img src={fileUrl(c.imageFileId, "medium")} alt="" />}<span><b>{c.name}</b><small>{c.productCount}</small></span></Link></li>))}</ul>
        </section>
      )}
      {shelf(t("shop.bestSellers"), t("shop.loved"), "/products?sort=popular", home.bestSellers.length ? home.bestSellers : home.featured, 4)}
      {shelf(t("shop.offers"), t("shop.limitedTime"), "/products?onSale=true&sort=discount", home.offers, 4)}
      {shelf(t("shop.newest"), t("shop.justIn"), "/products?sort=newest", home.newest, 8)}
      {pool.length === 0 && <p className="lf-wrap lf-none">{t("shop.empty")}</p>}
    </>
  );
}
