import Link from "next/link";
import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { OpenBuyBox, type OpenDetail } from "@/components/open/buy-box";
import { OpenGallery } from "@/components/open/gallery";
import { WishButton } from "@/components/open/product-card";
import { ProductRail } from "@/components/open/sections";
import { Icon } from "@/components/icons";
import { BackendError } from "@/lib/backend";
import { money } from "@/lib/format";
import { getT } from "@/lib/i18n-server";
import { dec, num, openJson, type OpenProduct, type OpenShop } from "@/lib/open";

export const dynamic = "force-dynamic";

type Full = OpenDetail & {
  description: string; shortDescription?: string; brand?: string | null; specs?: { k: string; v: string }[]; sizeGuide?: string; tags?: string[]; itemCondition?: string;
  taxonomy: { slug: string; nameAr: string; nameEn: string }[]; shop: OpenShop;
  reviews: { average: number | string; count: number; reviews: { rating: number; reviewText?: string; firstName: string; createdAt: string }[] };
  delivery: { shippingMethods: { id: string; type: string; name: string; feeMinor: number; freeAboveMinor?: number | null }[]; zones: { id: string; name: string; feeMinor: number; freeAboveMinor?: number | null; codFeeMinor: number; etaMinDays: number; etaMaxDays: number; governorateCodes: string[] }[]; paymentMethods: { method: string }[] };
  moreFromShop: OpenProduct[]; similar: OpenProduct[];
};

const load = (shop: string, slug: string) => openJson<Full>(`/products/${encodeURIComponent(dec(shop))}/${encodeURIComponent(dec(slug))}`).catch((e) => { if (e instanceof BackendError && e.status === 404) return null; throw e; });

export async function generateMetadata({ params }: { params: Promise<{ shop: string; slug: string }> }): Promise<Metadata> {
  const { shop, slug } = await params;
  const p = await load(shop, slug);
  if (!p) return {};
  return { title: p.name, description: p.shortDescription || p.description?.slice(0, 160), openGraph: { title: p.name, images: p.images[0] ? [{ url: p.images[0].medium }] : undefined } };
}

