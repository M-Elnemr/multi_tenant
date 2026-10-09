"use client";

import Link from "next/link";
import { Page, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { ErrorText, Loading, PageHeader, Stat, StatusBadge, Table, Td } from "@/components/ui";
import { dateTime, money } from "@/lib/format";
import { governorateName } from "@/lib/governorates";

type Summary = { ordersToday: number; salesTodayMinor: number; ordersThisMonth: number; salesThisMonthMinor: number; averageOrderValueMinor: number; lowStockCount: number; cashToCollectMinor: number; cashToCollectCount: number; cashCollectedTodayMinor: number; ordersByStatus: Record<string, number>; unconfirmedCount?: number; openReturnsCount?: number; delivered30?: number; returned30?: number; cancelled30?: number; salesByDay?: { day: string; orders: number; salesMinor: number }[]; byGovernorate?: { governorateCode: string; orders: number; salesMinor: number }[]; topProducts?: { name: string; units: number; revenueMinor: number }[] };
type Order = { id: string; orderNumber: string; status: string; totalMinor: number; currency: string; customerNameSnapshot: string; createdAt: string };

export default function StoreOverview() {
  const { t, locale, currency } = useI18n();
  const { can } = useMe();
  const summary = useApi<Summary>(can("report.read") ? "store/reports/summary" : null);
  const recent = useApi<Page<Order>>(can("order.read") ? "store/orders?pageSize=6" : null);
  if (summary.loading && !summary.data) return <Loading />;
  const s = summary.data;
  return (
    <>
      <PageHeader title={t("nav.overview")} />
      <ErrorText error={summary.error} />
      {s && (
        <div className="stagger mb-6 grid grid-cols-2 gap-3 sm:gap-4 lg:grid-cols-3">
          {([[t("dash.salesToday"), money(s.salesTodayMinor, currency, locale), "chart", "brand"], [t("dash.ordersToday"), s.ordersToday, "box", "green"], [t("dash.salesMonth"), money(s.salesThisMonthMinor, currency, locale), "billing", "brand"], [t("dash.aov"), money(s.averageOrderValueMinor, currency, locale), "cart", "green"], [t("dash.cashToCollect"), `${money(s.cashToCollectMinor, currency, locale)} (${s.cashToCollectCount})`, "clock", "amber"], [t("dash.cashToday"), money(s.cashCollectedTodayMinor, currency, locale), "check", "green"]] as const).map(([l, v, icon, tone], i) => (
            <div key={String(l)} style={{ "--i": i } as React.CSSProperties}><Stat label={String(l)} value={v} icon={icon} tone={tone} /></div>
          ))}
        </div>
      )}
      {s && (s.unconfirmedCount ?? 0) > 0 && <Link href="/dashboard/orders" className="mb-4 block animate-fade-up rounded-2xl border border-amber-300 bg-amber-50 p-4 text-sm font-semibold text-amber-900 transition hover:shadow-soft">📞 {t("confirm.banner", { n: s.unconfirmedCount ?? 0 })}</Link>}
      {s && (s.openReturnsCount ?? 0) > 0 && <Link href="/dashboard/returns" className="mb-4 block animate-fade-up rounded-2xl border border-sky-300 bg-sky-50 p-4 text-sm font-semibold text-sky-900 transition hover:shadow-soft">↩️ {t("dash.openReturns", { n: s.openReturnsCount ?? 0 })}</Link>}
      {s && s.salesByDay && s.salesByDay.length > 0 && (
        <div className="mb-6 rounded-2xl border border-slate-200/80 bg-white p-5 shadow-soft">
          <div className="mb-3 flex flex-wrap items-end justify-between gap-2"><h2 className="font-bold">{t("dash.last14")}</h2>
            {(s.delivered30 ?? 0) + (s.returned30 ?? 0) + (s.cancelled30 ?? 0) > 0 && <p className="text-xs text-slate-500">{t("dash.outcome", { d: s.delivered30 ?? 0, r: s.returned30 ?? 0, c: s.cancelled30 ?? 0 })}</p>}</div>
          <div className="flex h-32 items-end gap-1.5">{(() => { const max = Math.max(...s.salesByDay!.map((d) => d.salesMinor), 1); return s.salesByDay!.map((d) => (
            <div key={d.day} className="group flex flex-1 flex-col items-center justify-end gap-1" title={`${d.day}: ${money(d.salesMinor, currency, locale)} (${d.orders})`}>
              <div className="w-full rounded-t-lg bg-brand-gradient transition group-hover:brightness-110" style={{ height: `${Math.max(6, (d.salesMinor / max) * 100)}%` }} />
              <span className="text-[10px] text-slate-400">{d.day.slice(8)}</span>
            </div>)); })()}</div>
        </div>
      )}
      {s && s.lowStockCount > 0 && <Link href="/dashboard/inventory" className="mb-6 block animate-fade-up rounded-2xl border border-amber-300 bg-amber-50 p-4 text-sm font-medium text-amber-900 transition hover:shadow-soft">⚠️ {t("dash.lowStock", { n: s.lowStockCount })}</Link>}
      <div className="grid gap-6 lg:grid-cols-2">
        <div>
          <h2 className="mb-3 text-lg font-bold">{t("dash.recentOrders")}</h2>
          <Table head={[t("orders.number"), t("admin.status"), t("checkout.total")]}>
            {recent.data?.data.map((o) => (<tr key={o.id}><Td><Link href={`/dashboard/orders/${o.id}`} className="font-mono text-brand">{o.orderNumber}</Link><div className="text-xs text-slate-500">{o.customerNameSnapshot} · {dateTime(o.createdAt, locale)}</div></Td><Td><StatusBadge status={o.status} /></Td><Td>{money(o.totalMinor, o.currency, locale)}</Td></tr>))}
          </Table>
        </div>
        {s?.byGovernorate && s.byGovernorate.length > 0 && (
          <div>
            <h2 className="mb-3 text-lg font-bold">{t("dash.byGovernorate")}</h2>
            <Table head={[t("checkout.governorate"), t("customers.orders"), t("dash.revenue")]}>
              {s.byGovernorate.map((g) => <tr key={g.governorateCode}><Td>{governorateName(g.governorateCode, locale)}</Td><Td>{g.orders}</Td><Td>{money(g.salesMinor, currency, locale)}</Td></tr>)}
            </Table>
          </div>
        )}
        {s?.topProducts && (
          <div>
            <h2 className="mb-3 text-lg font-bold">{t("dash.topProducts")}</h2>
            <Table head={[t("products.name"), t("dash.units"), t("dash.revenue")]}>
              {s.topProducts.map((p) => <tr key={p.name}><Td>{p.name}</Td><Td>{p.units}</Td><Td>{money(p.revenueMinor, currency, locale)}</Td></tr>)}
            </Table>
          </div>
        )}
      </div>
    </>
  );
}
