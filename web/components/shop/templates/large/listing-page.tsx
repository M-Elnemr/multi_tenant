import type { ListingParams } from "@/components/shop/listing";
import { getT } from "@/lib/i18n-server";
import type { LargeId } from "./ids";
import { loadListing } from "./load";
import { LARGE_SETS } from "./registry";

/** /products and /c/[slug] for a large template: gathers the data once and hands it to the template's own listing. */
export async function LargeListing({ id, params, category }: { id: LargeId; params: ListingParams; category?: string }) {
  const { t, locale } = await getT();
  const d = await loadListing(params, category, id === "sn-market" ? 20 : 12);
  const L = LARGE_SETS[id]!.Listing;
  return <L d={d} t={t} locale={locale} />;
}
