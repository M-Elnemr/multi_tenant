import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { OpenListing } from "@/components/open/listing";
import { getT } from "@/lib/i18n-server";
import { dec, openJson, type OpenFacets } from "@/lib/open";

export const dynamic = "force-dynamic";

async function titleOf(slug: string, locale: string): Promise<string | null> {
  const f = await openJson<OpenFacets>(`/products/facets?category=${encodeURIComponent(slug)}`).catch(() => null);
  const last = f?.trail.at(-1);
  return last ? (locale === "ar" ? last.nameAr : last.nameEn || last.nameAr) : null;
}

export async function generateMetadata({ params }: { params: Promise<{ slug: string }> }): Promise<Metadata> {
  const { locale } = await getT();
  const t = await titleOf((await params).slug, locale);
  return t ? { title: t } : {};
}

export default async function CategoryPage({ params, searchParams }: { params: Promise<{ slug: string }>; searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const slug = dec((await params).slug);
  const { locale } = await getT();
  const title = await titleOf(slug, locale);
  if (!title) notFound();
  return <OpenListing searchParams={await searchParams} basePath={`/c/${encodeURIComponent(slug)}`} fixed={{ category: slug }} title={title} />;
}
