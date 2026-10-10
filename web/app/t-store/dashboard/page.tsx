"use client";

import Link from "next/link";
import { Page, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { AnimatedNumber, AreaChart, ErrorText, Loading, Section, Stat, StatusBadge, Table, Td } from "@/components/ui";
import { dateTime, money } from "@/lib/format";
import { governorateName } from "@/lib/governorates";

type Summary = { ordersToday: number; salesTodayMinor: number; ordersThisMonth: number; salesThisMonthMinor: number; averageOrderValueMinor: number; lowStockCount: number; cashToCollectMinor: number; cashToCollectCount: number; cashCollectedTodayMinor: number; ordersByStatus: Record<string, number>; unconfirmedCount?: number; openReturnsCount?: number; delivered30?: number; returned30?: number; cancelled30?: number; salesByDay?: { day: string; orders: number; salesMinor: number }[]; byGovernorate?: { governorateCode: string; orders: number; salesMinor: number }[]; topProducts?: { name: string; units: number; revenueMinor: number }[] };
type Order = { id: string; orderNumber: string; status: string; totalMinor: number; currency: string; customerNameSnapshot: string; createdAt: string };

export default function StoreOverview() {
  const { t, locale, currency } = useI18n();
  const { me, can } = useMe();
  const summary = useApi<Summary>(can("report.read") ? "store/reports/summary" : null);
  const recent = useApi<Page<Order>>(can("order.read") ? "store/orders?pageSize=6" : null);
  if (summary.loading && !summary.data) return <Loading />;
  const s = summary.data;
  const attention: { href: string; text: string; icon: "alert" | "return" | "inventory"; tone: string }[] = [];
  if (s && (s.unconfirmedCount ?? 0) > 0) attention.push({ href: "/dashboard/orders", text: t("confirm.banner", { n: s.unconfirmedCount ?? 0 }), icon: "phone", tone: "bg-amber-50 text-amber-900 ring-amber-200" } as never);
  if (s && (s.openReturnsCount ?? 0) > 0) attention.push({ href: "/dashboard/returns", text: t("dash.openReturns", { n: s.openReturnsCount ?? 0 }), icon: "return", tone: "bg-sky-50 text-sky-900 ring-sky-200" });
  if (s && s.lowStockCount > 0) attention.push({ href: "/dashboard/inventory", text: t("dash.lowStock", { n: s.lowStockCount }), icon: "alert", tone: "bg-amber-50 text-amber-900 ring-amber-200" });
  return (
    <div className="space-y-6">
      <ErrorText error={summary.error} />
      {s && (
        <div className="hero-card animate-fade-up p-6 sm:p-8">
          <div className="flex flex-wrap items-end justify-between gap-6">
            <div>
              <p className="flex items-center gap-2 text-sm font-medium text-white/80"><Icon name="sparkles" className="h-4 w-4" />{t("dash.hello")}, {me?.firstName}</p>
              <p className="mt-3 text-sm text-white/80">{t("dash.salesToday")}</p>
              <p className="text-4xl font-bold tracking-tight sm:text-5xl"><AnimatedNumber value={s.salesTodayMinor} format={(n) => money(n, currency, locale)} /></p>
            </div>
            <div className="flex gap-3">
              <div className="min-w-[6rem] rounded-2xl bg-white/12 px-4 py-3 text-center ring-1 ring-white/20 backdrop-blur"><p className="text-3xl font-bold leading-none"><AnimatedNumber value={s.ordersToday} /></p><p className="mt-1.5 text-xs text-white/80">{t("dash.ordersToday")}</p></div>
              <div className="min-w-[6rem] rounded-2xl bg-white/12 px-4 py-3 text-center ring-1 ring-white/20 backdrop-blur"><p className="text-3xl font-bold leading-none"><AnimatedNumber value={s.ordersThisMonth} /></p><p className="mt-1.5 text-xs text-white/80">{t("dash.salesMonth")}</p></div>
            </div>
          </div>
        </div>
      )}
      {attention.length > 0 && (
        <div className="stagger grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
          {attention.map((a, i) => <Link key={a.href + i} href={a.href} style={{ "--i": i } as React.CSSProperties} className={`flex items-center gap-3 rounded-2xl p-4 text-sm font-semibold ring-1 transition hover:-translate-y-0.5 hover:shadow-soft ${a.tone}`}><span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-white/70"><Icon name={a.icon} className="h-5 w-5" /></span>{a.text}</Link>)}
        </div>
      )}
      {s && (
        <div className="stagger grid grid-cols-2 gap-3 sm:gap-4 xl:grid-cols-4">
          {([[t("dash.salesMonth"), money(s.salesThisMonthMinor, currency, locale), "billing", "brand"], [t("dash.aov"), money(s.averageOrderValueMinor, currency, locale), "cart", "green"], [t("dash.cashToCollect"), `${money(s.cashToCollectMinor, currency, locale)} (${s.cashToCollectCount})`, "wallet", "amber"], [t("dash.cashToday"), money(s.cashCollectedTodayMinor, currency, locale), "check", "green"]] as const).map(([l, v, icon, tone], i) => (
            <div key={String(l)} style={{ "--i": i } as React.CSSProperties}><Stat label={String(l)} value={v} icon={icon} tone={tone} /></div>
          ))}
        </div>
      )}
      {s && s.salesByDay && s.salesByDay.length > 1 && (
        <Section title={t("dash.last14")} action={(s.delivered30 ?? 0) + (s.returned30 ?? 0) + (s.cancelled30 ?? 0) > 0 ? <p className="text-xs text-slate-500">{t("dash.outcome", { d: s.delivered30 ?? 0, r: s.returned30 ?? 0, c: s.cancelled30 ?? 0 })}</p> : undefined}>
          <AreaChart height={180} points={s.salesByDay.map((d) => ({ label: d.day.slice(5), value: d.salesMinor }))} format={(n) => money(n, currency, locale)} />
        </Section>
      )}
      <div className="grid gap-6 lg:grid-cols-2">
        <Section title={t("dash.recentOrders")} action={<Link href="/dashboard/orders" className="inline-flex items-center gap-1 text-sm font-semibold text-brand hover:underline">{t("nav.orders")}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Link>} className="section-flat [&>div:last-child]:p-0">
          <Table head={[t("orders.number"), t("admin.status"), t("checkout.total")]}>
            {recent.data?.data.map((o) => (<tr key={o.id}><Td><Link href={`/dashboard/orders/${o.id}`} className="font-mono text-brand">{o.orderNumber}</Link><div className="text-xs text-slate-500">{o.customerNameSnapshot} · {dateTime(o.createdAt, locale)}</div></Td><Td><StatusBadge status={o.status} /></Td><Td>{money(o.totalMinor, o.currency, locale)}</Td></tr>))}
          </Table>
        </Section>
        {s?.byGovernorate && s.byGovernorate.length > 0 && (
          <Section title={t("dash.byGovernorate")} className="section-flat [&>div:last-child]:p-0">
            <Table head={[t("checkout.governorate"), t("customers.orders"), t("dash.revenue")]}>
              {s.byGovernorate.map((g) => <tr key={g.governorateCode}><Td>{governorateName(g.governorateCode, locale)}</Td><Td>{g.orders}</Td><Td>{money(g.salesMinor, currency, locale)}</Td></tr>)}
            </Table>
          </Section>
        )}
        {s?.topProducts && (
          <Section title={t("dash.topProducts")} className="section-flat [&>div:last-child]:p-0">
            <Table head={[t("products.name"), t("dash.units"), t("dash.revenue")]}>
              {s.topProducts.map((p) => <tr key={p.name}><Td>{p.name}</Td><Td>{p.units}</Td><Td>{money(p.revenueMinor, currency, locale)}</Td></tr>)}
            </Table>
          </Section>
        )}
      </div>
    </div>
  );
}
