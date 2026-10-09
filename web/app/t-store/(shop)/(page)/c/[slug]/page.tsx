import type { Metadata } from "next";
import { Suspense } from "react";
import { Listing, type ListingParams } from "@/components/shop/listing";
import { backendJson } from "@/lib/backend";
import type { ShopCategory } from "@/components/shop/types";
import { getT } from "@/lib/i18n-server";

export async function generateMetadata({ params }: { params: Promise<{ slug: string }> }): Promise<Metadata> {
  const slug = (await params).slug;
  const cat = (await backendJson<ShopCategory[]>(`/shop/categories?lang=${(await getT()).locale}`).catch(() => [] as ShopCategory[])).find((c) => c.slug === slug);
  return cat ? { title: cat.name } : {};
}

export default async function CategoryPage({ params, searchParams }: { params: Promise<{ slug: string }>; searchParams: Promise<ListingParams> }) {
  const { slug } = await params;
  return <Suspense><Listing params={await searchParams} category={decodeURIComponent(slug)} /></Suspense>;
}
