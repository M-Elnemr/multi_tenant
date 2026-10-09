import Link from "next/link";
import { backendJson } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";
import { resolveHost } from "@/lib/tenant";
import { currentHost } from "@/lib/backend";
import { FilterSheet, FilterSidebar, SortSelect, type Facets } from "./filters";
import { ShopProductCard, type ShopProduct } from "./product-card";
import { buildTree, pathTo, type ShopCategory } from "./types";

export type ListingParams = { q?: string; category?: string; page?: string; sort?: string; minPrice?: string; maxPrice?: string; brand?: string; inStock?: string; onSale?: string; minRating?: string; audience?: string; color?: string; size?: string; condition?: string };
type PageData = { data: ShopProduct[]; meta: { page: number; pageSize: number; total: number; hasNext: boolean } };

/** Product grid with breadcrumbs, filters, sorting and paging. Used by /products and /c/[slug]. */
export async function Listing({ params, category }: { params: ListingParams; category?: string }) {
  const { t, locale } = await getT();
  const info = await resolveHost(await currentHost());
  const currency = info.kind === "TENANT" ? info.currency : "EGP";
  const slug = category ?? params.category ?? "";
  const qs = new URLSearchParams({ pageSize: "12" });
  for (const k of ["q", "page", "sort", "minPrice", "maxPrice", "brand", "inStock", "onSale", "minRating", "audience", "color", "size", "condition"] as const) if (params[k]) qs.set(k, params[k]!);
  if (slug) qs.set("category", slug);
  const fq = new URLSearchParams(); if (params.q) fq.set("q", params.q); if (slug) fq.set("category", slug);
  const [cats, res, facets] = await Promise.all([
    backendJson<ShopCategory[]>(`/shop/categories?lang=${locale}`).catch(() => [] as ShopCategory[]),
    backendJson<PageData>(`/shop/products?${qs}`).catch(() => ({ data: [], meta: { page: 1, pageSize: 12, total: 0, hasNext: false } }) as PageData),
    backendJson<Facets>(`/shop/products/facets?${fq}`).catch(() => ({ brands: [], priceMinMinor: null, priceMaxMinor: null, total: 0 }) as Facets),
  ]);
  const tree = buildTree(cats);
  const crumbs = slug ? pathTo(cats, slug) : [];
  const current = crumbs[crumbs.length - 1];
  const basePath = category ? `/c/${category}` : "/products";
  const link = (p: number) => { const n = new URLSearchParams(); for (const [k, v] of Object.entries(params)) if (v && k !== "page" && !(category && k === "category")) n.set(k, v); n.set("page", String(p)); return `${basePath}?${n}`; };
  const pageNo = res.meta.page;
  const pages = Math.max(1, Math.ceil(res.meta.total / res.meta.pageSize));
  const labels = { outOfStock: t("shop.outOfStock"), off: t("shop.off"), badges: { NEW: t("badge.NEW"), SALE: t("badge.SALE"), BEST_SELLER: t("badge.BEST_SELLER"), LIMITED: t("badge.LIMITED") } };
  const aud = (params.audience ?? "").split(",").filter(Boolean);
  const audTitle = aud.length === 0 ? "" : ["BOYS", "GIRLS", "BABY"].every((x) => aud.includes(x)) && aud.length === 3 ? t("audience.KIDS") : aud.map((x) => t(`audience.${x}`)).join(" · ");
  const title = params.q ? t("shop.resultsFor", { q: params.q }) : current?.name ?? (params.onSale === "true" ? t("shop.offers") : audTitle || t("shop.allProducts"));
  const subcats = current ? cats.filter((c) => c.parentId === current.id && c.productCount > 0) : [];
  return (
    <div>
      <nav className="mb-4 flex flex-wrap items-center gap-1.5 text-sm text-[var(--s-mute)]" aria-label="breadcrumb">
        <Link href="/" className="hover:text-[var(--brand)]">{t("shop.home")}</Link><span>/</span>
        <Link href="/products" className="hover:text-[var(--brand)]">{t("shop.allProducts")}</Link>
        {crumbs.map((c, i) => <span key={c.id} className="flex items-center gap-1.5"><span>/</span>{i === crumbs.length - 1 ? <b className="text-[var(--s-ink)]">{c.name}</b> : <Link href={`/c/${c.slug}`} className="hover:text-[var(--brand)]">{c.name}</Link>}</span>)}
      </nav>
      <div className="mb-6 flex flex-wrap items-end justify-between gap-3">
        <div><h1 className="text-2xl font-extrabold sm:text-3xl">{title}</h1><p className="mt-1 text-sm text-[var(--s-mute)]">{t("shop.resultCount", { n: res.meta.total })}</p></div>
        <div className="flex items-center gap-2"><FilterSheet tree={tree} activeSlug={slug} facets={facets} currency={currency} /><SortSelect /></div>
      </div>
      {subcats.length > 0 && <div className="mb-6 flex flex-wrap gap-2">{subcats.map((c) => <Link key={c.id} href={`/c/${c.slug}`} className="s-chip">{c.name} <span className="text-[var(--s-mute)]">{c.productCount}</span></Link>)}</div>}
      <div className="flex items-start gap-8">
        <FilterSidebar tree={tree} activeSlug={slug} facets={facets} currency={currency} />
        <div className="min-w-0 flex-1">
          {res.data.length === 0 ? (
            <div className="s-card grid place-items-center gap-3 p-14 text-center"><span className="text-5xl">🔍</span><p className="text-lg font-extrabold">{t("shop.noResults")}</p><p className="text-sm text-[var(--s-mute)]">{t("shop.noResultsHint")}</p><Link href="/products" className="s-btn s-btn-sm mt-2">{t("shop.allProducts")}</Link></div>
          ) : (
            <div className="stagger grid grid-cols-2 gap-3 sm:gap-5 md:grid-cols-3">{res.data.map((p, i) => <div key={p.id} style={{ "--i": i } as React.CSSProperties}><ShopProductCard p={p} locale={locale} labels={labels} /></div>)}</div>
          )}
          {pages > 1 && (
            <div className="mt-10 flex items-center justify-center gap-3 text-sm font-bold">
              {pageNo > 1 ? <Link href={link(pageNo - 1)} className="s-btn s-btn-ghost s-btn-sm">{t("common.prev")}</Link> : <span />}
              <span className="text-[var(--s-mute)]">{pageNo} / {pages}</span>
              {res.meta.hasNext ? <Link href={link(pageNo + 1)} className="s-btn s-btn-ghost s-btn-sm">{t("common.next")}</Link> : <span />}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
