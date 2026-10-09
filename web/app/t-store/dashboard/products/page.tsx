"use client";

import Link from "next/link";
import { useState } from "react";
import { ProductCsv } from "@/components/dashboard/product-csv";
import { Page, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Empty, ErrorText, Input, Loading, PageHeader, Pager, StatusBadge, Table, Td } from "@/components/ui";
import { money } from "@/lib/format";

type Row = { id: string; name: string; slug: string; status: string; currency: string; minPriceMinor: number; available: number };

export default function Products() {
  const { t, locale } = useI18n();
  const { can } = useMe();
  const [q, setQ] = useState("");
  const [page, setPage] = useState(1);
  const { data, loading, error, reload } = useApi<Page<Row>>(`store/products?page=${page}&q=${encodeURIComponent(q)}`);
  return (
    <>
      <PageHeader title={t("nav.products")} actions={can("product.create") && <Link href="/dashboard/products/new" className="rounded-lg bg-brand px-4 py-2.5 text-sm font-medium text-white">{t("products.add")}</Link>} />
      <div className="mb-4 max-w-sm"><Input placeholder={t("common.search")} value={q} onChange={(e) => { setQ(e.target.value); setPage(1); }} /></div>
      {can("product.create") && <ProductCsv onImported={reload} />}
      <ErrorText error={error} />
      {loading && !data ? <Loading /> : data?.data.length === 0 ? <Empty>{t("products.none")}</Empty> : (
        <>
          <Table head={[t("products.name"), t("admin.status"), t("products.price"), t("products.stock")]}>
            {data?.data.map((p) => (<tr key={p.id} className="hover:bg-slate-50"><Td><Link href={`/dashboard/products/${p.id}`} className="font-medium text-brand">{p.name}</Link></Td><Td><StatusBadge status={p.status} /></Td><Td>{money(p.minPriceMinor, p.currency, locale)}</Td><Td className={p.available <= 0 ? "text-red-600" : ""}>{p.available}</Td></tr>))}
          </Table>
          <Pager meta={data?.meta} onPage={setPage} />
        </>
      )}
    </>
  );
}
