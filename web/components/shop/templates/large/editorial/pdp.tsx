import Link from "next/link";
import { money } from "@/lib/format";
import { Stars } from "../kit";
import { EditorialBuy } from "./buy";
import { EditorialCard } from "./card";
import type { PdpProps } from "../types";

export function EditorialPdp({ product, related, together, profile, crumbs, t, locale }: PdpProps) {
  return (
    <div className="ed-pdp-page">
      <nav className="ed-wrap ed-crumbs" aria-label="breadcrumb">
        <Link href="/">{t("shop.home")}</Link>{crumbs.map((c) => <Link key={c.slug} href={`/c/${c.slug}`}>{c.name}</Link>)}
      </nav>
      <EditorialBuy product={product} whatsapp={profile?.whatsapp} storeName={profile?.storeName ?? ""} isOpen={profile?.isOpen !== false} returnDays={profile?.returnWindowDays ?? 14} />
      {together.length > 0 && (
        <section className="ed-wrap ed-gap"><h2 className="ed-h2">{t("product.together")}</h2>
          <ul className="ed-lines">{together.map((x) => <li key={x.id}><Link href={`/products/${x.slug}`}><span>{x.name}</span><b>{money(x.priceMinor, x.currency, locale)}</b></Link></li>)}</ul>
        </section>
      )}
      <section className="ed-wrap ed-gap" id="ed-reviews"><h2 className="ed-h2">{t("shop.reviews")} <small>({product.reviews.count})</small></h2>
        {product.reviews.count === 0 ? <p className="ed-muted">{t("shop.noReviews")}</p> : <ul className="ed-quotes">{product.reviews.reviews.map((r, i) => <li key={i}><Stars value={r.rating} />{r.reviewText && <blockquote>{r.reviewText}</blockquote>}<cite>{r.firstName}</cite></li>)}</ul>}
      </section>
      {related.length > 0 && (
        <section className="ed-wrap ed-gap"><h2 className="ed-h2">{t("product.related")}</h2>
          <div className="ed-grid">{related.slice(0, 4).map((r) => <EditorialCard key={r.id} p={r} t={t} locale={locale} />)}</div>
        </section>
      )}
    </div>
  );
}
