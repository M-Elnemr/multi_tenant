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
      <section className="bg-brand-gradient relative animate-fade-up overflow-hidden rounded-3xl px-6 py-16 text-center text-white shadow-lift"><div className="pointer-events-none absolute -end-16 -top-16 h-64 w-64 rounded-full bg-white/10 [animation:drift_12s_ease-in-out_infinite]" /><div className="pointer-events-none absolute -bottom-20 start-0 h-56 w-56 rounded-full bg-white/10 [animation:drift_16s_ease-in-out_infinite_reverse]" />
        <h1 className="relative text-3xl font-extrabold sm:text-5xl">{profile?.profile.storeName}</h1>
        {(profile?.profile.shortDescription || profile?.profile.about) && <p className="relative mx-auto mt-3 max-w-xl text-lg opacity-90">{profile.profile.shortDescription ?? profile.profile.about}</p>}
        <Link href="/products" className="relative mt-7 inline-block rounded-2xl bg-white px-7 py-3 font-semibold text-slate-900 shadow-lg transition hover:-translate-y-0.5 active:scale-[.97]">{t("shop.browse")}</Link>
      </section>
      {cats.length > 0 && (
        <section>
          <h2 className="mb-4 text-xl font-bold">{t("shop.categories")}</h2>
          <div className="flex flex-wrap gap-2">{cats.map((c) => <Link key={c.id} href={`/products?category=${c.slug}`} className="rounded-full border border-slate-200 bg-white px-4 py-2 text-sm font-medium shadow-sm transition hover:-translate-y-0.5 hover:border-brand hover:bg-brand-soft hover:text-brand">{c.name}</Link>)}</div>
        </section>
      )}
      <section>
        <h2 className="mb-4 text-xl font-bold">{t("shop.newest")}</h2>
        {products.data.length === 0 ? <p className="text-slate-500">{t("shop.empty")}</p> : (
          <div className="stagger grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">{products.data.map((p, i) => <div key={p.id} style={{ "--i": i } as React.CSSProperties}><ProductCard p={p} locale={locale} outOfStock={t("shop.outOfStock")} /></div>)}</div>
        )}
      </section>
    </div>
  );
}
