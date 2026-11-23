import Link from "next/link";
import { money } from "@/lib/format";
import { Stars } from "../kit";
import { LifestyleBuy } from "./buy";
import { LifestyleCard } from "./card";
import type { PdpProps } from "../types";

export function LifestylePdp({ product, related, together, profile, crumbs, t, locale }: PdpProps) {
  return (
    <div className="lf-wrap lf-page">
      <nav className="lf-crumbs" aria-label="breadcrumb"><Link href="/">{t("shop.home")}</Link>{crumbs.map((c) => <Link key={c.slug} href={`/c/${c.slug}`}>{c.name}</Link>)}</nav>
      <LifestyleBuy product={product} whatsapp={profile?.whatsapp} storeName={profile?.storeName ?? ""} isOpen={profile?.isOpen !== false} returnDays={profile?.returnWindowDays ?? 14} />
      {together.length > 0 && <section className="lf-sec"><h2 className="lf-h2">{t("product.together")}</h2><ul className="lf-tog">{together.map((x) => <li key={x.id}><Link href={`/products/${x.slug}`}><span>{x.name}</span><b>{money(x.priceMinor, x.currency, locale)}</b></Link></li>)}</ul></section>}
      <section className="lf-sec" id="lf-reviews"><h2 className="lf-h2">{t("shop.reviews")} <small>({product.reviews.count})</small></h2>
        {product.reviews.count === 0 ? <p className="lf-muted">{t("shop.noReviews")}</p> : <ul className="lf-revs">{product.reviews.reviews.map((r, i) => <li key={i}><p><b>{r.firstName}</b><Stars value={r.rating} /></p>{r.reviewText && <p>{r.reviewText}</p>}</li>)}</ul>}
      </section>
      {related.length > 0 && <section className="lf-sec"><h2 className="lf-h2">{t("product.related")}</h2><div className="lf-grid">{related.slice(0, 4).map((r) => <LifestyleCard key={r.id} p={r} t={t} locale={locale} />)}</div></section>}
    </div>
  );
}
