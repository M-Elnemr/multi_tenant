import { backendJson, currentHost } from "@/lib/backend";
import { resolveHost } from "@/lib/tenant";

export type FeedItem = { id: string; itemGroupId: string; title: string; description: string; link: string; imageLink: string | null; price: string; compareAtPrice?: string; availability: string; stock: number; brand: string; condition: string; gtin?: string };
export type Feed = { shop: string; description: string; url: string; currency: string; items: FeedItem[] };

/** The shop's product feed, or null for a host that is not a store. Shared by the Google, Facebook and AI-assistant feeds. */
export async function loadFeed(): Promise<Feed | null> {
  const info = await resolveHost(await currentHost());
  if (info.kind !== "TENANT" || info.type !== "STORE") return null;
  return backendJson<Feed>("/shop/feed").catch(() => null);
}

export const xml = (s: string) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
export const csv = (s: string | number | undefined | null) => `"${String(s ?? "").replace(/"/g, '""').replace(/\r?\n/g, " ")}"`;
