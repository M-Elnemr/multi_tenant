import { currentHost } from "@/lib/backend";

export async function GET(req: Request) {
  const host = await currentHost();
  const proto = req.headers.get("x-forwarded-proto") ?? (/^(localhost|127\.)/.test(host) ? "http" : "https");
  const body = `User-agent: *\nDisallow: /dashboard\nDisallow: /account\nDisallow: /checkout\nDisallow: /cart\nDisallow: /orders\nDisallow: /api/\nSitemap: ${proto}://${host}/sitemap.xml\n`;
  return new Response(body, { headers: { "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "public, max-age=3600" } });
}
