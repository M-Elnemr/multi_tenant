import Link from "next/link";
import { money } from "@/lib/format";
import { Stars } from "../kit";
import { MarketCard } from "./card";
import { MarketBuy } from "./buy";
import type { PdpProps } from "../types";

export function MarketPdp({ product, related, together, profile, crumbs, t, locale }: PdpProps) {
  return (
    <div className="lg-wrap mk-page">
      <nav className="mk-crumbs" aria-label="breadcrumb">
        <Link href="/">{t("shop.home")}</Link><span aria-hidden>/</span><Link href="/products">{t("shop.allProducts")}</Link>
        {crumbs.map((c) => <span key={c.slug} className="mk-crumb"><span aria-hidden>/</span><Link href={`/c/${c.slug}`}>{c.name}</Link></span>)}
      </nav>
      <MarketBuy product={product} whatsapp={profile?.whatsapp} storeName={profile?.storeName ?? ""} isOpen={profile?.isOpen !== false} returnDays={profile?.returnWindowDays ?? 14} />
      {together.length > 0 && (
        <section className="mk-sec">
          <h2 className="mk-h2">{t("product.together")}</h2>
          <ul className="mk-together">{together.map((x) => <li key={x.id}><Link href={`/products/${x.slug}`}><span>{x.name}</span><b>{money(x.priceMinor, x.currency, locale)}</b></Link></li>)}</ul>
        </section>
      )}
      <section className="mk-sec" id="mk-reviews">
        <h2 className="mk-h2">{t("shop.reviews")} <small>({product.reviews.count})</small></h2>
        {product.reviews.count === 0 ? <p className="mk-muted">{t("shop.noReviews")}</p> : (
          <ul className="mk-reviews">{product.reviews.reviews.map((r, i) => (
            <li key={i}><p><b>{r.firstName}</b><Stars value={r.rating} /></p>{r.reviewText && <p>{r.reviewText}</p>}</li>))}</ul>
        )}
      </section>
      {related.length > 0 && (
        <section className="mk-sec">
          <h2 className="mk-h2">{t("product.related")}</h2>
          <div className="mk-grid">{related.slice(0, 5).map((r) => <MarketCard key={r.id} p={r} t={t} locale={locale} />)}</div>
        </section>
      )}
    </div>
  );
}
