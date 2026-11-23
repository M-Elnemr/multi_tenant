import Link from "next/link";
import { MinimalCard } from "./card";
import { MinimalListingShell } from "./listing-shell";
import type { ListingProps } from "../types";

export function MinimalListing({ d, t, locale }: ListingProps) {
  const { res } = d;
  return (
    <div className="mn-wrap mn-page">
      <nav className="mn-crumbs" aria-label="breadcrumb"><Link href="/">{t("shop.home")}</Link>{d.crumbs.map((c) => <Link key={c.id} href={`/c/${c.slug}`}>{c.name}</Link>)}</nav>
      <h1 className="mn-title">{d.title}</h1>
      {d.subcats.length > 0 && <p className="mn-subs">{d.subcats.map((c) => <Link key={c.id} href={`/c/${c.slug}`}>{c.name}</Link>)}</p>}
      <MinimalListingShell tree={d.tree} activeSlug={d.slug} facets={d.facets} currency={d.currency} total={res.meta.total}>
        {res.data.length === 0 ? (
          <div className="mn-nores"><h2>{t("shop.noResults")}</h2><p>{t("lg.emptyHint")}</p><p className="mn-subs">{d.suggestions.map((c) => <Link key={c.id} href={`/c/${c.slug}`}>{c.name}</Link>)}</p></div>
        ) : res.data.map((p) => <MinimalCard key={p.id} p={p} t={t} locale={locale} />)}
      </MinimalListingShell>
      {d.pages > 1 && (
        <nav className="mn-pager" aria-label="pagination">
          {d.page > 1 ? <Link href={d.pageHref(d.page - 1)} rel="prev">{t("lg.previous")}</Link> : <span />}
          <bdi>{d.page} / {d.pages}</bdi>
          {d.page < d.pages ? <Link href={d.pageHref(d.page + 1)} rel="next">{t("lg.next")}</Link> : <span />}
        </nav>
      )}
    </div>
  );
}
