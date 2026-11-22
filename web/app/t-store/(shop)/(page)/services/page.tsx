import { notFound } from "next/navigation";
import { shopLook } from "@/lib/shop-look";
import { vnDesign } from "@/components/shop/service/vn-registry";
import { vnData } from "@/components/shop/service/vn-core/data";

export const dynamic = "force-dynamic";

/** All services of a service business (only for the vn-* designs; the older designs list services on their home page). */
export default async function Services({ searchParams }: { searchParams?: Promise<Record<string, string | string[] | undefined>> }) {
  const { template, service } = await shopLook();
  const vn = service ? vnDesign(template) : undefined;
  if (!vn) notFound();
  const q = (await searchParams) ?? {};
  return <vn.Catalog d={await vnData()} cat={typeof q.cat === "string" ? q.cat : undefined} />;
}
