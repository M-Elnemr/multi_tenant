import Link from "next/link";
import { ProductCard, type ProductSummary } from "@/components/product-card";
import { backendJson } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";

type Cat = { id: string; name: string; slug: string };
type Profile = { profile: { storeName: string; shortDescription?: string; about?: string } };

export default async function StoreHome() {
  const { t, locale } = await getT();
  const [profile, cats, products] = await Promise.all([
    backendJson<Profile>("/shop/profile").catch(() => null),
    backendJson<Cat[]>("/shop/categories").catch(() => [] as Cat[]),
    backendJson<{ data: ProductSummary[] }>("/shop/products?pageSize=8").catch(() => ({ data: [] as ProductSummary[] })),
  ]);
  return (
    <div className="space-y-10">
      <section className="rounded-2xl bg-gradient-to-br from-brand to-brand-2 px-6 py-14 text-center text-white">
        <h1 className="text-3xl font-bold sm:text-4xl">{profile?.profile.storeName}</h1>
        {(profile?.profile.shortDescription || profile?.profile.about) && <p className="mx-auto mt-3 max-w-xl opacity-90">{profile.profile.shortDescription ?? profile.profile.about}</p>}
        <Link href="/products" className="mt-6 inline-block rounded-xl bg-white px-6 py-3 font-medium text-slate-900">{t("shop.browse")}</Link>
      </section>
      {cats.length > 0 && (
        <section>
          <h2 className="mb-3 text-xl font-semibold">{t("shop.categories")}</h2>
          <div className="flex flex-wrap gap-2">{cats.map((c) => <Link key={c.id} href={`/products?category=${c.slug}`} className="rounded-full border border-slate-300 bg-white px-4 py-1.5 text-sm hover:border-brand">{c.name}</Link>)}</div>
        </section>
      )}
      <section>
        <h2 className="mb-3 text-xl font-semibold">{t("shop.newest")}</h2>
        {products.data.length === 0 ? <p className="text-slate-500">{t("shop.empty")}</p> : (
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">{products.data.map((p) => <ProductCard key={p.id} p={p} locale={locale} outOfStock={t("shop.outOfStock")} />)}</div>
        )}
      </section>
    </div>
  );
}
