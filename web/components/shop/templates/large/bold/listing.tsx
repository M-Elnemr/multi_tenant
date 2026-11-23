import Link from "next/link";
import { BoldCard } from "./card";
import { BoldListingShell } from "./listing-shell";
import type { ListingProps } from "../types";

export function BoldListing({ d, t, locale }: ListingProps) {
  const { res } = d;
  return (
    <div className="bd-wrap bd-page">
      <nav className="bd-crumbs" aria-label="breadcrumb"><Link href="/">{t("shop.home")}</Link>{d.crumbs.map((c) => <Link key={c.id} href={`/c/${c.slug}`}>{c.name}</Link>)}</nav>
      <h1 className="bd-title">{d.title}</h1>
      {d.subcats.length > 0 && <ul className="bd-subs">{d.subcats.map((c) => <li key={c.id}><Link href={`/c/${c.slug}`} className="bd-pill">{c.name} <small>{c.productCount}</small></Link></li>)}</ul>}
      <BoldListingShell tree={d.tree} activeSlug={d.slug} facets={d.facets} currency={d.currency} total={res.meta.total}>
        {res.data.length === 0 ? (
          <div className="bd-nores"><h2>{t("shop.noResults")}</h2><p>{t("lg.emptyHint")}</p><ul className="bd-subs">{d.suggestions.map((c) => <li key={c.id}><Link href={`/c/${c.slug}`} className="bd-pill">{c.name}</Link></li>)}</ul></div>
        ) : res.data.map((p) => <BoldCard key={p.id} p={p} t={t} locale={locale} />)}
      </BoldListingShell>
      {d.pages > 1 && (
        <nav className="bd-pager" aria-label="pagination">
          {d.page > 1 ? <Link href={d.pageHref(d.page - 1)} rel="prev" className="bd-pill">{t("lg.previous")}</Link> : <span />}
          <bdi>{d.page} / {d.pages}</bdi>
          {d.page < d.pages ? <Link href={d.pageHref(d.page + 1)} rel="next" className="bd-pill">{t("lg.next")}</Link> : <span />}
        </nav>
      )}
    </div>
  );
}
