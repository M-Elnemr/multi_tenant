import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { backendJson, BackendError } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";
import { ProductBuy, type ProductDetail } from "./buy";

async function load(slug: string): Promise<ProductDetail | null> {
  try {
    return await backendJson<ProductDetail>(`/shop/products/${encodeURIComponent(slug)}`);
  } catch (e) {
    if (e instanceof BackendError && e.status === 404) return null;
    throw e;
  }
}

export async function generateMetadata({ params }: { params: Promise<{ slug: string }> }): Promise<Metadata> {
  const p = await load((await params).slug);
  if (!p) return {};
  return { title: p.name, description: p.shortDescription ?? p.description.slice(0, 160), openGraph: { title: p.name, description: p.shortDescription ?? p.description.slice(0, 160) } };
}

export default async function ProductPage({ params }: { params: Promise<{ slug: string }> }) {
  const p = await load((await params).slug);
  if (!p) notFound();
  const { t } = await getT();
  const prices = p.variants.map((v) => v.priceMinor);
  // schema.org structured data for search engines (spec 14/92)
  const ld = {
    "@context": "https://schema.org", "@type": "Product", name: p.name, description: p.description, brand: p.brand ? { "@type": "Brand", name: p.brand } : undefined,
    offers: { "@type": "AggregateOffer", priceCurrency: p.currency, lowPrice: Math.min(...prices) / 100, highPrice: Math.max(...prices) / 100, availability: p.variants.some((v) => v.available > 0) ? "https://schema.org/InStock" : "https://schema.org/OutOfStock" },
  };
  return (
    <>
      <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: JSON.stringify(ld).replace(/</g, "\\u003c") }} />
      <ProductBuy product={p} />
      <section className="mt-12">
        <h2 className="mb-3 text-xl font-semibold">{t("shop.reviews")} ({p.reviews.count})</h2>
        {p.reviews.count === 0 ? <p className="text-sm text-slate-500">{t("shop.noReviews")}</p> : (
          <ul className="space-y-3">
            {p.reviews.reviews.map((r, i) => (
              <li key={i} className="rounded-xl border border-slate-200 bg-white p-4 text-sm">
                <p className="font-medium">{"★".repeat(r.rating)}{"☆".repeat(5 - r.rating)} <span className="text-slate-500">{r.firstName}</span></p>
                {r.reviewText && <p className="mt-1 text-slate-700">{r.reviewText}</p>}
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  );
}
