import { OpenListing } from "@/components/open/listing";

export const dynamic = "force-dynamic";

export default async function SearchPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  return <OpenListing searchParams={await searchParams} basePath="/search" />;
}
