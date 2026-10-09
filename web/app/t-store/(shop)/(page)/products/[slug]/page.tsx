import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { ShopProductCard, type ShopProduct } from "@/components/shop/product-card";
import type { ShopProfile } from "@/components/shop/types";
import { backendJson, BackendError } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";
import { mediaUrl } from "@/lib/media";
import { ProductBuy, type ProductDetail } from "./buy";

/** Route params arrive percent-encoded (Arabic slugs), so decode before encoding for the backend path. */
const clean = (s: string) => { try { return decodeURIComponent(s); } catch { return s; } };

async function load(slug: string): Promise<(ProductDetail & { taxonomy?: { slug: string; nameAr: string; nameEn: string }[] }) | null> {
  try {
    return await backendJson<ProductDetail & { taxonomy?: { slug: string; nameAr: string; nameEn: string }[] }>(`/shop/products/${encodeURIComponent(clean(slug))}`);
  } catch (e) {
    if (e instanceof BackendError && e.status === 404) return null;
    throw e;
  }
}

export async function generateMetadata({ params }: { params: Promise<{ slug: string }> }): Promise<Metadata> {
  const p = await load((await params).slug);
  if (!p) return {};
  const desc = p.shortDescription ?? p.description.slice(0, 160);
  const image = mediaUrl(p.media[0], "medium");
  return { title: p.name, description: desc, openGraph: { title: p.name, description: desc, ...(image ? { images: [image] } : {}) } };
}

export default async function ProductPage({ params }: { params: Promise<{ slug: string }> }) {
  const slug = (await params).slug;
  const p = await load(slug);
  if (!p) notFound();
  const { t, locale } = await getT();
  const [prof, related] = await Promise.all([
    backendJson<{ profile: ShopProfile }>("/shop/profile").catch(() => null),
    backendJson<ShopProduct[]>(`/shop/products/${encodeURIComponent(clean(slug))}/related`).catch(() => [] as ShopProduct[]),
  ]);
  const crumbs = (p.taxonomy ?? []).map((c) => ({ id: c.slug, slug: c.slug, name: locale === "ar" ? c.nameAr : c.nameEn }));
  const prices = p.variants.map((v) => v.priceMinor);
  const ld = {
    "@context": "https://schema.org", "@type": "Product", name: p.name, description: p.description, brand: p.brand ? { "@type": "Brand", name: p.brand } : undefined,
    image: p.media.map((m) => mediaUrl(m, "medium")).filter(Boolean),
    offers: { "@type": "AggregateOffer", priceCurrency: p.currency, lowPrice: Math.min(...prices) / 100, highPrice: Math.max(...prices) / 100, availability: p.variants.some((v) => v.available > 0) ? "https://schema.org/InStock" : "https://schema.org/OutOfStock" },
    ...(p.reviews.count > 0 ? { aggregateRating: { "@type": "AggregateRating", ratingValue: p.reviews.average, reviewCount: p.reviews.count } } : {}),
  };
  const labels = { outOfStock: t("shop.outOfStock"), off: t("shop.off"), badges: { NEW: t("badge.NEW"), SALE: t("badge.SALE"), BEST_SELLER: t("badge.BEST_SELLER"), LIMITED: t("badge.LIMITED") } };
  return (
    <>
      <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: JSON.stringify(ld).replace(/</g, "\\u003c") }} />
      <nav className="mb-5 flex flex-wrap items-center gap-1.5 text-sm text-[var(--s-mute)]" aria-label="breadcrumb">
        <Link href="/" className="hover:text-[var(--brand)]">{t("shop.home")}</Link><span>/</span><Link href="/products" className="hover:text-[var(--brand)]">{t("shop.allProducts")}</Link>
        {crumbs.map((c) => <span key={c.id} className="flex items-center gap-1.5"><span>/</span><Link href={`/c/${c.slug}`} className="hover:text-[var(--brand)]">{c.name}</Link></span>)}
      </nav>
      <ProductBuy product={p} whatsapp={prof?.profile.whatsapp} storeName={prof?.profile.storeName ?? ""} isOpen={prof?.profile.isOpen !== false} returnDays={prof?.profile.returnWindowDays ?? 14} />
      <section className="mt-14">
        <h2 className="s-section-title mb-5">{t("shop.reviews")} <span className="text-[var(--s-mute)]">({p.reviews.count})</span></h2>
        {p.reviews.count === 0 ? <p className="s-card p-8 text-center text-[var(--s-mute)]">{t("shop.noReviews")}</p> : (
          <ul className="grid gap-4 md:grid-cols-2">
            {p.reviews.reviews.map((r, i) => (
              <li key={i} className="s-card p-5 text-sm">
                <p className="flex items-center justify-between"><b>{r.firstName}</b><span className="font-bold text-amber-600">{"★".repeat(r.rating)}<span className="text-slate-300">{"★".repeat(5 - r.rating)}</span></span></p>
                {r.reviewText && <p className="mt-2 leading-relaxed text-[var(--s-mute)]">{r.reviewText}</p>}
              </li>
            ))}
          </ul>
        )}
      </section>
      {related.length > 0 && (
        <section className="mt-16">
          <h2 className="s-section-title mb-5">{t("product.related")}</h2>
          <div className="grid grid-cols-2 gap-3 sm:gap-5 md:grid-cols-4">{related.slice(0, 4).map((r) => <ShopProductCard key={r.id} p={r} locale={locale} labels={labels} />)}</div>
        </section>
      )}
    </>
  );
}
