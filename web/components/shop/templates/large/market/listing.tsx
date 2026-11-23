import Link from "next/link";
import { Icon } from "@/components/icons";
import { MarketCard } from "./card";
import { MarketListingShell } from "./listing-shell";
import type { ListingProps } from "../types";

export function MarketListing({ d, t, locale }: ListingProps) {
  const { res } = d;
  return (
    <div className="lg-wrap mk-page">
      <nav className="mk-crumbs" aria-label="breadcrumb">
        <Link href="/">{t("shop.home")}</Link><span aria-hidden>/</span>
        {d.crumbs.length === 0 ? <span aria-current="page">{d.title}</span> : <Link href="/products">{t("shop.allProducts")}</Link>}
        {d.crumbs.map((c, i) => <span key={c.id} className="mk-crumb">{i < d.crumbs.length && <span aria-hidden>/</span>}{i === d.crumbs.length - 1 ? <span aria-current="page">{c.name}</span> : <Link href={`/c/${c.slug}`}>{c.name}</Link>}</span>)}
      </nav>
      <h1 className="mk-title">{d.title}</h1>
      {d.subcats.length > 0 && <ul className="mk-subcats">{d.subcats.map((c) => <li key={c.id}><Link href={`/c/${c.slug}`} className="lg-chip">{c.name}<small>{c.productCount}</small></Link></li>)}</ul>}
      <MarketListingShell tree={d.tree} activeSlug={d.slug} facets={d.facets} currency={d.currency} total={res.meta.total}>
        {res.data.length === 0 ? (
          <div className="lg-empty mk-empty">
            <Icon name="search" className="h-10 w-10" />
            <h2>{t("shop.noResults")}</h2>
            <p>{t("lg.emptyHint")}</p>
            <div className="mk-subcats">{d.suggestions.map((c) => <Link key={c.id} href={`/c/${c.slug}`} className="lg-chip">{c.name}</Link>)}</div>
            <Link href="/products" className="mk-btn">{t("shop.allProducts")}</Link>
          </div>
        ) : res.data.map((p) => <MarketCard key={p.id} p={p} t={t} locale={locale} />)}
      </MarketListingShell>
      {d.pages > 1 && (
        <nav className="mk-pager" aria-label="pagination">
          {d.page > 1 ? <Link href={d.pageHref(d.page - 1)} rel="prev" className="lg-chip"><Icon name="chevron" className="h-4 w-4 rotate-90 rtl:-rotate-90" />{t("lg.previous")}</Link> : <span />}
          <bdi>{d.page} / {d.pages}</bdi>
          {d.page < d.pages ? <Link href={d.pageHref(d.page + 1)} rel="next" className="lg-chip">{t("lg.next")}<Icon name="chevron" className="h-4 w-4 -rotate-90 rtl:rotate-90" /></Link> : <span />}
        </nav>
      )}
    </div>
  );
}
