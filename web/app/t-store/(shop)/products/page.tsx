import Link from "next/link";
import { ProductCard, type ProductSummary } from "@/components/product-card";
import { backendJson } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";

type Cat = { id: string; name: string; slug: string };
type PageData = { data: ProductSummary[]; meta: { page: number; pageSize: number; total: number; hasNext: boolean } };

export default async function Products({ searchParams }: { searchParams: Promise<{ q?: string; category?: string; page?: string }> }) {
  const { q = "", category = "", page = "1" } = await searchParams;
  const { t, locale } = await getT();
  const qs = new URLSearchParams({ page, pageSize: "12" });
  if (q) qs.set("q", q);
  if (category) qs.set("category", category);
  const [cats, res] = await Promise.all([
    backendJson<Cat[]>("/shop/categories").catch(() => [] as Cat[]),
    backendJson<PageData>(`/shop/products?${qs}`).catch(() => ({ data: [], meta: { page: 1, pageSize: 12, total: 0, hasNext: false } }) as PageData),
  ]);
  const link = (p: number) => `/products?${new URLSearchParams({ ...(q && { q }), ...(category && { category }), page: String(p) })}`;
  const pageNo = res.meta.page;
  return (
    <div>
      <form className="mb-6 flex gap-2" action="/products">
        {category && <input type="hidden" name="category" value={category} />}
        <input name="q" defaultValue={q} placeholder={t("shop.search")} className="w-full max-w-md rounded-lg border border-slate-300 bg-white px-3 py-2.5 text-sm" />
        <button className="rounded-lg bg-brand px-4 text-sm font-medium text-white">{t("common.search")}</button>
      </form>
      <div className="mb-6 flex flex-wrap gap-2 text-sm">
        <Link href="/products" className={`rounded-full border px-3 py-1 ${!category ? "border-brand bg-brand text-white" : "border-slate-300 bg-white"}`}>{t("shop.all")}</Link>
        {cats.map((c) => <Link key={c.id} href={`/products?category=${c.slug}`} className={`rounded-full border px-3 py-1 ${category === c.slug ? "border-brand bg-brand text-white" : "border-slate-300 bg-white"}`}>{c.name}</Link>)}
      </div>
      {res.data.length === 0 ? <p className="py-10 text-center text-slate-500">{t("shop.noResults")}</p> : (
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">{res.data.map((p) => <ProductCard key={p.id} p={p} locale={locale} outOfStock={t("shop.outOfStock")} />)}</div>
      )}
      {res.meta.total > res.meta.pageSize && (
        <div className="mt-8 flex justify-center gap-3 text-sm">
          {pageNo > 1 && <Link href={link(pageNo - 1)} className="rounded-lg border bg-white px-4 py-2">{t("common.prev")}</Link>}
          {res.meta.hasNext && <Link href={link(pageNo + 1)} className="rounded-lg border bg-white px-4 py-2">{t("common.next")}</Link>}
        </div>
      )}
    </div>
  );
}
