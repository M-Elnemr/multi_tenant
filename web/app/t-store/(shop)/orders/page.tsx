"use client";

import Link from "next/link";
import { useState } from "react";
import { Page, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Empty, ErrorText, Loading, PageHeader, Pager, StatusBadge, Table, Td } from "@/components/ui";
import { dateTime, money } from "@/lib/format";

type Order = { id: string; orderNumber: string; status: string; paymentStatus: string; totalMinor: number; currency: string; createdAt: string };

export default function Orders() {
  const { t, locale } = useI18n();
  const [page, setPage] = useState(1);
  const { data, loading, error } = useApi<Page<Order>>(`shop/orders?page=${page}`);
  return (
    <>
      <PageHeader title={t("orders.mine")} />
      <ErrorText error={error} />
      {loading && !data ? <Loading /> : data?.data.length === 0 ? <Empty>{t("orders.none")}</Empty> : (
        <>
          <Table head={[t("orders.number"), t("admin.status"), t("orders.payment"), t("checkout.total"), t("admin.created")]}>
            {data?.data.map((o) => (
              <tr key={o.id}><Td><Link href={`/orders/${o.id}`} className="font-mono text-brand">{o.orderNumber}</Link></Td><Td><StatusBadge status={o.status} /></Td><Td><StatusBadge status={o.paymentStatus} /></Td><Td>{money(o.totalMinor, o.currency, locale)}</Td><Td>{dateTime(o.createdAt, locale)}</Td></tr>
            ))}
          </Table>
          <Pager meta={data?.meta} onPage={setPage} />
        </>
      )}
    </>
  );
}
