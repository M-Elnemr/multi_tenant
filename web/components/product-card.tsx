import Link from "next/link";
import { money } from "@/lib/format";
import { mediaUrl } from "@/lib/media";
import type { Locale } from "@/lib/i18n";

export type ProductSummary = { id: string; name: string; slug: string; brand?: string; currency: string; minPriceMinor: number | null; imageUrl?: string | null; imageMediaBase?: string | null; imageMediaExt?: string | null; inStock: boolean };

export function ProductCard({ p, locale, outOfStock }: { p: ProductSummary; locale: Locale; outOfStock: string }) {
  return (
    <Link href={`/products/${p.slug}`} className="group overflow-hidden rounded-xl border border-slate-200 bg-white transition hover:shadow-md">
      <div className="aspect-square bg-slate-100">
        {p.imageUrl || p.imageMediaBase ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={mediaUrl({ url: p.imageUrl, mediaBase: p.imageMediaBase, mediaExt: p.imageMediaExt }, "thumb") ?? ""} alt={p.name} width={320} height={320} decoding="async" className="h-full w-full object-cover transition group-hover:scale-105" loading="lazy" />
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
