import { loadFeed, xml } from "@/lib/feed";

/** Google Merchant Center product feed (RSS 2.0 with the g: namespace): add this address as a scheduled fetch in Merchant Center. */
export async function GET() {
  const f = await loadFeed();
  if (!f) return new Response("Not found", { status: 404 });
  const items = f.items.map((i) => `    <item>
      <g:id>${xml(i.id)}</g:id>
      <g:item_group_id>${xml(i.itemGroupId)}</g:item_group_id>
      <title>${xml(i.title.slice(0, 150))}</title>
      <description>${xml(i.description.slice(0, 4900))}</description>
      <link>${xml(i.link)}</link>
${i.imageLink ? `      <g:image_link>${xml(i.imageLink)}</g:image_link>\n` : ""}      <g:price>${xml(i.compareAtPrice ?? i.price)}</g:price>
${i.compareAtPrice ? `      <g:sale_price>${xml(i.price)}</g:sale_price>\n` : ""}      <g:availability>${i.availability === "in stock" ? "in_stock" : "out_of_stock"}</g:availability>
      <g:brand>${xml(i.brand)}</g:brand>
      <g:condition>new</g:condition>
${i.gtin ? `      <g:gtin>${xml(i.gtin)}</g:gtin>\n` : ""}    </item>`).join("\n");
  const body = `<?xml version="1.0" encoding="UTF-8"?>\n<rss version="2.0" xmlns:g="http://base.google.com/ns/1.0">\n  <channel>\n    <title>${xml(f.shop)}</title>\n    <link>${xml(f.url)}</link>\n    <description>${xml(f.description || f.shop)}</description>\n${items}\n  </channel>\n</rss>\n`;
  return new Response(body, { headers: { "Content-Type": "application/xml; charset=utf-8", "Cache-Control": "public, max-age=1800" } });
}
