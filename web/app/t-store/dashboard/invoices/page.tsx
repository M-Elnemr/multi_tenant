"use client";

import { useRouter } from "next/navigation";
import { DateRangePicker, rangeQuery, type DateRange } from "@/components/date-range";
import { useCallback, useState } from "react";
import { dateOnly, money } from "@/lib/format";
import { Page, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Badge, Empty, ErrorText, KpiStrip, PageHeader, Pager } from "@/components/ui";
import { Bar, Cell, DataTable, Row, RowMenu, SearchField } from "@/components/dashboard/sell/kit";

type RowT = { id: string; number: string; kind: string; issuedAt: string; totalMinor: number; vatMinor: number; currency: string; buyerName: string; orderNumber: string };

/** Tax invoices and credit notes issued from orders. */
export default function Invoices() {
  const { t, locale } = useI18n();
  const router = useRouter();
  const [page, setPage] = useState(1);
  const [range, setRange] = useState<DateRange>({});
  const [q, setQ] = useState("");
  const setSearch = useCallback((v: string) => { setQ(v); setPage(1); }, []);
  const rq = (b: string) => { const qs = rangeQuery(new URLSearchParams(), range).toString(); return qs ? `${b}${b.includes("?") ? "&" : "?"}${qs}` : b; };
  const { data, loading, error } = useApi<Page<RowT>>(rq(`store/invoices?page=${page}&q=${encodeURIComponent(q)}`));
  const n = (x: number) => x.toLocaleString(locale === "ar" ? "ar-EG" : "en-US");
  const cur = data?.data[0]?.currency ?? "EGP";
  const pageTotal = data?.data.reduce((s, r) => s + (r.kind === "CREDIT_NOTE" ? -r.totalMinor : r.totalMinor), 0) ?? 0;
  const pageVat = data?.data.reduce((s, r) => s + (r.kind === "CREDIT_NOTE" ? -r.vatMinor : r.vatMinor), 0) ?? 0;
  const filtered = !!q.trim() || !!range.from || !!range.to;
  return (
    <div className="space-y-5">
      <PageHeader eyebrow={t("sell.eyebrow")} title={t("inv.title")} subtitle={t("inv.subtitle")} />
      <KpiStrip items={[
        { label: t("sell.kpi.docs"), value: data ? n(data.meta.total) : "–", icon: "receipt", tone: "brand" },
        { label: t("sell.kpi.pageTotal"), value: data ? money(pageTotal, cur, locale) : "–", icon: "wallet", tone: "green" },
        { label: t("inv.vat"), value: data ? money(pageVat, cur, locale) : "–", icon: "tag", tone: "slate" },
      ]} />
      <Bar>
        <SearchField value={q} onChange={setSearch} placeholder={t("invoices.search")} />
        <DateRangePicker value={range} onChange={(r) => { setRange(r); setPage(1); }} />
      </Bar>
      <ErrorText error={error} />
      {data && data.data.length === 0 ? (
        <Empty icon="receipt" title={filtered ? t("orders.noneMatch") : t("inv.none")}>{t("sell.inv.emptyHint")}</Empty>
      ) : (
        <>
          <DataTable loading={loading && !data} cols={[{ label: t("inv.number") }, { label: t("orders.order") }, { label: t("inv.buyer") }, { label: t("inv.date") }, { label: t("pos.total"), end: true }, { label: t("inv.vat"), end: true }, { label: "", end: true }]}>
            {data?.data.map((r) => (
              <Row key={r.id} onOpen={() => router.push(`/dashboard/invoices/${r.id}`)}>
                <Cell><span className="font-mono text-[13px] font-semibold text-ink" dir="ltr">{r.number}</span> {r.kind === "CREDIT_NOTE" && <Badge tone="amber">{t("inv.creditNote")}</Badge>}</Cell>
                <Cell><span dir="ltr" className="font-mono text-[12.5px] text-ink-2">{r.orderNumber}</span></Cell>
                <Cell className="font-medium text-ink">{r.buyerName}</Cell>
                <Cell className="whitespace-nowrap text-[12.5px] text-muted tnum">{dateOnly(r.issuedAt, locale)}</Cell>
                <Cell end><span className="font-semibold tnum">{r.kind === "CREDIT_NOTE" ? "−" : ""}{money(r.totalMinor, r.currency, locale)}</span></Cell>
                <Cell end><span className="text-muted tnum">{money(r.vatMinor, r.currency, locale)}</span></Cell>
                <Cell end><RowMenu label={t("inbox.more")} items={[{ label: t("sell.openInvoice"), icon: "eye", onSelect: () => router.push(`/dashboard/invoices/${r.id}`) }]} /></Cell>
              </Row>
            ))}
          </DataTable>
          <Pager meta={data?.meta} onPage={setPage} />
        </>
      )}
    </div>
  );
}
