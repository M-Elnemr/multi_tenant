"use client";

import Link from "next/link";
import { useState } from "react";
import { dateOnly, money } from "@/lib/format";
import { Page, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Badge, Empty, ErrorText, Loading, PageHeader, Pager, Table, Td } from "@/components/ui";

type Row = { id: string; number: string; kind: string; issuedAt: string; totalMinor: number; vatMinor: number; currency: string; buyerName: string; orderNumber: string };

export default function Invoices() {
  const { t, locale } = useI18n();
  const [page, setPage] = useState(1);
  const { data, loading, error } = useApi<Page<Row>>(`store/invoices?page=${page}`);
  return (
    <>
      <PageHeader title={t("inv.title")} subtitle={t("inv.subtitle")} />
      <ErrorText error={error} />
      {loading && !data ? <Loading /> : data?.data.length === 0 ? <Empty>{t("inv.none")}</Empty> : (
        <>
          <Table head={[t("inv.number"), t("orders.order"), t("inv.buyer"), t("inv.date"), t("pos.total"), t("inv.vat")]}>
            {data?.data.map((r) => <tr key={r.id} className="hover:bg-slate-50"><Td><Link href={`/dashboard/invoices/${r.id}`} className="font-mono text-brand" dir="ltr">{r.number}</Link> {r.kind === "CREDIT_NOTE" && <Badge tone="amber">{t("inv.creditNote")}</Badge>}</Td><Td><span dir="ltr">{r.orderNumber}</span></Td><Td>{r.buyerName}</Td><Td>{dateOnly(r.issuedAt, locale)}</Td><Td>{money(r.totalMinor, r.currency, locale)}</Td><Td>{money(r.vatMinor, r.currency, locale)}</Td></tr>)}
          </Table>
          <Pager meta={data?.meta} onPage={setPage} />
        </>
      )}
    </>
  );
}
