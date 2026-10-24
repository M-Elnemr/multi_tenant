"use client";

import { useState } from "react";
import { dateOnly, money } from "@/lib/format";
import { governorateName } from "@/lib/governorates";
import { useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Alert, Badge, Card, Loading, Stat, Table, Tabs, Td } from "../ui";
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
  const [tab, setTab] = useState<Tab>("overview");
  const o = useApi<Overview>(`store/reports/overview?days=${days}`);
  const m = useApi<Margin>(tab === "products" ? `store/reports/margin?days=${days}` : null);
  const c = useApi<Cohort[]>(tab === "customers" ? "store/reports/cohorts" : null);
  const attr = useApi<Attr>(tab === "operations" ? `store/attribution?days=${days}` : null);
  const mon = (n: number) => money(n, currency, locale);
  const pct = (n: number, d: number) => (d === 0 ? "-" : `${Math.round((n * 100) / d)}%`);
  const trendOf = (cur: number, prev: number) => (prev === 0 ? (cur === 0 ? undefined : { text: t("rp2.new"), up: true }) : { text: `${cur >= prev ? "+" : ""}${Math.round(((cur - prev) / prev) * 100)}%`, up: cur >= prev });
  const channelLabel = (k: string) => (k === "-" ? "—" : t(`channel.${k}`));
  const payLabel = (k: string) => (k === "-" ? "—" : t(`pay.${k}`));
  const d = o.data;

  const splitBars = (rows: Split[], label: (k: string) => string) => rows.map((r) => ({ key: r.key, label: label(r.key), value: r.revenueMinor, text: mon(r.revenueMinor), sub: `${r.orders} ${t("rp2.orders")}` }));
  const splitCsv = (name: string, rows: Split[], label: (k: string) => string) => () => downloadCsv(name, [t("rp.source"), t("rp2.orders"), t("rp.revenue")], rows.map((r) => [label(r.key), r.orders, (r.revenueMinor / 100).toFixed(2)]));

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3 print:hidden">
        <Tabs<Tab> tabs={(["overview", "sales", "products", "customers", "operations"] as Tab[]).map((k) => ({ key: k, label: t(`rp2.tab.${k}`) }))} value={tab} onChange={setTab} />
        <div className="flex flex-wrap items-center gap-2">
          <div className="inline-flex rounded-xl border bg-white p-0.5">{[7, 30, 90].map((n) => <button key={n} type="button" onClick={() => setDays(n)} className={`rounded-lg px-3 py-1.5 text-sm font-semibold ${days === n ? "bg-brand text-white" : "text-slate-600 hover:bg-slate-50"}`}>{t("rp2.lastDays", { n })}</button>)}</div>
          <button type="button" onClick={() => window.print()} className="rounded-xl border bg-white px-3 py-1.5 text-sm font-semibold hover:bg-slate-50">🖨️ {t("pos.print")}</button>
        </div>
      </div>
      <p className="hidden text-sm text-slate-500 print:block">{t("rp2.lastDays", { n: days })}</p>

      {!d ? <Loading /> : (
        <>
          {tab === "overview" && (
            <>
              <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                <Stat label={t("rp.revenue")} value={mon(d.current.revenueMinor)} icon="billing" tone="green" trend={trendOf(d.current.revenueMinor, d.previous.revenueMinor)} hint={t("rp2.vsPrev")} />
                <Stat label={t("rp2.orders")} value={d.current.orders} icon="box" trend={trendOf(d.current.orders, d.previous.orders)} hint={t("rp2.vsPrev")} />
                <Stat label={t("rp2.aov")} value={mon(d.current.aovMinor)} icon="trending" trend={trendOf(d.current.aovMinor, d.previous.aovMinor)} hint={t("rp2.vsPrev")} />
                <Stat label={t("rp2.customers")} value={d.current.customers} icon="users" trend={trendOf(d.current.customers, d.previous.customers)} hint={t("rp2.vsPrev")} />
              </div>
              <Card className="space-y-2"><h2 className="font-medium">{t("rp2.revenueTrend")}</h2>
                <TrendChart points={d.trend.map((x) => x.revenueMinor)} labels={d.trend.map((x) => dateOnly(x.day, locale))} format={(n) => mon(n)} /></Card>
              <div className="grid gap-5 lg:grid-cols-2">
                <Card className="space-y-3"><h2 className="font-medium">{t("rp2.topProducts")}</h2>
                  <Bars rows={d.topProducts.map((p) => ({ key: p.name, label: p.name, value: p.revenueMinor, text: mon(p.revenueMinor), sub: `${p.units} ${t("rp.units")}` }))} />
                  {d.topProducts.length === 0 && <p className="text-sm text-slate-500">{t("rp2.empty")}</p>}</Card>
                <Card className="space-y-3"><h2 className="font-medium">{t("rp2.byChannel")}</h2>
                  <Bars rows={splitBars(d.byChannel, channelLabel)} />{d.byChannel.length === 0 && <p className="text-sm text-slate-500">{t("rp2.empty")}</p>}</Card>
              </div>
            </>
          )}

          {tab === "sales" && (
            <div className="grid gap-5 lg:grid-cols-2">
              <Card className="space-y-3"><div className="flex items-center justify-between"><h2 className="font-medium">{t("rp2.byChannel")}</h2><button type="button" className="text-xs font-semibold text-brand print:hidden" onClick={splitCsv("sales-by-channel.csv", d.byChannel, channelLabel)}>CSV</button></div><Bars rows={splitBars(d.byChannel, channelLabel)} /></Card>
              <Card className="space-y-3"><div className="flex items-center justify-between"><h2 className="font-medium">{t("rp2.byPayment")}</h2><button type="button" className="text-xs font-semibold text-brand print:hidden" onClick={splitCsv("sales-by-payment.csv", d.byPayment, payLabel)}>CSV</button></div><Bars rows={splitBars(d.byPayment, payLabel)} /></Card>
              <Card className="space-y-3 lg:col-span-2"><div className="flex items-center justify-between"><h2 className="font-medium">{t("rp2.byArea")}</h2><button type="button" className="text-xs font-semibold text-brand print:hidden" onClick={splitCsv("sales-by-governorate.csv", d.byGovernorate, (k) => (k === "-" ? "—" : governorateName(k, locale) || k))}>CSV</button></div>
                <Bars rows={splitBars(d.byGovernorate, (k) => (k === "-" ? "—" : governorateName(k, locale) || k))} /></Card>
              <Card className="space-y-2 lg:col-span-2"><h2 className="font-medium">{t("rp2.ordersTrend")}</h2>
                <TrendChart points={d.trend.map((x) => x.orders)} labels={d.trend.map((x) => dateOnly(x.day, locale))} format={(n) => String(n)} /></Card>
            </div>
          )}

          {tab === "products" && (
            <>
              <div className="grid gap-5 lg:grid-cols-2">
                <Card className="space-y-3"><h2 className="font-medium">{t("rp2.topProducts")}</h2>
                  <Bars rows={d.topProducts.map((p) => ({ key: p.name, label: p.name, value: p.revenueMinor, text: mon(p.revenueMinor), sub: `${p.units} ${t("rp.units")}` }))} /></Card>
                <Card className="space-y-3"><h2 className="font-medium">{t("rp2.lowStock")}</h2>
                  {d.lowStock.length === 0 ? <p className="text-sm text-slate-500">{t("rp2.stockOk")}</p> : (
                    <ul className="divide-y text-sm">{d.lowStock.map((x) => <li key={x.sku} className="flex items-center justify-between py-1.5"><span className="min-w-0 truncate">{x.name} <span dir="ltr" className="text-xs text-slate-500">{x.sku}</span></span><Badge tone={x.available <= 0 ? "red" : "amber"}>{x.available}</Badge></li>)}</ul>
                  )}</Card>
              </div>
              {m.data && (
                <>
                  <div className="flex items-center justify-between"><h2 className="font-medium">{t("rp.margin")}</h2><button type="button" className="text-xs font-semibold text-brand print:hidden" onClick={() => downloadCsv("margin.csv", [t("products.name"), t("rp.units"), t("rp.revenue"), t("rp.margin"), "%"], m.data!.products.map((p) => [p.name, p.units, (p.revenueMinor / 100).toFixed(2), p.marginMinor == null ? "" : (p.marginMinor / 100).toFixed(2), p.marginPercent ?? ""]))}>CSV</button></div>
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
              <div className="flex items-center justify-between"><h2 className="font-medium">{t("rp.cohorts")}</h2>{c.data && <button type="button" className="text-xs font-semibold text-brand print:hidden" onClick={() => downloadCsv("cohorts.csv", [t("rp.month"), t("rp.newCustomers"), t("rp.back1"), t("rp.back2"), t("rp.back3")], c.data!.map((r) => [r.month, r.customers, r.back1, r.back2, r.back3]))}>CSV</button>}</div>
              <p className="text-sm text-slate-500">{t("rp.cohortsHint")}</p>
              <Table head={[t("rp.month"), t("rp.newCustomers"), t("rp.back1"), t("rp.back2"), t("rp.back3")]}>
                {(c.data ?? []).map((r) => <tr key={r.month}><Td>{dateOnly(r.month, locale)}</Td><Td>{r.customers}</Td><Td>{r.back1} ({pct(r.back1, r.customers)})</Td><Td>{r.back2} ({pct(r.back2, r.customers)})</Td><Td>{r.back3} ({pct(r.back3, r.customers)})</Td></tr>)}
              </Table>
              <Card className="flex flex-wrap items-center gap-3 print:hidden"><span className="text-sm font-medium">{t("rp.export")}</span>
                <a href="/api/bff/store/export/customers.csv" className="rounded-xl border px-3 py-1.5 text-sm font-semibold hover:bg-slate-50">{t("rp.exportCustomers")}</a></Card>
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
                <Card className="space-y-3">
                  <h2 className="font-medium">{t("rp.attribution")}</h2>
                  <div className="grid gap-3 sm:grid-cols-4">
                    <Stat label={t("rp.aiRevenue")} value={mon(attr.data.aiSellerRevenueMinor)} icon="sparkles" tone="green" hint={t("rp.aiChats", { n: attr.data.aiConversations })} />
                    <Stat label={t("rp.recoveredCarts")} value={mon(attr.data.recoveredCartsMinor)} icon="box" hint={String(attr.data.recoveredCarts)} />
                    <Stat label={t("rp.codSaved")} value={mon(attr.data.codSavedMinor)} icon="billing" hint={t("rp.riskyLost", { n: attr.data.riskyOrdersLost })} />
                    <Stat label={t("rp.loyaltyOrders")} value={attr.data.loyaltyOrders} icon="users" />
                  </div>
                </Card>
              )}
              <Card className="flex flex-wrap items-center gap-3 print:hidden"><span className="text-sm font-medium">{t("rp.export")}</span>
                <a href="/api/bff/store/export/orders.csv" className="rounded-xl border px-3 py-1.5 text-sm font-semibold hover:bg-slate-50">{t("rp.exportOrders")}</a></Card>
            </>
          )}
        </>
      )}
    </div>
  );
}
