"use client";

import { useState } from "react";
import { Page, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { ErrorText, Loading, PageHeader, Pager, Table, Td } from "@/components/ui";
import { dateOnly, money } from "@/lib/format";

type C = { id: string; customerNumber: string; firstName: string; lastName: string; phone: string; createdAt: string; ordersCount: number; totalSpentMinor: number };

export default function Customers() {
  const { t, locale, currency } = useI18n();
  const [page, setPage] = useState(1);
  const { data, loading, error } = useApi<Page<C>>(`store/customers?page=${page}`);
  return (
    <>
      <PageHeader title={t("nav.customers")} />
      <ErrorText error={error} />
      {loading && !data ? <Loading /> : (
        <>
          <Table head={[t("orders.customer"), t("staff.phone"), t("customers.orders"), t("customers.spent"), t("admin.created")]}>
            {data?.data.map((c) => <tr key={c.id}><Td>{c.firstName} {c.lastName}<div className="font-mono text-xs text-slate-500">{c.customerNumber}</div></Td><Td><span dir="ltr">{c.phone}</span></Td><Td>{c.ordersCount}</Td><Td>{money(c.totalSpentMinor, currency, locale)}</Td><Td>{dateOnly(c.createdAt, locale)}</Td></tr>)}
          </Table>
          <Pager meta={data?.meta} onPage={setPage} />
        </>
      )}
    </>
  );
}
