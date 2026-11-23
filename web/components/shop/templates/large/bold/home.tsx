/* eslint-disable @next/next/no-img-element */
import Link from "next/link";
import { OfferStrip } from "@/components/shop/offer-strip";
import type { ShopProduct } from "@/components/shop/product-card";
import { buildTree } from "@/components/shop/types";
import { BoldCard } from "./card";
import type { HomeProps } from "../types";

const TONES = ["a", "b", "c", "d"];

/** Bold home: a huge headline block with the banner as a framed picture, chunky coloured category blocks, a marquee band, then framed product grids. */
export function BoldHome({ name, home, slides, cats, t, locale }: HomeProps) {
  const tops = buildTree(cats).filter((c) => c.productCount > 0).slice(0, 8);
  const lead = slides[0];
  const hot: ShopProduct[] = home.offers.length ? home.offers : home.featured;
  const best = home.bestSellers.length ? home.bestSellers : home.featured;
  const fresh = home.newest;
  const sec = (title: string, href: string, items: ShopProduct[], n = 8) => items.length === 0 ? null : (
    <section className="bd-wrap bd-sec"><div className="bd-sec-head"><h2>{title}</h2><Link href={href} className="bd-pill">{t("shop.viewAll")}</Link></div><div className="bd-grid">{items.slice(0, n).map((p) => <BoldCard key={p.id} p={p} t={t} locale={locale} />)}</div></section>
  );
  return (
    <>
      <section className="bd-wrap bd-hero">
        <div className="bd-hero-copy">
          <p className="bd-kicker">{lead?.subtitle || t("shop.handpicked")}</p>
          <h1>{lead?.title || name}</h1>
          <Link href={lead?.href ?? "/products"} className="bd-btn">{t("shop.browse")}</Link>
        </div>
        <div className="bd-hero-pic">{lead ? <img src={lead.imageUrl} alt="" /> : <span aria-hidden>{name.trim().charAt(0)}</span>}</div>
      </section>
      {(home.offerBanners?.length ?? 0) > 0 && <section className="bd-wrap"><OfferStrip banners={home.offerBanners!} currency={hot[0]?.currency ?? "EGP"} /></section>}
      {tops.length > 0 && (
        <section className="bd-wrap bd-sec" aria-label={t("shop.categories")}>
          <div className="bd-sec-head"><h2>{t("shop.categories")}</h2></div>
          <ul className="bd-blocks">{tops.map((c, i) => <li key={c.id} data-tone={TONES[i % 4]}><Link href={`/c/${c.slug}`}><span>{c.name}</span><small>{c.productCount}</small></Link></li>)}</ul>
        </section>
      )}
      <div className="bd-band" aria-hidden><div className="bd-band-track">{[0, 1, 2, 3, 4, 5].map((i) => <span key={i}>{t("shop.newest")} ✦ {t("shop.offers")} ✦ {t("shop.bestSellers")} ✦</span>)}</div></div>
      {sec(t("shop.offers"), "/products?onSale=true&sort=discount", hot)}
      {sec(t("shop.bestSellers"), "/products?sort=popular", best, 4)}
      {sec(t("shop.newest"), "/products?sort=newest", fresh, 8)}
      {hot.length === 0 && best.length === 0 && fresh.length === 0 && <p className="bd-wrap bd-none">{t("shop.empty")}</p>}
    </>
  );
}
