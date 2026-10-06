"use client";

import Link from "next/link";
import { useState } from "react";
import { Page, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { ErrorText, Loading, PageHeader, Pager, Select, StatusBadge, Table, Td } from "@/components/ui";
import { dateTime, money } from "@/lib/format";

type Order = { id: string; orderNumber: string; status: string; paymentStatus: string; paymentMethod: string; totalMinor: number; currency: string; customerNameSnapshot: string; createdAt: string };
const STATUSES = ["PENDING", "CONFIRMED", "PROCESSING", "PACKED", "OUT_FOR_DELIVERY", "DELIVERED", "CANCELLED", "RETURN_REQUESTED", "RETURNED", "REFUNDED"];

export default function StoreOrders() {
  const { t, locale } = useI18n();
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(1);
  const { data, loading, error } = useApi<Page<Order>>(`store/orders?page=${page}&status=${status}`);
  return (
    <>
      <PageHeader title={t("nav.orders")} actions={<Select value={status} onChange={(e) => { setStatus(e.target.value); setPage(1); }}><option value="">{t("admin.allStatuses")}</option>{STATUSES.map((s) => <option key={s} value={s}>{t(`status.${s}`)}</option>)}</Select>} />
      <ErrorText error={error} />
      {loading && !data ? <Loading /> : (
        <>
          <Table head={[t("orders.number"), t("orders.customer"), t("admin.status"), t("orders.payment"), t("checkout.total"), t("admin.created")]}>
            {data?.data.map((o) => (
              <tr key={o.id} className="hover:bg-slate-50"><Td><Link href={`/dashboard/orders/${o.id}`} className="font-mono text-brand">{o.orderNumber}</Link></Td><Td>{o.customerNameSnapshot}</Td><Td><StatusBadge status={o.status} /></Td><Td><StatusBadge status={o.paymentStatus} /> <span className="text-xs text-slate-500">{t(`pay.${o.paymentMethod}`)}</span></Td><Td>{money(o.totalMinor, o.currency, locale)}</Td><Td>{dateTime(o.createdAt, locale)}</Td></tr>
            ))}
          </Table>
          <Pager meta={data?.meta} onPage={setPage} />
        </>
      )}
    </>
  );
}
