import { csv, loadFeed } from "@/lib/feed";

/** Facebook and Instagram catalog feed (CSV): add this address as a scheduled feed in Commerce Manager. */
export async function GET() {
  const f = await loadFeed();
  if (!f) return new Response("Not found", { status: 404 });
  const head = "id,title,description,availability,condition,price,link,image_link,brand,item_group_id,sale_price";
  const rows = f.items.map((i) => [i.id, i.title.slice(0, 150), i.description.slice(0, 4900), i.availability, "new", i.compareAtPrice ?? i.price, i.link, i.imageLink ?? "", i.brand, i.itemGroupId, i.compareAtPrice ? i.price : ""].map(csv).join(","));
  return new Response([head, ...rows].join("\n") + "\n", { headers: { "Content-Type": "text/csv; charset=utf-8", "Cache-Control": "public, max-age=1800" } });
}
