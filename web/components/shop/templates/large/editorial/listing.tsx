import Link from "next/link";
import { EditorialCard } from "./card";
import { EditorialListingShell } from "./listing-shell";
import type { ListingProps } from "../types";

export function EditorialListing({ d, t, locale }: ListingProps) {
  const { res } = d;
  return (
    <div className="ed-wrap ed-page">
      <header className="ed-title-block">
        <p className="ed-eyebrow">{d.crumbs.length > 1 ? d.crumbs.slice(0, -1).map((c) => c.name).join(" / ") : t("shop.allProducts")}</p>
        <h1>{d.title}</h1>
        {d.subcats.length > 0 && <p className="ed-subcats">{d.subcats.map((c) => <Link key={c.id} href={`/c/${c.slug}`}>{c.name}</Link>)}</p>}
      </header>
      <EditorialListingShell tree={d.tree} activeSlug={d.slug} facets={d.facets} currency={d.currency} total={res.meta.total}>
        {res.data.length === 0 ? (
          <div className="ed-none">
            <h2>{t("shop.noResults")}</h2><p>{t("lg.emptyHint")}</p>
            <p className="ed-subcats">{d.suggestions.map((c) => <Link key={c.id} href={`/c/${c.slug}`}>{c.name}</Link>)}</p>
          </div>
        ) : res.data.map((p) => <EditorialCard key={p.id} p={p} t={t} locale={locale} />)}
      </EditorialListingShell>
      {d.pages > 1 && (
        <nav className="ed-pager" aria-label="pagination">
          {d.page > 1 ? <Link href={d.pageHref(d.page - 1)} rel="prev">{t("lg.previous")}</Link> : <span />}
          <bdi>{d.page} / {d.pages}</bdi>
          {d.page < d.pages ? <Link href={d.pageHref(d.page + 1)} rel="next">{t("lg.next")}</Link> : <span />}
        </nav>
      )}
    </div>
  );
}
