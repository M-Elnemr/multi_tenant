// Shapes the marketplace API returns, plus pure helpers. No server-only imports, so client components can use this file.

export type OpenShopRef = { id: string; slug: string; name: string; host: string | null };

export type OpenProduct = {
  id: string; shopId: string; name: string; slug: string; brand?: string | null; currency: string;
  minPriceMinor: number | null; compareAtMinor?: number | null; discountPct?: number | string | null;
  badge?: string | null; rating?: number | string | null; ratingCount?: number; inStock?: boolean; freeShipping?: boolean; soldCount?: number;
  image?: { thumb: string; medium: string } | null; shop: OpenShopRef; categorySlug?: string | null;
};

export type OpenCategory = { id: number; parentId: number | null; level: number; slug: string; nameAr: string; nameEn: string; icon?: string | null; n: number; children?: OpenCategory[] };

export type OpenShop = {
  id: string; slug: string; name: string; shortDescription?: string | null; isOpen: boolean; closedMessage?: string; primaryColor?: string | null;
  logoUrl: string | null; host: string; url: string; productCount: number; rating: number | string | null; ratingCount: number; createdAt: string;
};

export type OpenPage<T> = { data: T[]; meta: { page: number; pageSize: number; total: number; hasNext: boolean } };

export type OpenHome = {
  categories: OpenCategory[]; deals: OpenProduct[]; trending: OpenProduct[]; newArrivals: OpenProduct[]; topRated: OpenProduct[]; featured: OpenProduct[];
  shops: OpenShop[]; categoryRows: (OpenCategory & { products: OpenProduct[] })[]; stats: { products: number; shops: number };
};

export type OpenFacets = {
  brands: { brand: string; n: number }[]; shops: { shopId: string; shopSlug: string; shopName: string; n: number }[]; audiences: { audience: string; n: number }[];
  colors: { code: string; nameAr: string; nameEn: string; hex?: string | null; n: number }[]; sizes: { scale: string; code: string; nameAr: string; nameEn: string; n: number }[];
  categories: { slug: string; nameAr: string; nameEn: string; icon?: string | null; n: number }[]; trail: { slug: string; nameAr: string; nameEn: string }[];
  priceMinMinor: number | null; priceMaxMinor: number | null; total: number;
};

export function pickName(o: { nameAr: string; nameEn: string }, locale: string): string { return locale === "ar" ? o.nameAr : o.nameEn || o.nameAr; }
export function num(v: number | string | null | undefined): number { return v === null || v === undefined ? 0 : Number(v); }

/** Route params arrive percent-encoded (Arabic slugs); decode once so they can be encoded again exactly once when calling the API. */
export function dec(s: string): string { try { return decodeURIComponent(s); } catch { return s; } }
