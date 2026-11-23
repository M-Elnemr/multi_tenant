import Link from "next/link";
import { OfferStrip } from "@/components/shop/offer-strip";
import type { ShopProduct } from "@/components/shop/product-card";
import { buildTree } from "@/components/shop/types";
import { MinimalCard } from "./card";
import type { HomeProps } from "../types";

/** Minimal home: no hero picture. One paragraph of words, a plain list of departments, then the newest as an index of lines and a short grid of favourites. */
export function MinimalHome({ p, name, home, cats, t, locale }: HomeProps) {
  const tops = buildTree(cats).filter((c) => c.productCount > 0);
  const fav: ShopProduct[] = (home.featured.length ? home.featured : home.bestSellers).slice(0, 8);
  const fresh: ShopProduct[] = home.newest.filter((x) => !fav.some((f) => f.id === x.id)).slice(0, 6);
  const line = (title: string, href: string) => <div className="mn-sec-head"><h2>{title}</h2><Link href={href}>{t("shop.viewAll")}</Link></div>;
  return (
    <>
      <section className="mn-wrap mn-intro">
        <h1>{p?.shortDescription || p?.about?.slice(0, 160) || name}</h1>
        <Link href="/products" className="mn-under">{t("shop.browse")}</Link>
      </section>
      {(home.offerBanners?.length ?? 0) > 0 && <section className="mn-wrap mn-sec"><OfferStrip banners={home.offerBanners!} currency={fav[0]?.currency ?? "EGP"} /></section>}
      {tops.length > 0 && (
        <section className="mn-wrap mn-sec" aria-label={t("shop.categories")}>
          <ul className="mn-depts">{tops.map((c) => <li key={c.id}><Link href={`/c/${c.slug}`}><span>{c.name}</span><small>{c.productCount}</small></Link></li>)}</ul>
        </section>
      )}
      {fav.length > 0 && <section className="mn-wrap mn-sec">{line(t("shop.featured"), "/products")}<div className="mn-grid">{fav.map((x) => <MinimalCard key={x.id} p={x} t={t} locale={locale} />)}</div></section>}
      {fresh.length > 0 && <section className="mn-wrap mn-sec">{line(t("shop.newest"), "/products?sort=newest")}<div className="mn-index">{fresh.map((x) => <MinimalCard key={x.id} p={x} t={t} locale={locale} row />)}</div></section>}
      {home.offers.length > 0 && <section className="mn-wrap mn-sec">{line(t("shop.offers"), "/products?onSale=true&sort=discount")}<div className="mn-grid">{home.offers.slice(0, 4).map((x) => <MinimalCard key={x.id} p={x} t={t} locale={locale} />)}</div></section>}
      {fav.length === 0 && fresh.length === 0 && <p className="mn-wrap mn-none">{t("shop.empty")}</p>}
    </>
  );
}
