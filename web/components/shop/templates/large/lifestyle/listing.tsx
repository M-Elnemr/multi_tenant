import Link from "next/link";
import { LifestyleCard } from "./card";
import { LifestyleListingShell } from "./listing-shell";
import type { ListingProps } from "../types";

export function LifestyleListing({ d, t, locale }: ListingProps) {
  const { res } = d;
  return (
    <div className="lf-wrap lf-page">
      <header className="lf-hero-title">
        <p className="lf-eyebrow">{d.crumbs.length > 1 ? d.crumbs.slice(0, -1).map((c) => c.name).join(" · ") : t("shop.allProducts")}</p>
        <h1>{d.title}</h1>
        {d.subcats.length > 0 && <ul className="lf-subs">{d.subcats.map((c) => <li key={c.id}><Link href={`/c/${c.slug}`} className="lf-btn lf-btn-soft">{c.name}</Link></li>)}</ul>}
      </header>
      <LifestyleListingShell tree={d.tree} activeSlug={d.slug} facets={d.facets} currency={d.currency} total={res.meta.total}>
        {res.data.length === 0 ? (
          <div className="lf-nores"><h2>{t("shop.noResults")}</h2><p>{t("lg.emptyHint")}</p><ul className="lf-subs">{d.suggestions.map((c) => <li key={c.id}><Link href={`/c/${c.slug}`} className="lf-btn lf-btn-soft">{c.name}</Link></li>)}</ul></div>
        ) : res.data.map((p) => <LifestyleCard key={p.id} p={p} t={t} locale={locale} />)}
      </LifestyleListingShell>
      {d.pages > 1 && (
        <nav className="lf-pager" aria-label="pagination">
          {d.page > 1 ? <Link href={d.pageHref(d.page - 1)} rel="prev" className="lf-btn lf-btn-soft">{t("lg.previous")}</Link> : <span />}
          <bdi>{d.page} / {d.pages}</bdi>
          {d.page < d.pages ? <Link href={d.pageHref(d.page + 1)} rel="next" className="lf-btn lf-btn-soft">{t("lg.next")}</Link> : <span />}
        </nav>
      )}
    </div>
  );
}
