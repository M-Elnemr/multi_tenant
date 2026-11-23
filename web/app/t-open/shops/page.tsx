import Link from "next/link";
import type { Metadata } from "next";
import { OEmpty } from "@/components/open/empty";
import { Icon } from "@/components/icons";
import { ShopCard } from "@/components/open/sections";
import { getT } from "@/lib/i18n-server";
import { openJson, type OpenPage, type OpenShop } from "@/lib/open";

export const dynamic = "force-dynamic";

export async function generateMetadata(): Promise<Metadata> { const { t } = await getT(); return { title: t("open.allShops") }; }

export default async function ShopsPage({ searchParams }: { searchParams: Promise<{ q?: string; sort?: string; page?: string }> }) {
  const { t, locale } = await getT();
  const sp = await searchParams;
  const qs = new URLSearchParams({ pageSize: "24", ...(sp.q ? { q: sp.q } : {}), ...(sp.sort ? { sort: sp.sort } : {}), ...(sp.page ? { page: sp.page } : {}) });
  const list = await openJson<OpenPage<OpenShop>>(`/shops?${qs}`);
  const page = Number(sp.page ?? 1);
  return (
    <div className="o-container pt-6 md:pt-10">
      <div className="mb-5 flex flex-wrap items-end justify-between gap-3">
        <div><p className="o-eyebrow">{t("open.allShops")}</p><h1 className="o-h1">{t("open.shopsHeading")}</h1><p className="o-mute mt-0.5 text-sm">{t("open.shopsCount", { n: list.meta.total })}</p></div>
        <form className="flex gap-2" role="search"><input name="q" type="search" defaultValue={sp.q} placeholder={t("open.searchShops")} aria-label={t("open.searchShops")} className="o-input !w-56 !rounded-full" /><button className="o-btn o-btn-dark o-btn-sm" type="submit"><Icon name="search" className="h-4 w-4" />{t("open.search")}</button></form>
      </div>
      <div className="mb-5 flex gap-2">{(["popular", "rating", "name"] as const).map((k) => <Link key={k} href={`/shops?sort=${k}${sp.q ? `&q=${encodeURIComponent(sp.q)}` : ""}`} className="o-chip" data-active={(sp.sort ?? "popular") === k}>{t(`open.shopSort.${k}`)}</Link>)}</div>
      {list.data.length === 0 ? <OEmpty icon="bag" title={t("open.noResults")} href="/shops" cta={t("open.clearSearch")} /> : (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">{list.data.map((s) => <ShopCard key={s.id} s={s} locale={locale} productsLabel={t("open.productsCount")} />)}</div>
      )}
      {(list.meta.hasNext || page > 1) && (
        <nav className="mt-8 flex items-center justify-center gap-3">
          {page > 1 && <Link href={`/shops?page=${page - 1}${sp.sort ? `&sort=${sp.sort}` : ""}${sp.q ? `&q=${encodeURIComponent(sp.q)}` : ""}`} className="o-btn o-btn-ghost o-btn-sm">{t("open.prev")}</Link>}
          {list.meta.hasNext && <Link href={`/shops?page=${page + 1}${sp.sort ? `&sort=${sp.sort}` : ""}${sp.q ? `&q=${encodeURIComponent(sp.q)}` : ""}`} className="o-btn o-btn-ghost o-btn-sm">{t("open.next")}</Link>}
        </nav>
      )}
    </div>
  );
}
