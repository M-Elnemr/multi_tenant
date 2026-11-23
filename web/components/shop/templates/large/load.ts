import type { Facets } from "@/components/shop/filters";
import type { ListingParams } from "@/components/shop/listing";
import type { ShopProduct } from "@/components/shop/product-card";
import { buildTree, pathTo, type CategoryNode, type ShopCategory } from "@/components/shop/types";
import { backendJson, currentHost } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";
import { resolveHost } from "@/lib/tenant";

type PageData = { data: ShopProduct[]; meta: { page: number; pageSize: number; total: number; hasNext: boolean } };

export type ListingData = {
  title: string; kind: "all" | "category" | "search" | "offers"; q: string; slug: string; basePath: string; currency: string;
  cats: ShopCategory[]; tree: CategoryNode[]; crumbs: ShopCategory[]; current?: ShopCategory; subcats: ShopCategory[];
  res: PageData; facets: Facets; page: number; pages: number; pageHref: (p: number) => string; params: ListingParams;
  /** other top categories to offer when a search or filter finds nothing */
  suggestions: ShopCategory[];
};

/** The data of /products, /c/[slug] and the search and offers views: the same backend calls as the shop's own listing, gathered for a template to lay out. */
export async function loadListing(params: ListingParams, category?: string, pageSize = 12): Promise<ListingData> {
  const { t, locale } = await getT();
  const info = await resolveHost(await currentHost());
  const currency = info.kind === "TENANT" ? info.currency : "EGP";
  const slug = category ?? params.category ?? "";
  const qs = new URLSearchParams({ pageSize: String(pageSize) });
  for (const k of ["q", "page", "sort", "minPrice", "maxPrice", "brand", "inStock", "onSale", "minRating", "audience", "color", "size", "condition"] as const) if (params[k]) qs.set(k, params[k]!);
  if (slug) qs.set("category", slug);
  qs.set("lang", locale);
  const fq = new URLSearchParams(); if (params.q) fq.set("q", params.q); if (slug) fq.set("category", slug);
  const [cats, res, facets] = await Promise.all([
    backendJson<ShopCategory[]>(`/shop/categories?lang=${locale}`).catch(() => [] as ShopCategory[]),
    backendJson<PageData>(`/shop/products?${qs}`).catch(() => ({ data: [], meta: { page: 1, pageSize, total: 0, hasNext: false } }) as PageData),
    backendJson<Facets>(`/shop/products/facets?${fq}`).catch(() => ({ brands: [], priceMinMinor: null, priceMaxMinor: null, total: 0 }) as Facets),
  ]);
  const crumbs = slug ? pathTo(cats, slug) : [];
  const current = crumbs[crumbs.length - 1];
  const basePath = category ? `/c/${category}` : "/products";
  const aud = (params.audience ?? "").split(",").filter(Boolean);
  const audTitle = aud.length === 0 ? "" : ["BOYS", "GIRLS", "BABY"].every((x) => aud.includes(x)) && aud.length === 3 ? t("audience.KIDS") : aud.map((x) => t(`audience.${x}`)).join(" · ");
  const kind = params.q ? "search" : current ? "category" : params.onSale === "true" ? "offers" : "all";
  const title = params.q ? t("shop.resultsFor", { q: params.q }) : current?.name ?? (params.onSale === "true" ? t("shop.offers") : audTitle || t("shop.allProducts"));
  const pages = Math.max(1, Math.ceil(res.meta.total / res.meta.pageSize));
  const pageHref = (p: number) => { const n = new URLSearchParams(); for (const [k, v] of Object.entries(params)) if (v && k !== "page" && !(category && k === "category")) n.set(k, v); n.set("page", String(p)); return `${basePath}?${n}`; };
  return {
    title, kind, q: params.q ?? "", slug, basePath, currency, cats, tree: buildTree(cats), crumbs, current, subcats: current ? cats.filter((c) => c.parentId === current.id && c.productCount > 0) : [],
    res, facets, page: res.meta.page, pages, pageHref, params, suggestions: cats.filter((c) => !c.parentId && c.productCount > 0).slice(0, 6),
  };
}