export default async function OpenProductPage({ params }: { params: Promise<{ shop: string; slug: string }> }) {
  const { shop: shopSlug, slug } = await params;
  const { t, locale } = await getT();
  const p = await load(shopSlug, slug);
  if (!p) notFound();
  const rating = num(p.reviews.average);
  const prices = p.variants.map((v) => v.priceMinor);
  const lowest = prices.length ? Math.min(...prices) : 0;
  const nm = (c: { nameAr: string; nameEn: string }) => (locale === "ar" ? c.nameAr : c.nameEn || c.nameAr);
  const zones = p.delivery.zones;
  const eta = zones.length ? { min: Math.min(...zones.map((z) => z.etaMinDays)), max: Math.max(...zones.map((z) => z.etaMaxDays)) } : null;
  const cod = p.delivery.paymentMethods.some((m) => m.method === "CASH_ON_DELIVERY");
  const fees = [...zones.map((z) => z.feeMinor), ...p.delivery.shippingMethods.filter((s) => s.type !== "PICKUP" && s.type !== "ZONES").map((s) => s.feeMinor)];
  const cheapest = fees.length ? Math.min(...fees) : null;
  const jsonLd = {
    "@context": "https://schema.org", "@type": "Product", name: p.name, description: p.shortDescription || p.description?.slice(0, 300), image: p.images.map((i) => i.original), brand: p.brand ? { "@type": "Brand", name: p.brand } : undefined,
    offers: { "@type": "Offer", priceCurrency: p.currency, price: (lowest / 100).toFixed(2), availability: p.variants.some((v) => v.available > 0) ? "https://schema.org/InStock" : "https://schema.org/OutOfStock", seller: { "@type": "Organization", name: p.shop.name } },
    aggregateRating: p.reviews.count > 0 ? { "@type": "AggregateRating", ratingValue: rating, reviewCount: p.reviews.count } : undefined,
  };

  return (
    <div className="o-container pt-4 md:pt-7">
      <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd).replace(/</g, "\\u003c") }} />
      <nav className="mb-4 flex flex-wrap items-center gap-1.5 text-xs font-semibold text-[var(--o-mute)]" aria-label="breadcrumb">
        <Link href="/" className="hover:text-[var(--o-accent)]">{t("shop.home")}</Link>
        {p.taxonomy.map((c) => <span key={c.slug} className="flex items-center gap-1.5"><span>/</span><Link href={`/c/${c.slug}`} className="hover:text-[var(--o-accent)]">{nm(c)}</Link></span>)}
      </nav>

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1.1fr)_minmax(0,1fr)] lg:gap-10">
        <OpenGallery images={p.images} name={p.name} badge={p.badge === "NEW" ? <span className="o-badge o-badge-new">{t("open.badge.NEW")}</span> : null} />

        <div>
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              {p.brand && <p className="text-xs font-extrabold uppercase tracking-wider text-[var(--o-accent)]">{p.brand}</p>}
              <h1 className="mt-1 text-2xl font-black leading-tight md:text-3xl">{p.name}</h1>
            </div>
            <WishButton p={{ id: p.id, shopId: p.shopId, name: p.name, slug: p.slug, currency: p.currency, minPriceMinor: lowest, image: p.images[0] ?? null, shop: p.shop }} className="shrink-0 !bg-[var(--o-soft)]" />
          </div>
          <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-sm">
            {p.reviews.count > 0 ? <a href="#reviews" className="flex items-center gap-1 font-bold"><span className="o-stars">{"★".repeat(Math.round(rating))}</span>{rating.toFixed(1)}<span className="font-medium text-[var(--o-mute)]">({p.reviews.count})</span></a> : <span className="o-mute">{t("open.noReviewsYet")}</span>}
            <span className="text-[var(--o-mute)]">{t("open.soldBy")} <Link href={`/shop/${p.shop.slug}`} className="font-extrabold text-[var(--o-ink)] hover:text-[var(--o-accent)]">{p.shop.name}</Link></span>
          </div>
          {p.shortDescription && <p className="mt-3 text-[15px] leading-relaxed text-[var(--o-mute)]">{p.shortDescription}</p>}
          <div className="mt-5 border-t border-[var(--o-line)] pt-5"><OpenBuyBox p={p} /></div>

          <ul className="o-card mt-6 grid gap-3 p-4 text-sm">
            <li className="flex items-start gap-3"><Icon name="navigate" className="mt-0.5 h-5 w-5 shrink-0 text-[var(--o-accent)]" /><span><b>{t("open.deliveredBy", { shop: p.shop.name })}</b>{eta && <span className="block text-[var(--o-mute)]">{t("open.eta", { min: eta.min, max: eta.max })}{cheapest !== null && ` · ${cheapest === 0 ? t("open.freeShipping") : t("open.shippingFrom", { price: money(cheapest, p.currency, locale) })}`}</span>}</span></li>
            {cod && <li className="flex items-start gap-3"><Icon name="wallet" className="mt-0.5 h-5 w-5 shrink-0 text-[var(--o-accent)]" /><span><b>{t("open.trust.cod")}</b><span className="block text-[var(--o-mute)]">{t("open.trust.codSub")}</span></span></li>}
            <li className="flex items-start gap-3"><Icon name="shield" className="mt-0.5 h-5 w-5 shrink-0 text-[var(--o-accent)]" /><span><b>{t("open.trust.track")}</b><span className="block text-[var(--o-mute)]">{t("open.trust.trackSub")}</span></span></li>
          </ul>

          <Link href={`/shop/${p.shop.slug}`} className="o-card mt-4 flex items-center gap-3 p-4 transition hover:shadow-[var(--o-shadow-lg)]">
            <span className="grid h-12 w-12 shrink-0 place-items-center overflow-hidden rounded-2xl bg-[var(--o-soft)] text-xl font-black">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              {p.shop.logoUrl ? <img src={p.shop.logoUrl} alt="" width={48} height={48} className="h-full w-full object-cover" /> : p.shop.name.slice(0, 1)}
            </span>
            <span className="min-w-0 flex-1"><span className="block truncate font-extrabold">{p.shop.name}</span><span className="o-mute text-xs">{p.shop.productCount} {t("open.productsCount")}{num(p.shop.rating) > 0 ? ` · ★ ${num(p.shop.rating).toFixed(1)}` : ""}</span></span>
            <span className="o-link">{t("open.visitShop")}</span>
          </Link>
        </div>
      </div>

      <section className="o-card mt-8 p-5 md:p-8">
        <h2 className="o-title">{t("open.details")}</h2>
        {p.description && <div className="mt-3 max-w-3xl whitespace-pre-line text-[15px] leading-8">{p.description}</div>}
        {p.specs && p.specs.length > 0 && (
          <dl className="mt-5 grid max-w-3xl divide-y divide-[var(--o-line)] overflow-hidden rounded-2xl border border-[var(--o-line)] text-sm">
            {p.specs.map((s, i) => <div key={i} className="grid grid-cols-[minmax(7rem,1fr)_2fr] gap-3 px-4 py-2.5 odd:bg-[var(--o-soft)]"><dt className="font-bold">{s.k}</dt><dd>{s.v}</dd></div>)}
          </dl>
        )}
        {p.sizeGuide && <details className="mt-4"><summary className="cursor-pointer font-extrabold">{t("open.sizeGuide")}</summary><p className="mt-2 whitespace-pre-line text-sm leading-7">{p.sizeGuide}</p></details>}
      </section>

      <section id="reviews" className="o-card mt-6 scroll-mt-32 p-5 md:p-8">
        <div className="flex flex-wrap items-center gap-4">
          <h2 className="o-title">{t("open.reviews")}</h2>
          {p.reviews.count > 0 && <p className="flex items-center gap-2"><span className="text-3xl font-black">{rating.toFixed(1)}</span><span><span className="o-stars block">{"★".repeat(Math.round(rating))}</span><span className="o-mute text-xs">{t("open.basedOn", { n: p.reviews.count })}</span></span></p>}
        </div>
        {p.reviews.reviews.length === 0 ? <p className="o-mute mt-3">{t("open.noReviewsHint")}</p> : (
          <ul className="mt-4 grid gap-4 md:grid-cols-2">
            {p.reviews.reviews.map((r, i) => (
              <li key={i} className="rounded-2xl bg-[var(--o-soft)] p-4">
                <p className="flex items-center justify-between"><span className="font-extrabold">{r.firstName}</span><span className="o-stars text-sm">{"★".repeat(r.rating)}{"☆".repeat(5 - r.rating)}</span></p>
                {r.reviewText && <p className="mt-1.5 text-sm leading-7">{r.reviewText}</p>}
              </li>
            ))}
          </ul>
        )}
      </section>

      <ProductRail title={t("open.moreFromShop", { shop: p.shop.name })} href={`/shop/${p.shop.slug}`} linkLabel={t("open.seeAll")} products={p.moreFromShop} />
      <ProductRail title={t("open.similar")} products={p.similar} />
    </div>
  );
}
