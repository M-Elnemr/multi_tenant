"use client";

import { useState } from "react";
import { dateOnly, money } from "@/lib/format";
import { useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Alert, Badge, Card, Select, Stat, Table, Td } from "../ui";

type Margin = { days: number; products: { name: string; units: number; revenueMinor: number; marginMinor?: number | null; marginPercent?: number | null }[]; totalRevenueMinor: number; totalMarginMinor: number; marginPercent: number };
type Attr = { totalRevenueMinor: number; totalOrders: number; bySource: { source: string; orders: number; revenueMinor: number }[]; aiSellerRevenueMinor: number; aiConversations: number; recoveredCarts: number; recoveredCartsMinor: number; riskyOrdersLost: number; codSavedMinor: number; loyaltyOrders: number };
type Cohort = { month: string; customers: number; back1: number; back2: number; back3: number };

/** Margin per product, customer cohorts and the CSV downloads. */
export default function ReportsPlus() {
  const { t, locale, currency } = useI18n();
  const [days, setDays] = useState("30");
  const m = useApi<Margin>(`store/reports/margin?days=${days}`);
  const c = useApi<Cohort[]>("store/reports/cohorts");
  const attr = useApi<Attr>(`store/attribution?days=${days}`);
  const pct = (n: number, d: number) => (d === 0 ? "-" : `${Math.round((n * 100) / d)}%`);
  return (
    <div className="space-y-5">
      <Card className="flex flex-wrap items-center gap-3"><span className="text-sm font-medium">{t("rp.export")}</span>
        <a href="/api/bff/store/export/orders.csv" className="rounded-xl border px-3 py-1.5 text-sm font-semibold hover:bg-slate-50">{t("rp.exportOrders")}</a>
        <a href="/api/bff/store/export/customers.csv" className="rounded-xl border px-3 py-1.5 text-sm font-semibold hover:bg-slate-50">{t("rp.exportCustomers")}</a></Card>
      {attr.data && (
        <Card className="space-y-3">
          <h2 className="font-medium">{t("rp.attribution")}</h2>
          <div className="grid gap-3 sm:grid-cols-4">
            <Stat label={t("rp.aiRevenue")} value={money(attr.data.aiSellerRevenueMinor, currency, locale)} icon="sparkles" tone="green" hint={t("rp.aiChats", { n: attr.data.aiConversations })} />
            <Stat label={t("rp.recoveredCarts")} value={money(attr.data.recoveredCartsMinor, currency, locale)} icon="box" hint={String(attr.data.recoveredCarts)} />
            <Stat label={t("rp.codSaved")} value={money(attr.data.codSavedMinor, currency, locale)} icon="billing" hint={t("rp.riskyLost", { n: attr.data.riskyOrdersLost })} />
            <Stat label={t("rp.loyaltyOrders")} value={attr.data.loyaltyOrders} icon="users" />
          </div>
          <Table head={[t("rp.source"), t("rp.units"), t("rp.revenue")]}>{attr.data.bySource.map((x) => <tr key={x.source}><Td>{t(`rp.src.${x.source}`)}</Td><Td>{x.orders}</Td><Td>{money(x.revenueMinor, currency, locale)}</Td></tr>)}</Table>
        </Card>
      )}
      <div className="flex items-center gap-3"><h2 className="font-medium">{t("rp.margin")}</h2><Select value={days} onChange={(e) => setDays(e.target.value)} className="!w-auto"><option value="7">7</option><option value="30">30</option><option value="90">90</option></Select></div>
      {m.data && (
        <>
          <div className="grid gap-3 sm:grid-cols-3"><Stat label={t("rp.revenue")} value={money(m.data.totalRevenueMinor, currency, locale)} icon="billing" /><Stat label={t("rp.margin")} value={money(m.data.totalMarginMinor, currency, locale)} icon="trending" tone="green" /><Stat label={t("rp.marginPct")} value={<span dir="ltr">{m.data.marginPercent}%</span>} icon="dashboard" /></div>
          <Alert tone="blue">{t("rp.costNote")}</Alert>
          <Table head={[t("products.name"), t("rp.units"), t("rp.revenue"), t("rp.margin"), "%"]}>
            {m.data.products.map((p) => <tr key={p.name}><Td>{p.name}</Td><Td>{p.units}</Td><Td>{money(p.revenueMinor, currency, locale)}</Td><Td>{p.marginMinor == null ? <Badge tone="amber">{t("rp.noCost")}</Badge> : money(p.marginMinor, currency, locale)}</Td><Td><span dir="ltr">{p.marginPercent ?? "-"}</span></Td></tr>)}
          </Table>
        </>
      )}
      <h2 className="font-medium">{t("rp.cohorts")}</h2>
      <p className="text-sm text-slate-500">{t("rp.cohortsHint")}</p>
      <Table head={[t("rp.month"), t("rp.newCustomers"), t("rp.back1"), t("rp.back2"), t("rp.back3")]}>
        {(c.data ?? []).map((r) => <tr key={r.month}><Td>{dateOnly(r.month, locale)}</Td><Td>{r.customers}</Td><Td>{r.back1} ({pct(r.back1, r.customers)})</Td><Td>{r.back2} ({pct(r.back2, r.customers)})</Td><Td>{r.back3} ({pct(r.back3, r.customers)})</Td></tr>)}
      </Table>
    </div>
  );
}
