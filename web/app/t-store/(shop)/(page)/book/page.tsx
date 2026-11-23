import { notFound } from "next/navigation";
import { backendJson } from "@/lib/backend";
import { shopLook } from "@/lib/shop-look";   // DR-E
import { vnDesign } from "@/components/shop/service/vn-registry";   // DR-E
import { BookClient, type BookConfig } from "./book-client";

export const dynamic = "force-dynamic";

export default async function Book({ searchParams }: { searchParams?: Promise<Record<string, string | string[] | undefined>> }) {
  const q = (await searchParams) ?? {};
  const wanted = typeof q.service === "string" ? q.service : undefined;
  const cfg = await backendJson<BookConfig>("/shop/booking/config").catch(() => null);
  if (!cfg || cfg.services.length === 0 || cfg.hours.length === 0) notFound();
  const { template, service } = await shopLook();   // DR-E: a vn-* design has its own booking page (same endpoints)
  const vn = service ? vnDesign(template) : undefined;
  if (vn) {
    const str = (k: string) => (typeof q[k] === "string" ? (q[k] as string) : undefined);
    return <vn.Book cfg={cfg} init={{ service: wanted, date: str("date"), time: str("time") }} />;
  }
  return <BookClient cfg={cfg} initial={cfg.services.some((s) => s.id === wanted) ? wanted : undefined} />;
}
