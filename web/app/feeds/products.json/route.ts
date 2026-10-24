import { loadFeed } from "@/lib/feed";

/** The same catalog as plain JSON for AI shopping assistants and other tools that want live prices and stock. */
export async function GET() {
  const f = await loadFeed();
  if (!f) return new Response("Not found", { status: 404 });
  return new Response(JSON.stringify(f), { headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "public, max-age=900", "Access-Control-Allow-Origin": "*" } });
}
