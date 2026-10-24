import { loadFeed } from "@/lib/feed";

/** A short, honest description of the shop for AI assistants, pointing at the live product feed. */
export async function GET() {
  const f = await loadFeed();
  if (!f) return new Response("Not found", { status: 404 });
  const top = f.items.slice(0, 20).map((i) => `- ${i.title}: ${i.price} (${i.availability}) ${i.link}`).join("\n");
  const body = `# ${f.shop}\n\n> ${f.description || "Online shop"}\n\nOrders can be placed on the website with cash on delivery. Live prices and stock: ${f.url}/feeds/products.json\n\n## Products\n${top}\n`;
  return new Response(body, { headers: { "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "public, max-age=900" } });
}
