"use client";

import Link from "next/link";
import { Page, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Card, ErrorText, Loading, PageHeader, StatusBadge, Table, Td } from "@/components/ui";
import { dateTime, money } from "@/lib/format";

type Summary = { ordersToday: number; salesTodayMinor: number; ordersThisMonth: number; salesThisMonthMinor: number; averageOrderValueMinor: number; lowStockCount: number; ordersByStatus: Record<string, number>; topProducts?: { name: string; units: number; revenueMinor: number }[] };
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
        <div className="mb-6 grid grid-cols-2 gap-4 lg:grid-cols-4">
          {[[t("dash.salesToday"), money(s.salesTodayMinor, currency, locale)], [t("dash.ordersToday"), s.ordersToday], [t("dash.salesMonth"), money(s.salesThisMonthMinor, currency, locale)], [t("dash.aov"), money(s.averageOrderValueMinor, currency, locale)]].map(([l, v]) => (
            <Card key={String(l)}><p className="text-sm text-slate-500">{l}</p><p className="mt-1 text-xl font-semibold">{v}</p></Card>
          ))}
        </div>
      )}
      {s && s.lowStockCount > 0 && <Link href="/dashboard/inventory" className="mb-6 block rounded-xl border border-amber-300 bg-amber-50 p-4 text-sm text-amber-900">⚠️ {t("dash.lowStock", { n: s.lowStockCount })}</Link>}
      <div className="grid gap-6 lg:grid-cols-2">
        <div>
          <h2 className="mb-2 font-medium">{t("dash.recentOrders")}</h2>
          <Table head={[t("orders.number"), t("admin.status"), t("checkout.total")]}>
            {recent.data?.data.map((o) => (<tr key={o.id}><Td><Link href={`/dashboard/orders/${o.id}`} className="font-mono text-brand">{o.orderNumber}</Link><div className="text-xs text-slate-500">{o.customerNameSnapshot} · {dateTime(o.createdAt, locale)}</div></Td><Td><StatusBadge status={o.status} /></Td><Td>{money(o.totalMinor, o.currency, locale)}</Td></tr>))}
          </Table>
        </div>
        {s?.topProducts && (
          <div>
            <h2 className="mb-2 font-medium">{t("dash.topProducts")}</h2>
            <Table head={[t("products.name"), t("dash.units"), t("dash.revenue")]}>
              {s.topProducts.map((p) => <tr key={p.name}><Td>{p.name}</Td><Td>{p.units}</Td><Td>{money(p.revenueMinor, currency, locale)}</Td></tr>)}
            </Table>
          </div>
        )}
      </div>
    </>
  );
}
