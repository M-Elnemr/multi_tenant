"use client";

import { useState } from "react";
import { useSection } from "./section";
import { dateOnly, money } from "@/lib/format";
import { governorateName } from "@/lib/governorates";
import { useApi } from "../hooks";
import { DateRangePicker, rangeQuery, type DateRange } from "../date-range";
import { useI18n } from "../i18n-provider";
import { Alert, Badge, Button, Empty, KpiStrip, Loading, Section, Segmented, Stat, Table, Td } from "../ui";
import { Icon } from "../icons";
import { Bars, downloadCsv, TrendChart } from "./report-charts";

type Split = { key: string; orders: number; revenueMinor: number };
type Overview = {
  days: number;
  current: { orders: number; revenueMinor: number; aovMinor: number; customers: number };
  previous: { orders: number; revenueMinor: number; aovMinor: number; customers: number };
  trend: { day: string; orders: number; revenueMinor: number }[];
  byChannel: Split[]; byPayment: Split[]; byGovernorate: Split[];
  topProducts: { name: string; units: number; revenueMinor: number }[];
  customerMix: { newCustomers: number; returningCustomers: number };
  operations: { cancelled: number; returned: number; cod: number; allOrders: number; refundedMinor: number };
  lowStock: { name: string; sku: string; available: number }[];
};
type Margin = { days: number; products: { name: string; units: number; revenueMinor: number; marginMinor?: number | null; marginPercent?: number | null }[]; totalRevenueMinor: number; totalMarginMinor: number; marginPercent: number };
type Attr = { aiSellerRevenueMinor: number; aiConversations: number; recoveredCarts: number; recoveredCartsMinor: number; riskyOrdersLost: number; codSavedMinor: number; loyaltyOrders: number };
type Cohort = { month: string; customers: number; back1: number; back2: number; back3: number };
type Tab = "overview" | "sales" | "products" | "customers" | "operations";

