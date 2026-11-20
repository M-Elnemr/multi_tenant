import { notFound } from "next/navigation";
import { backendJson } from "@/lib/backend";
import { BookClient, type BookConfig } from "./book-client";

export const dynamic = "force-dynamic";

export default async function Book() {
  const cfg = await backendJson<BookConfig>("/shop/booking/config").catch(() => null);
  if (!cfg || cfg.services.length === 0 || cfg.hours.length === 0) notFound();
  return <BookClient cfg={cfg} />;
}
