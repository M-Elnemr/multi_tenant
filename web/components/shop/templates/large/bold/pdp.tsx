import Link from "next/link";
import { money } from "@/lib/format";
import { Stars } from "../kit";
import { BoldBuy } from "./buy";
import { BoldCard } from "./card";
import type { PdpProps } from "../types";

export function BoldPdp({ product, related, together, profile, crumbs, t, locale }: PdpProps) {
  return (
    <div className="bd-wrap bd-page">
      <nav className="bd-crumbs" aria-label="breadcrumb"><Link href="/">{t("shop.home")}</Link>{crumbs.map((c) => <Link key={c.slug} href={`/c/${c.slug}`}>{c.name}</Link>)}</nav>
      <BoldBuy product={product} whatsapp={profile?.whatsapp} storeName={profile?.storeName ?? ""} isOpen={profile?.isOpen !== false} returnDays={profile?.returnWindowDays ?? 14} />
      {together.length > 0 && <section className="bd-sec"><div className="bd-sec-head"><h2>{t("product.together")}</h2></div><ul className="bd-tog">{together.map((x) => <li key={x.id}><Link href={`/products/${x.slug}`}><span>{x.name}</span><b>{money(x.priceMinor, x.currency, locale)}</b></Link></li>)}</ul></section>}
      <section className="bd-sec" id="bd-reviews"><div className="bd-sec-head"><h2>{t("shop.reviews")} ({product.reviews.count})</h2></div>
        {product.reviews.count === 0 ? <p className="bd-muted">{t("shop.noReviews")}</p> : <ul className="bd-revs">{product.reviews.reviews.map((r, i) => <li key={i}><p><b>{r.firstName}</b><Stars value={r.rating} /></p>{r.reviewText && <p>{r.reviewText}</p>}</li>)}</ul>}
      </section>
      {related.length > 0 && <section className="bd-sec"><div className="bd-sec-head"><h2>{t("product.related")}</h2></div><div className="bd-grid">{related.slice(0, 4).map((r) => <BoldCard key={r.id} p={r} t={t} locale={locale} />)}</div></section>}
    </div>
  );
}
