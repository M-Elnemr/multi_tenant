import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { OpenListing } from "@/components/open/listing";
import { Icon } from "@/components/icons";
import { Monogram } from "@/components/open/media";
import { getT } from "@/lib/i18n-server";
import { dec, openJson, type OpenShop } from "@/lib/open";

export const dynamic = "force-dynamic";

const shopOf = (slug: string) => openJson<OpenShop>(`/shops/${encodeURIComponent(dec(slug))}`).catch(() => null);

export async function generateMetadata({ params }: { params: Promise<{ slug: string }> }): Promise<Metadata> {
  const s = await shopOf((await params).slug);
  return s ? { title: s.name, description: s.shortDescription ?? undefined } : {};
}

/** A shop inside the marketplace: its banner, rating and every product it sells, with the same filters as the rest of the store. */
export default async function ShopPage({ params, searchParams }: { params: Promise<{ slug: string }>; searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const { slug: rawSlug } = await params;
  const slug = dec(rawSlug);
  const { t } = await getT();
  const shop = await shopOf(slug);
  if (!shop) notFound();
  const color = shop.primaryColor && /^#[0-9a-fA-F]{3,8}$/.test(shop.primaryColor) ? shop.primaryColor : "#17130e";
  const rating = Number(shop.rating ?? 0);
  const header = (
    <section className="o-card mb-6 overflow-hidden">
      <div className="h-24 md:h-32" style={{ background: `linear-gradient(120deg, ${color}, color-mix(in srgb, ${color} 55%, #17130e))` }} />
      <div className="flex flex-wrap items-end gap-4 px-5 pb-5">
        <span className="o-frame relative -mt-10 grid h-20 w-20 shrink-0 place-items-center overflow-hidden rounded-3xl border-4 border-[var(--o-paper)] bg-[var(--o-paper)] shadow-lg md:-mt-12 md:h-24 md:w-24" >
          {/* eslint-disable-next-line @next/next/no-img-element */}
          {shop.logoUrl ? <img src={shop.logoUrl} alt="" width={96} height={96} className="h-full w-full object-cover" /> : <Monogram name={shop.name} />}
        </span>
        <div className="min-w-0 flex-1 pt-2">
          <h1 className="o-h1">{shop.name}</h1>
          <p className="mt-1 flex flex-wrap items-center gap-x-4 gap-y-1 text-sm text-[var(--o-mute)]">
            {rating > 0 && <span className="flex items-center gap-1 font-bold text-[var(--o-ink)]"><Icon name="star" className="o-stars h-3.5 w-3.5 fill-current" />{rating.toFixed(1)} <span className="font-medium text-[var(--o-mute)]">({shop.ratingCount})</span></span>}
            <span>{shop.productCount} {t("open.productsCount")}</span>
            {shop.isOpen === false && <span className="font-bold text-[var(--o-red)]">{shop.closedMessage || t("open.shopClosed")}</span>}
          </p>
          {shop.shortDescription && <p className="mt-2 max-w-2xl text-sm">{shop.shortDescription}</p>}
        </div>
        <a href={shop.url} className="o-btn o-btn-ghost o-btn-sm" rel="noopener">{t("open.visitShopSite")}<Icon name="arrow" className="h-4 w-4 rtl:rotate-180" /></a>
      </div>
    </section>
  );
  return <OpenListing searchParams={await searchParams} basePath={`/shop/${slug}`} fixed={{ shop: slug }} title={t("open.shopProducts")} header={header} />;
}
