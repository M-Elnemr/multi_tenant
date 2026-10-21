import Link from "next/link";
import { getT } from "@/lib/i18n-server";
import { openJson, type OpenFacets, type OpenPage, type OpenProduct } from "@/lib/open";
import { GOVERNORATES } from "@/lib/governorates";
import { FiltersButton, FiltersSidebar, SortSelect } from "./filters";
import { ProductGrid } from "./product-card";
import { Icon } from "../icons";

type SP = Record<string, string | string[] | undefined>;
const KEYS = ["q", "category", "shop", "minPrice", "maxPrice", "brand", "inStock", "onSale", "freeShipping", "minRating", "audience", "color", "size", "condition", "governorate", "sort", "page"];

const first = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v);

/** The shared results page: used for search, a category and a single shop. Fixed values (the category in /c/x, the shop in /shop/x) always win over the URL. */
export async function OpenListing({ searchParams, basePath, fixed = {}, title, header }: { searchParams: SP; basePath: string; fixed?: { category?: string; shop?: string }; title?: string; header?: React.ReactNode }) {
  const { t, locale } = await getT();
  const params = new URLSearchParams();
  for (const k of KEYS) { const v = first(searchParams[k]); if (v) params.set(k, v); }
  if (fixed.category) params.set("category", fixed.category);
  if (fixed.shop) params.set("shop", fixed.shop);
  params.set("pageSize", "24");
  const facetParams = new URLSearchParams();
  for (const k of ["q", "category", "shop"]) if (params.get(k)) facetParams.set(k, params.get(k) as string);
  const [list, facets] = await Promise.all([
    openJson<OpenPage<OpenProduct>>(`/products?${params.toString()}`),
    openJson<OpenFacets>(`/products/facets?${facetParams.toString()}`),
  ]);

  const q = params.get("q");
  const page = Number(params.get("page") ?? 1);
  const activeKeys = KEYS.filter((k) => !["q", "category", "shop", "sort", "page"].includes(k) && first(searchParams[k]));
  const chip = (label: string, k: string) => {
    const p = new URLSearchParams(); for (const x of KEYS) { const v = first(searchParams[x]); if (v && x !== k && x !== "page") p.set(x, v); }
    return <Link key={k + label} href={`${basePath}${p.size ? `?${p}` : ""}`} className="o-chip" data-active="true">{label}<Icon name="close" className="h-3.5 w-3.5" /></Link>;
  };
  const labelFor = (k: string): string => {
    const v = first(searchParams[k]) ?? "";
    switch (k) {
      case "minPrice": return `≥ ${Number(v) / 100}`;
      case "maxPrice": return `≤ ${Number(v) / 100}`;
      case "onSale": return t("open.f.onSale");
      case "inStock": return t("open.f.inStock");
      case "freeShipping": return t("open.f.freeShipping");
      case "minRating": return `${v}★ ${t("open.f.andUp")}`;
      case "governorate": { const g = GOVERNORATES.find((x) => x.code === v); return g ? `${t("open.f.deliverTo")}: ${locale === "ar" ? g.ar : g.en}` : v; }
      default: return v.split(",").join(" · ");
    }
  };
  const pageHref = (n: number) => { const p = new URLSearchParams(); for (const x of KEYS) { const v = first(searchParams[x]); if (v && x !== "page") p.set(x, v); } p.set("page", String(n)); return `${basePath}?${p}`; };
  const heading = title ?? (q ? t("open.resultsFor", { q }) : t("open.allProducts"));

  return (
    <div className="o-container pt-5 md:pt-8">
      {header}
      <div className="mb-4 flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-black md:text-3xl">{heading}</h1>
          <p className="o-mute mt-0.5 text-sm">{t("open.resultsCount", { n: list.meta.total })}</p>
        </div>
        <div className="flex items-center gap-2"><FiltersButton facets={facets} fixed={fixed} basePath={basePath} activeCount={activeKeys.length + (!fixed.shop && first(searchParams.shop) ? 1 : 0)} total={list.meta.total} /><SortSelect basePath={basePath} /></div>
      </div>
      {(activeKeys.length > 0 || (!fixed.shop && first(searchParams.shop))) && (
        <div className="mb-4 flex flex-wrap gap-2">
          {activeKeys.map((k) => chip(labelFor(k), k))}
          {!fixed.shop && first(searchParams.shop) && chip(facets.shops.find((s) => s.shopSlug === first(searchParams.shop))?.shopName ?? (first(searchParams.shop) as string), "shop")}
        </div>
      )}
      <div className="flex items-start gap-6">
        <FiltersSidebar facets={facets} fixed={fixed} basePath={basePath} activeCount={activeKeys.length + (!fixed.shop && first(searchParams.shop) ? 1 : 0)} />
        <div className="min-w-0 flex-1">
          {list.data.length === 0 ? (
            <div className="o-card px-6 py-16 text-center"><p className="text-5xl">🔎</p><h2 className="o-title mt-3">{t("open.noResults")}</h2><p className="o-mute mt-1">{t("open.noResultsHint")}</p><Link href="/search" className="o-btn mt-5">{t("open.clearSearch")}</Link></div>
          ) : (
            <ProductGrid products={list.data} />
          )}
          {(list.meta.hasNext || page > 1) && (
            <nav className="mt-8 flex items-center justify-center gap-3" aria-label="pagination">
              {page > 1 && <Link href={pageHref(page - 1)} className="o-btn o-btn-ghost o-btn-sm"><Icon name="arrow" className="h-4 w-4 rotate-180 rtl:rotate-0" />{t("open.prev")}</Link>}
              <span className="text-sm font-bold">{page} / {Math.max(1, Math.ceil(list.meta.total / list.meta.pageSize))}</span>
              {list.meta.hasNext && <Link href={pageHref(page + 1)} className="o-btn o-btn-ghost o-btn-sm">{t("open.next")}<Icon name="arrow" className="h-4 w-4 rtl:rotate-180" /></Link>}
            </nav>
          )}
        </div>
      </div>
    </div>
  );
}
