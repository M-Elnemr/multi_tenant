import { backendJson, currentHost } from "@/lib/backend";
import { resolveHost } from "@/lib/tenant";

type Prod = { slug: string };
type Cat = { slug: string };
const esc = (s: string) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

/** Per-shop sitemap (products and categories) so search engines can index each shop on its own address. */
export async function GET(req: Request) {
  const host = await currentHost();
  const info = await resolveHost(host);
  const proto = req.headers.get("x-forwarded-proto") ?? (/^(localhost|127\.)/.test(host) ? "http" : "https");
  const base = `${proto}://${host}`;
  const urls: string[] = [`${base}/`, `${base}/products`];
  if (info.kind === "TENANT" && info.type === "STORE") {
    const cats = await backendJson<Cat[]>("/shop/categories").catch(() => [] as Cat[]);
    for (const c of cats) urls.push(`${base}/c/${encodeURIComponent(c.slug)}`);
    for (let page = 1; page <= 10; page++) {
      const res = await backendJson<{ data: Prod[]; meta: { hasNext: boolean } }>(`/shop/products?pageSize=100&page=${page}`).catch(() => null);
      if (!res) break;
      for (const p of res.data) urls.push(`${base}/products/${encodeURIComponent(p.slug)}`);
      if (!res.meta.hasNext) break;
    }
    urls.push(`${base}/branches`, `${base}/contact`);
  }
  const body = `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${urls.map((u) => `  <url><loc>${esc(u)}</loc></url>`).join("\n")}\n</urlset>\n`;
  return new Response(body, { headers: { "Content-Type": "application/xml; charset=utf-8", "Cache-Control": "public, max-age=3600" } });
}
