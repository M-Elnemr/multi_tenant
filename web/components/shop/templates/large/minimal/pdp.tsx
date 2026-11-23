import Link from "next/link";
import { money } from "@/lib/format";
import { Stars } from "../kit";
import { MinimalBuy } from "./buy";
import { MinimalCard } from "./card";
import type { PdpProps } from "../types";

export function MinimalPdp({ product, related, together, profile, crumbs, t, locale }: PdpProps) {
  return (
    <div className="mn-wrap mn-page">
      <nav className="mn-crumbs" aria-label="breadcrumb"><Link href="/">{t("shop.home")}</Link>{crumbs.map((c) => <Link key={c.slug} href={`/c/${c.slug}`}>{c.name}</Link>)}</nav>
      <MinimalBuy product={product} whatsapp={profile?.whatsapp} storeName={profile?.storeName ?? ""} isOpen={profile?.isOpen !== false} returnDays={profile?.returnWindowDays ?? 14} />
      {together.length > 0 && <section className="mn-sec"><div className="mn-sec-head"><h2>{t("product.together")}</h2></div><ul className="mn-ruled">{together.map((x) => <li key={x.id}><Link href={`/products/${x.slug}`}><span>{x.name}</span><b>{money(x.priceMinor, x.currency, locale)}</b></Link></li>)}</ul></section>}
      <section className="mn-sec" id="mn-reviews"><div className="mn-sec-head"><h2>{t("shop.reviews")} ({product.reviews.count})</h2></div>
        {product.reviews.count === 0 ? <p className="mn-muted">{t("shop.noReviews")}</p> : <ul className="mn-ruled">{product.reviews.reviews.map((r, i) => <li key={i} className="mn-rev"><span><Stars value={r.rating} /> <b>{r.firstName}</b></span>{r.reviewText && <p>{r.reviewText}</p>}</li>)}</ul>}
      </section>
      {related.length > 0 && <section className="mn-sec"><div className="mn-sec-head"><h2>{t("product.related")}</h2></div><div className="mn-grid">{related.slice(0, 4).map((r) => <MinimalCard key={r.id} p={r} t={t} locale={locale} />)}</div></section>}
    </div>
  );
}
