import { Suspense } from "react";
import { Listing, type ListingParams } from "@/components/shop/listing";

export default async function Products({ searchParams }: { searchParams: Promise<ListingParams> }) {
  return <Suspense><Listing params={await searchParams} /></Suspense>;
}
