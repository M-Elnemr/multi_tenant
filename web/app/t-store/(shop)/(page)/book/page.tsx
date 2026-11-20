import { notFound } from "next/navigation";
import { backendJson } from "@/lib/backend";
import { BookClient, type BookConfig } from "./book-client";

export const dynamic = "force-dynamic";

export default async function Book({ searchParams }: { searchParams?: Promise<Record<string, string | string[] | undefined>> }) {
  const q = (await searchParams) ?? {};
  const wanted = typeof q.service === "string" ? q.service : undefined;
  const cfg = await backendJson<BookConfig>("/shop/booking/config").catch(() => null);
  if (!cfg || cfg.services.length === 0 || cfg.hours.length === 0) notFound();
  return <BookClient cfg={cfg} initial={cfg.services.some((s) => s.id === wanted) ? wanted : undefined} />;
}
