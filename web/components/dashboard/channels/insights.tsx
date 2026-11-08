"use client";

import { useMemo, useState } from "react";
import { useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { AreaChart, Badge, Empty, ErrorText, KpiStrip, Section } from "@/components/ui";
import { Icon } from "@/components/icons";
import { Bar, CardSkeletons, PillTabs } from "@/components/dashboard/marketing/kit";
import { money } from "@/lib/format";
import { AccountStatus, CountryFlag, ProviderMark, STAGES, StageBadge } from "./common";

type Channel = { id: string; provider: string; name: string; country: string; label: string; status: string; lastSyncAt?: string | null; lastError?: string | null; orders: number; revenueMinor: number; newOrders: number; lateOrders: number; listings: number; listingErrors: number; lowStock: number; unlinked: number; avgOrderMinor: number };
type Attention = { type: string; accountId: string; provider: string; name: string; message?: string; count?: number };
export type Insights = {
  days: number; channels: Channel[]; shop?: { orders: number; revenueMinor: number };
  totals: { orders: number; revenueMinor: number; avgOrderMinor: number; newOrders: number; listingErrors: number; lowStock: number; listings: number; accountsInError: number };
  series: { day: string; accountId: string; orders: number; revenueMinor: number }[]; funnel: Record<string, number>;
  topProducts: { title: string; sku?: string; units: number; revenueMinor: number }[]; attention: Attention[];
};

const CURRENCY: Record<string, string> = { EG: "EGP", SA: "SAR", AE: "AED" };

/** The one-screen summary of every connected platform; with an account picked it narrows to that platform only. */
export function InsightsView({ accountId, onPick, onGo }: { accountId: string | null; onPick: (id: string) => void; onGo: (tab: "orders" | "products" | "platforms", accountId: string) => void }) {
  const { t, locale } = useI18n();
  const [days, setDays] = useState("30");
  const { data, error, loading } = useApi<Insights>(`store/integrations/insights?days=${days}${accountId ? `&accountId=${accountId}` : ""}`);
  const cur = useMemo(() => { const set = new Set((data?.channels ?? []).map((c) => CURRENCY[c.country] ?? "EGP")); return set.size === 1 ? [...set][0] : null; }, [data]);
  const points = useMemo(() => {
    const by = new Map<string, number>();
    for (const s of data?.series ?? []) by.set(s.day, (by.get(s.day) ?? 0) + s.revenueMinor);
    return [...by.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([label, value]) => ({ label, value }));
  }, [data]);
  if (error) return <ErrorText error={error} />;
  if (loading && !data) return <div className="space-y-5"><div className="skeleton h-28 rounded-[14px]" /><CardSkeletons n={2} className="grid gap-4 lg:grid-cols-2" h="h-64" /></div>;
  if (!data) return null;
  if (data.channels.length === 0) return <Empty icon="chart">{t("ch.insights.none")}</Empty>;
  const fmt = (n: number, c = cur) => (c ? money(n, c, locale) : "—");
  const maxFunnel = Math.max(1, ...STAGES.map((s) => data.funnel[s] ?? 0));
  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <PillTabs id="ch-days" tabs={[{ key: "7", label: t("ch.days7") }, { key: "30", label: t("ch.days30") }, { key: "90", label: t("ch.days90") }]} value={days} onChange={setDays} />
        {data.totals.accountsInError > 0 && <Badge tone="red">{t("ch.insights.errors", { n: data.totals.accountsInError })}</Badge>}
      </div>

      {data.attention.length > 0 && (
        <Section title={t("ch.attention")}>
          <ul className="divide-y divide-line">
            {data.attention.map((a, i) => (
              <li key={i} className="flex flex-wrap items-center justify-between gap-2 py-2.5 text-sm">
                <span className="flex items-center gap-2"><ProviderMark provider={a.provider} name={a.name} className="h-7 w-7 text-[10px]" /><span className="font-semibold">{a.name}</span><span className="text-ink-2">{t(`ch.attn.${a.type}`, { n: a.count ?? 0 })}{a.message ? `: ${a.message}` : ""}</span></span>
                <button onClick={() => onGo(a.type === "SYNC_ERROR" ? "platforms" : a.type === "LATE_ORDERS" ? "orders" : "products", a.accountId)} className="inline-flex items-center gap-1 font-semibold text-brand hover:underline">{t("ch.attn.open")}<Icon name="arrow" className="h-3.5 w-3.5 flip-rtl" /></button>
              </li>
            ))}
          </ul>
        </Section>
      )}

      <KpiStrip items={[
        { label: t("ch.insights.revenue"), value: fmt(data.totals.revenueMinor), icon: "wallet", tone: "green", hint: cur ? undefined : t("ch.insights.mixed") },
        { label: t("ch.insights.orders"), value: data.totals.orders, icon: "box", hint: data.totals.newOrders > 0 ? t("ch.insights.new", { n: data.totals.newOrders }) : undefined },
        { label: t("ch.insights.avg"), value: fmt(data.totals.avgOrderMinor), icon: "trending", tone: "amber" },
        { label: t("ch.insights.listings"), value: data.totals.listings, icon: "bag", tone: data.totals.listingErrors > 0 ? "red" : "slate", hint: data.totals.lowStock > 0 ? t("ch.insights.low", { n: data.totals.lowStock }) : undefined },
      ]} />

      {points.length > 1 && <Section title={t("ch.insights.trend")}><AreaChart points={points} format={(n) => fmt(n)} /></Section>}

      {!accountId && data.channels.length > 0 && (
        <Section title={t("ch.insights.byPlatform")}>
          <div className="grid gap-3 md:grid-cols-2">
            {data.channels.map((c) => (
              <button key={c.id} onClick={() => onPick(c.id)} className="group rounded-[16px] p-4 text-start ring-1 ring-line transition hover:ring-2 hover:ring-brand">
                <div className="flex items-center gap-3">
                  <ProviderMark provider={c.provider} name={c.name} />
                  <div className="min-w-0 flex-1"><p className="truncate font-bold">{c.name} <CountryFlag code={c.country} /></p><p className="truncate text-xs text-muted">{c.label}</p></div>
                  <AccountStatus status={c.status} />
                </div>
                <dl className="mt-3 grid grid-cols-3 gap-2 rounded-[12px] bg-canvas p-2.5 text-center text-xs text-muted">
                  <div><dd className="text-[15px] font-semibold tnum text-ink">{money(c.revenueMinor, CURRENCY[c.country] ?? "EGP", locale)}</dd>{t("ch.insights.revenue")}</div>
                  <div><dd className="text-[15px] font-semibold tnum text-ink">{c.orders}</dd>{t("ch.insights.orders")}</div>
                  <div><dd className="text-[15px] font-semibold tnum text-ink">{c.listings}</dd>{t("ch.insights.listings")}</div>
                </dl>
              </button>
            ))}
          </div>
          {data.shop && <p className="mt-4 text-sm text-muted">{t("ch.insights.shop", { orders: data.shop.orders, revenue: fmt(data.shop.revenueMinor, "EGP") })}</p>}
        </Section>
      )}

      <div className="grid gap-5 lg:grid-cols-2">
        <Section title={t("ch.insights.funnel")}>
          <ul className="space-y-2.5">
            {STAGES.map((s) => (
              <li key={s} className="flex items-center gap-3 text-sm">
                <span className="w-28 shrink-0"><StageBadge stage={s} /></span>
                <span className="flex-1"><Bar value={data.funnel[s] ?? 0} max={maxFunnel} /></span>
                <span className="w-10 text-end font-semibold tnum">{data.funnel[s] ?? 0}</span>
              </li>
            ))}
          </ul>
        </Section>
        <Section title={t("ch.insights.top")}>
          {data.topProducts.length === 0 ? <p className="text-sm text-muted">{t("ch.insights.noTop")}</p> : (
            <ol className="space-y-2.5">
              {data.topProducts.map((p, i) => (
                <li key={i} className="flex items-center gap-3 text-sm">
                  <span className="grid h-6 w-6 shrink-0 place-items-center rounded-full bg-brand-soft text-xs font-bold text-brand">{i + 1}</span>
                  <span className="min-w-0 flex-1 truncate">{p.title}{p.sku && <span className="ms-2 font-mono text-xs text-faint">{p.sku}</span>}</span>
                  <span className="shrink-0 text-xs text-muted">{t("ch.insights.units", { n: p.units })}</span>
                  <span className="w-24 shrink-0 text-end font-semibold">{fmt(p.revenueMinor)}</span>
                </li>
              ))}
            </ol>
          )}
        </Section>
      </div>

      {accountId && data.channels[0] && (
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-[18px] bg-surface p-5 ring-1 ring-line">
          <div className="text-sm"><p className="font-semibold">{t("ch.insights.health")}</p><p className="text-muted">{data.channels[0].lastSyncAt ? t("ch.insights.synced", { when: new Date(data.channels[0].lastSyncAt).toLocaleString(locale === "ar" ? "ar-EG" : "en-GB") }) : t("ch.insights.neverSynced")}</p></div>
          <div className="flex gap-5 text-center text-xs text-muted">
            <div><p className="font-display text-lg font-semibold tnum text-ink">{data.channels[0].unlinked}</p>{t("ch.insights.unlinked")}</div>
            <div><p className="font-display text-lg font-semibold tnum text-ink">{data.channels[0].lateOrders}</p>{t("ch.insights.late")}</div>
            <div><p className="font-display text-lg font-semibold tnum text-ink">{data.channels[0].listingErrors}</p>{t("ch.insights.listErrors")}</div>
          </div>
        </div>
      )}
    </div>
  );
}
