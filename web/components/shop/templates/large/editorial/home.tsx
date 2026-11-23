/* eslint-disable @next/next/no-img-element */
import Link from "next/link";
import { OfferStrip } from "@/components/shop/offer-strip";
import type { ShopProduct } from "@/components/shop/product-card";
import { buildTree } from "@/components/shop/types";
import { EditorialCard } from "./card";
import type { HomeProps } from "../types";

/** Editorial home: one full-bleed statement, numbered category chapters set as large serif lines, a single horizontal edit of tall pictures, a closing note. */
export function EditorialHome({ name, home, slides, cats, t, locale }: HomeProps) {
  const tops = buildTree(cats).filter((c) => c.productCount > 0).slice(0, 8);
  const edit = (home.featured.length ? home.featured : home.bestSellers.length ? home.bestSellers : home.newest);
  const rest: ShopProduct[] = [...home.newest, ...home.bestSellers].filter((x, i, a) => a.findIndex((y) => y.id === x.id) === i && !edit.some((e) => e.id === x.id));
  const lead = slides[0];
  return (
    <>
      {(home.offerBanners?.length ?? 0) > 0 && <section className="ed-wrap ed-gap"><OfferStrip banners={home.offerBanners!} currency={edit[0]?.currency ?? "EGP"} /></section>}
      <section className="ed-hero">
        {lead && <img src={lead.imageUrl} alt="" />}
        <div className="ed-hero-text">
          <p className="ed-eyebrow">{lead?.subtitle || t("shop.handpicked")}</p>
          <h1>{lead?.title || name}</h1>
          <Link href={lead?.href ?? "/products"} className="ed-link">{t("shop.browse")}</Link>
        </div>
      </section>
      {tops.length > 0 && (
        <section className="ed-wrap ed-chapters" aria-label={t("shop.categories")}>
          <p className="ed-eyebrow">{t("shop.shopBy")}</p>
          <ol>{tops.map((c, i) => <li key={c.id}><Link href={`/c/${c.slug}`}><i>{String(i + 1).padStart(2, "0")}</i><span>{c.name}</span><small>{c.productCount}</small></Link></li>)}</ol>
        </section>
      )}
      {edit.length > 0 && (
        <section className="ed-edit">
          <div className="ed-wrap ed-head2"><h2>{t("shop.featured")}</h2><Link href="/products" className="ed-link">{t("shop.viewAll")}</Link></div>
          <div className="ed-rail">{edit.slice(0, 10).map((p) => <EditorialCard key={p.id} p={p} t={t} locale={locale} tall />)}</div>
        </section>
      )}
      {home.offers.length > 0 && (
        <section className="ed-wrap ed-gap">
          <div className="ed-head2"><h2>{t("shop.offers")}</h2><Link href="/products?onSale=true&sort=discount" className="ed-link">{t("shop.viewAll")}</Link></div>
          <div className="ed-grid">{home.offers.slice(0, 6).map((p) => <EditorialCard key={p.id} p={p} t={t} locale={locale} />)}</div>
        </section>
      )}
      {rest.length > 0 && (
        <section className="ed-wrap ed-gap">
          <div className="ed-head2"><h2>{t("shop.newest")}</h2><Link href="/products?sort=newest" className="ed-link">{t("shop.viewAll")}</Link></div>
          <div className="ed-grid">{rest.slice(0, 6).map((p) => <EditorialCard key={p.id} p={p} t={t} locale={locale} />)}</div>
        </section>
      )}
      {edit.length === 0 && rest.length === 0 && <p className="ed-wrap ed-empty">{t("shop.empty")}</p>}
    </>
  );
}
