import Link from "next/link";
import { money } from "@/lib/format";
import type { Locale } from "@/lib/i18n";

export type ProductSummary = { id: string; name: string; slug: string; brand?: string; currency: string; minPriceMinor: number | null; imageUrl?: string | null; inStock: boolean };

export function ProductCard({ p, locale, outOfStock }: { p: ProductSummary; locale: Locale; outOfStock: string }) {
  return (
    <Link href={`/products/${p.slug}`} className="group overflow-hidden rounded-xl border border-slate-200 bg-white transition hover:shadow-md">
      <div className="aspect-square bg-slate-100">
        {p.imageUrl ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={p.imageUrl.startsWith("/api/v1/") ? p.imageUrl.replace("/api/v1/", "/api/bff/") : p.imageUrl} alt={p.name} className="h-full w-full object-cover transition group-hover:scale-105" loading="lazy" />
        ) : (
          <div className="flex h-full items-center justify-center text-4xl text-slate-300">🛍️</div>
        )}
      </div>
      <div className="p-3">
        <p className="line-clamp-2 text-sm font-medium">{p.name}</p>
        <p className="mt-1 text-sm font-semibold text-brand">{money(p.minPriceMinor, p.currency, locale)}</p>
        {!p.inStock && <p className="mt-1 text-xs text-red-600">{outOfStock}</p>}
      </div>
    </Link>
  );
}