/** The report centre: one period for every tab, the previous period beside each total, charts, and a CSV or print for what is on screen. */
export default function ReportsPlus() {
  const { t, locale, currency } = useI18n();
  const [days, setDays] = useState(30);
  const [range, setRange] = useState<DateRange>({});
  const span = range.from || range.to ? rangeQuery(new URLSearchParams(), range).toString() : `days=${days}`;
  const [tab] = useSection<Tab>(["overview", "sales", "products", "customers", "operations"], "overview");
  const o = useApi<Overview>(`store/reports/overview?${span}`);
  const m = useApi<Margin>(tab === "products" ? `store/reports/margin?${span}` : null);
  const c = useApi<Cohort[]>(tab === "customers" ? "store/reports/cohorts" : null);
  const attr = useApi<Attr>(tab === "operations" ? `store/attribution?days=${days}` : null);
  const mon = (n: number) => money(n, currency, locale);
  const pct = (n: number, d: number) => (d === 0 ? "-" : `${Math.round((n * 100) / d)}%`);
  const trendOf = (cur: number, prev: number) => (prev === 0 ? (cur === 0 ? undefined : { text: t("rp2.new"), up: true }) : { text: `${cur >= prev ? "+" : ""}${Math.round(((cur - prev) / prev) * 100)}%`, up: cur >= prev });
  const deltaOf = (cur: number, prev: number) => (prev === 0 ? null : ((cur - prev) / prev) * 100);
  const channelLabel = (k: string) => (k === "-" ? "—" : t(`channel.${k}`));
  const payLabel = (k: string) => (k === "-" ? "—" : t(`pay.${k}`));
  const d = o.data;

  const splitBars = (rows: Split[], label: (k: string) => string) => rows.map((r) => ({ key: r.key, label: label(r.key), value: r.revenueMinor, text: mon(r.revenueMinor), sub: `${r.orders} ${t("rp2.orders")}` }));
  const splitCsv = (name: string, rows: Split[], label: (k: string) => string) => () => downloadCsv(name, [t("rp.source"), t("rp2.orders"), t("rp.revenue")], rows.map((r) => [label(r.key), r.orders, (r.revenueMinor / 100).toFixed(2)]));

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3 print:hidden">
        <Segmented size="sm" value={range.from || range.to ? ("" as never) : (String(days) as "7" | "30" | "90")} onChange={(k) => { setDays(Number(k)); setRange({}); }} tabs={[7, 30, 90].map((n) => ({ key: String(n) as "7" | "30" | "90", label: t("rp2.lastDays", { n }) }))} />
        <div className="flex flex-wrap items-center gap-2">
          <DateRangePicker value={range} onChange={setRange} label={t("range.custom")} />
          <Button variant="secondary" size="sm" onClick={() => window.print()}><Icon name="print" className="h-4 w-4" />{t("pos.print")}</Button>
        </div>
      </div>
      <p className="hidden text-sm text-muted print:block">{t("rp2.lastDays", { n: days })}</p>

      {!d ? <Loading /> : (
        <>
          {tab === "overview" && (
            <>
              <KpiStrip items={[
                { label: t("rp.revenue"), value: mon(d.current.revenueMinor), icon: "billing", tone: "green", delta: deltaOf(d.current.revenueMinor, d.previous.revenueMinor), hint: t("rp2.vsPrev") },
                { label: t("rp2.orders"), value: d.current.orders, icon: "box", tone: "brand", delta: deltaOf(d.current.orders, d.previous.orders), hint: t("rp2.vsPrev") },
                { label: t("rp2.aov"), value: mon(d.current.aovMinor), icon: "trending", tone: "violet", delta: deltaOf(d.current.aovMinor, d.previous.aovMinor), hint: t("rp2.vsPrev") },
                { label: t("rp2.customers"), value: d.current.customers, icon: "users", tone: "amber", delta: deltaOf(d.current.customers, d.previous.customers), hint: t("rp2.vsPrev") },
              ]} />
              <Section className="" title={t("rp2.revenueTrend")}>
                <TrendChart points={d.trend.map((x) => x.revenueMinor)} labels={d.trend.map((x) => dateOnly(x.day, locale))} format={(n) => mon(n)} /></Section>
              <div className="grid gap-5 lg:grid-cols-2">
                <Section className="" title={t("rp2.topProducts")}>
                  <Bars rows={d.topProducts.map((p) => ({ key: p.name, label: p.name, value: p.revenueMinor, text: mon(p.revenueMinor), sub: `${p.units} ${t("rp.units")}` }))} />
                  {d.topProducts.length === 0 && <Empty compact icon="chart">{t("rp2.empty")}</Empty>}</Section>
                <Section className="" title={t("rp2.byChannel")}>
                  <Bars rows={splitBars(d.byChannel, channelLabel)} />{d.byChannel.length === 0 && <Empty compact icon="chart">{t("rp2.empty")}</Empty>}</Section>
              </div>
            </>
          )}

          {tab === "sales" && (
            <div className="grid gap-5 lg:grid-cols-2">
              <Section className="" title={t("rp2.byChannel")} action={<Button size="sm" variant="ghost" onClick={splitCsv("sales-by-channel.csv", d.byChannel, channelLabel)}><Icon name="file" className="h-3.5 w-3.5" />CSV</Button>}><Bars rows={splitBars(d.byChannel, channelLabel)} /></Section>
              <Section className="" title={t("rp2.byPayment")} action={<Button size="sm" variant="ghost" onClick={splitCsv("sales-by-payment.csv", d.byPayment, payLabel)}><Icon name="file" className="h-3.5 w-3.5" />CSV</Button>}><Bars rows={splitBars(d.byPayment, payLabel)} /></Section>
              <Section className="lg:col-span-2" title={t("rp2.byArea")} action={<Button size="sm" variant="ghost" onClick={splitCsv("sales-by-governorate.csv", d.byGovernorate, (k) => (k === "-" ? "—" : governorateName(k, locale) || k))}><Icon name="file" className="h-3.5 w-3.5" />CSV</Button>}>
                <Bars rows={splitBars(d.byGovernorate, (k) => (k === "-" ? "—" : governorateName(k, locale) || k))} /></Section>
              <Section className="lg:col-span-2" title={t("rp2.ordersTrend")}>
                <TrendChart points={d.trend.map((x) => x.orders)} labels={d.trend.map((x) => dateOnly(x.day, locale))} format={(n) => String(n)} /></Section>
            </div>
          )}

          {tab === "products" && (
            <>
              <div className="grid gap-5 lg:grid-cols-2">
                <Section className="" title={t("rp2.topProducts")}>
                  <Bars rows={d.topProducts.map((p) => ({ key: p.name, label: p.name, value: p.revenueMinor, text: mon(p.revenueMinor), sub: `${p.units} ${t("rp.units")}` }))} /></Section>
                <Section className="" title={t("rp2.lowStock")}>
                  {d.lowStock.length === 0 ? <Empty compact icon="check">{t("rp2.stockOk")}</Empty> : (
                    <ul className="divide-y divide-line text-sm">{d.lowStock.map((x) => <li key={x.sku} className="flex items-center justify-between py-1.5"><span className="min-w-0 truncate">{x.name} <span dir="ltr" className="text-xs text-muted">{x.sku}</span></span><Badge tone={x.available <= 0 ? "red" : "amber"}>{x.available}</Badge></li>)}</ul>
                  )}</Section>
              </div>
              {m.data && (
                <>
                  <div className="flex items-center justify-between"><h2 className="font-display text-[15px] font-semibold text-ink">{t("rp.margin")}</h2><button type="button" className="text-xs font-semibold text-brand print:hidden" onClick={() => downloadCsv("margin.csv", [t("products.name"), t("rp.units"), t("rp.revenue"), t("rp.margin"), "%"], m.data!.products.map((p) => [p.name, p.units, (p.revenueMinor / 100).toFixed(2), p.marginMinor == null ? "" : (p.marginMinor / 100).toFixed(2), p.marginPercent ?? ""]))}>CSV</button></div>
                  <div className="grid gap-3 sm:grid-cols-3"><Stat label={t("rp.revenue")} value={mon(m.data.totalRevenueMinor)} icon="billing" /><Stat label={t("rp.margin")} value={mon(m.data.totalMarginMinor)} icon="trending" tone="green" /><Stat label={t("rp.marginPct")} value={`${m.data.marginPercent}%`} icon="sparkles" /></div>
                  <Alert tone="blue">{t("rp.costNote")}</Alert>
                  <Table head={[t("products.name"), t("rp.units"), t("rp.revenue"), t("rp.margin"), "%"]}>
                    {m.data.products.map((p) => <tr key={p.name}><Td>{p.name}</Td><Td>{p.units}</Td><Td>{mon(p.revenueMinor)}</Td><Td>{p.marginMinor == null ? <Badge tone="amber">{t("rp.noCost")}</Badge> : mon(p.marginMinor)}</Td><Td>{p.marginPercent == null ? "-" : `${p.marginPercent}%`}</Td></tr>)}
                  </Table>
                </>
              )}
            </>
          )}

          {tab === "customers" && (
            <>
              <div className="grid gap-3 sm:grid-cols-3">
                <Stat label={t("rp2.customers")} value={d.current.customers} icon="users" trend={trendOf(d.current.customers, d.previous.customers)} hint={t("rp2.vsPrev")} />
                <Stat label={t("rp.newCustomers")} value={d.customerMix.newCustomers} icon="sparkles" tone="green" />
                <Stat label={t("rp2.returning")} value={d.customerMix.returningCustomers} icon="users" hint={pct(d.customerMix.returningCustomers, d.customerMix.newCustomers + d.customerMix.returningCustomers)} />
              </div>
              <div className="flex items-center justify-between"><h2 className="font-display text-[15px] font-semibold text-ink">{t("rp.cohorts")}</h2>{c.data && <button type="button" className="text-xs font-semibold text-brand print:hidden" onClick={() => downloadCsv("cohorts.csv", [t("rp.month"), t("rp.newCustomers"), t("rp.back1"), t("rp.back2"), t("rp.back3")], c.data!.map((r) => [r.month, r.customers, r.back1, r.back2, r.back3]))}>CSV</button>}</div>
              <p className="text-sm text-muted">{t("rp.cohortsHint")}</p>
              <Table head={[t("rp.month"), t("rp.newCustomers"), t("rp.back1"), t("rp.back2"), t("rp.back3")]}>
                {(c.data ?? []).map((r) => <tr key={r.month}><Td>{dateOnly(r.month, locale)}</Td><Td>{r.customers}</Td><Td>{r.back1} ({pct(r.back1, r.customers)})</Td><Td>{r.back2} ({pct(r.back2, r.customers)})</Td><Td>{r.back3} ({pct(r.back3, r.customers)})</Td></tr>)}
              </Table>
              <Section title={t("rp.export")} icon="file" className="print:hidden"><div className="flex flex-wrap gap-2">
                <a href="/api/bff/store/export/customers.csv" className="inline-flex h-9 items-center gap-2 rounded-[10px] bg-surface px-3.5 text-[13px] font-semibold text-ink ring-1 ring-line-strong hover:bg-surface-2">{t("rp.exportCustomers")}</a></div></Section>
            </>
          )}

          {tab === "operations" && (
            <>
              <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                <Stat label={t("rp2.cancelled")} value={d.operations.cancelled} icon="box" hint={pct(d.operations.cancelled, d.operations.allOrders)} tone="amber" />
                <Stat label={t("rp2.returned")} value={d.operations.returned} icon="box" hint={pct(d.operations.returned, d.operations.allOrders)} tone="red" />
                <Stat label={t("rp2.refunded")} value={mon(d.operations.refundedMinor)} icon="billing" hint={t("rp2.refundedHint")} />
                <Stat label={t("rp2.cod")} value={d.operations.cod} icon="billing" hint={pct(d.operations.cod, d.operations.allOrders)} />
              </div>
              {attr.data && (
                <Section title={t("rp.attribution")} icon="sparkles">
                  <div className="grid gap-3 sm:grid-cols-4">
                    <Stat label={t("rp.aiRevenue")} value={mon(attr.data.aiSellerRevenueMinor)} icon="sparkles" tone="green" hint={t("rp.aiChats", { n: attr.data.aiConversations })} />
                    <Stat label={t("rp.recoveredCarts")} value={mon(attr.data.recoveredCartsMinor)} icon="box" hint={String(attr.data.recoveredCarts)} />
                    <Stat label={t("rp.codSaved")} value={mon(attr.data.codSavedMinor)} icon="billing" hint={t("rp.riskyLost", { n: attr.data.riskyOrdersLost })} />
                    <Stat label={t("rp.loyaltyOrders")} value={attr.data.loyaltyOrders} icon="users" />
                  </div>
                </Section>
              )}
              <Section title={t("rp.export")} icon="file" className="print:hidden"><div className="flex flex-wrap gap-2">
                <a href="/api/bff/store/export/orders.csv" className="inline-flex h-9 items-center gap-2 rounded-[10px] bg-surface px-3.5 text-[13px] font-semibold text-ink ring-1 ring-line-strong hover:bg-surface-2">{t("rp.exportOrders")}</a></div></Section>
            </>
          )}
        </>
      )}
    </div>
  );
}
