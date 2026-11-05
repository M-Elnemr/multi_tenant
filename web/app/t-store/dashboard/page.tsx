"use client";

import Link from "next/link";
import { useState } from "react";
import { Page, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon, type IconName } from "@/components/icons";
import { AnimatedNumber, AreaChart, AttentionRow, Avatar, BarChart, Delta, DonutChart, Empty, ErrorText, KpiStrip, Loading, Section, SectionLink, ShareBars, Sparkline, StatusBadge, Segmented, STATUS_TONE, TONE_HEX } from "@/components/ui";
import { dateTime, money, num } from "@/lib/format";
import { governorateName } from "@/lib/governorates";

type Summary = { ordersToday: number; salesTodayMinor: number; ordersThisMonth: number; salesThisMonthMinor: number; averageOrderValueMinor: number; lowStockCount: number; cashToCollectMinor: number; cashToCollectCount: number; cashCollectedTodayMinor: number; ordersByStatus: Record<string, number>; unconfirmedCount?: number; openReturnsCount?: number; delivered30?: number; returned30?: number; cancelled30?: number; salesByDay?: { day: string; orders: number; salesMinor: number }[]; byGovernorate?: { governorateCode: string; orders: number; salesMinor: number }[]; topProducts?: { name: string; units: number; revenueMinor: number }[] };
type Order = { id: string; orderNumber: string; status: string; totalMinor: number; currency: string; customerNameSnapshot: string; createdAt: string };
type Usage = { usedThisMonth: number; monthlyCap: number; unanswered: number };
type Channel = { id: string; platform: string; name?: string; status: string };

const pct = (a: number, b: number) => (b > 0 ? ((a - b) / b) * 100 : null);

export default function StoreOverview() {
  const { t, locale, currency, timezone } = useI18n();
  const { me, can, canSee } = useMe();
  const summary = useApi<Summary>(can("report.read") ? "store/reports/summary" : null);
  const recent = useApi<Page<Order>>(can("order.read") ? "store/orders?pageSize=6" : null);
  const products = useApi<{ meta?: { total: number } }>(can("product.read") ? "store/products?pageSize=1" : null);
  const aiOn = canSee("ai");
  const usage = useApi<Usage>(aiOn ? "store/ai/usage" : null);
  const channels = useApi<Channel[]>(aiOn ? "store/ai/channels" : null);
  const branding = useApi<{ logoFileId?: string }>("tenant/branding");
  const [metric, setMetric] = useState<"sales" | "orders">("sales");
  const [yKey] = useState(() => new Date(Date.now() - 86400000).toLocaleDateString("en-CA", { timeZone: timezone }));
  if (summary.loading && !summary.data) return <Loading />;
  const s = summary.data;
  const fmt = (n: number) => money(n, currency, locale);
  const days = s?.salesByDay ?? [];
  // compare today's sales with yesterday's entry (by the shop's calendar day), only when yesterday sold something
  const yesterday = days.find((d) => d.day === yKey);
  const vsYesterday = s && yesterday && yesterday.salesMinor > 0 ? pct(s.salesTodayMinor, yesterday.salesMinor) : null;
  const firstHalf = days.slice(0, Math.floor(days.length / 2)).reduce((a, d) => a + d.salesMinor, 0);
  const secondHalf = days.slice(Math.floor(days.length / 2)).reduce((a, d) => a + d.salesMinor, 0);
  const weekTrend = days.length >= 4 ? pct(secondHalf, firstHalf) : null;

  // Onboarding: shown until the shop has products, a logo, a channel and a first order (all real counts).
  const productCount = products.data?.meta?.total;
  const orderCount = recent.data?.meta?.total;
  const steps: { done: boolean; label: string; hint: string; href: string; icon: IconName }[] = [
    { done: (productCount ?? 1) > 0, label: t("onb.products"), hint: t("onb.productsHint"), href: "/dashboard/products", icon: "box" },
    { done: !!branding.data?.logoFileId, label: t("onb.look"), hint: t("onb.lookHint"), href: "/dashboard/settings?tab=look", icon: "palette" },
    { done: !aiOn || (channels.data?.length ?? 1) > 0, label: t("onb.channels"), hint: t("onb.channelsHint"), href: "/dashboard/ai?tab=channels", icon: "chat" },
    { done: (orderCount ?? 1) > 0, label: t("onb.order"), hint: t("onb.orderHint"), href: "/?edit=1", icon: "bag" },
  ];
  const doneCount = steps.filter((x) => x.done).length;
  const showOnboarding = can("settings.manage") && doneCount < steps.length && products.data !== undefined;

  const statusSegs = s ? Object.entries(s.ordersByStatus ?? {}).filter(([, v]) => v > 0).map(([k, v]) => ({ label: t(`status.${k}`) === `status.${k}` ? k : t(`status.${k}`), value: v, color: TONE_HEX[STATUS_TONE[k] ?? "slate"] })) : [];
  const statusTotal = statusSegs.reduce((a, x) => a + x.value, 0);

  if (!s && !can("report.read")) {
    // employees without reports still get a useful start screen
    return (
      <div className="space-y-6">
        <Greeting name={me?.firstName} />
        {recent.data ? <RecentOrders orders={recent.data.data} /> : <Empty icon="dashboard" title={t("dash.limitedTitle")}>{t("dash.limitedHint")}</Empty>}
      </div>
    );
  }

  return (
    <div className="space-y-5">
      <ErrorText error={summary.error} />
      {s && (
        <div className="grid gap-5 xl:grid-cols-[1.55fr_1fr]">
          {/* Command hero: today in display type, the 14-day pulse underneath */}
          <section className="hero-card flex animate-fade-up flex-col p-6 sm:p-7">
            <div className="flex flex-wrap items-start justify-between gap-4">
              <div>
                <p className="text-[13px] font-medium text-white/60">{t("dash.hello")}, {me?.firstName}</p>
                <p className="mt-4 flex items-center gap-2 text-[13px] font-medium text-white/70"><span className="h-1.5 w-1.5 rounded-full bg-emerald-400 live-dot" />{t("dash.salesToday")}</p>
                <p className="mt-1 font-display text-[2.6rem] font-semibold leading-none tracking-tight tnum sm:text-[3.4rem]"><AnimatedNumber value={s.salesTodayMinor} format={fmt} /></p>
                <div className="mt-3 flex flex-wrap items-center gap-2 text-xs text-white/60">
                  {vsYesterday != null && <Delta value={vsYesterday} className="!bg-white/10 !text-white" />}
                  {vsYesterday != null && <span>{t("dash.vsYesterday")}</span>}
                </div>
              </div>
              <div className="flex gap-2">
                {[[s.ordersToday, t("dash.ordersToday")], [s.ordersThisMonth, t("dash.ordersMonth")]].map(([v, l]) => (
                  <div key={String(l)} className="min-w-[6.5rem] rounded-[12px] bg-white/[.06] px-4 py-3 ring-1 ring-inset ring-white/10">
                    <p className="font-display text-2xl font-semibold leading-none tnum"><AnimatedNumber value={Number(v)} /></p>
                    <p className="mt-1.5 text-[11.5px] text-white/55">{l}</p>
                  </div>
                ))}
              </div>
            </div>
            <div className="flex-1" />
            {days.length > 1 && days.some((d) => d.salesMinor > 0) && <div className="-mx-1 mt-6 opacity-90"><Sparkline values={days.map((d) => d.salesMinor)} height={56} color="rgb(255 255 255 / .85)" /></div>}
            {can("settings.manage") && (
              <div className="mt-6 flex flex-wrap gap-2">
                <a href="/?edit=1" className="press inline-flex h-9 items-center gap-2 rounded-[10px] bg-[#fff] px-3.5 text-[13px] font-semibold text-[#101218] transition hover:bg-[#fff]/90"><Icon name="edit" className="h-4 w-4" />{t("site.customize")}</a>
                <Link href="/dashboard/products/new" className="press inline-flex h-9 items-center gap-2 rounded-[10px] bg-white/10 px-3.5 text-[13px] font-semibold text-white ring-1 ring-inset ring-white/15 transition hover:bg-white/15"><Icon name="plus" className="h-4 w-4" />{t("dash.addProduct")}</Link>
                <Link href="/dashboard/pos" className="press inline-flex h-9 items-center gap-2 rounded-[10px] bg-white/10 px-3.5 text-[13px] font-semibold text-white ring-1 ring-inset ring-white/15 transition hover:bg-white/15"><Icon name="receipt" className="h-4 w-4" />{t("dash.openPos")}</Link>
              </div>
            )}
          </section>

          {/* Right column: AI seller activity, or onboarding while the shop is new */}
          <div className="grid content-start gap-5">
            {showOnboarding ? (
              <Section title={t("onb.title")} subtitle={t("onb.progress", { done: doneCount, all: steps.length })} icon="sparkles" flush>
                <div className="h-1 bg-[color-mix(in_srgb,var(--ink)_6%,transparent)]"><div className="grow-x h-full bg-brand" style={{ width: `${(doneCount / steps.length) * 100}%` }} /></div>
                <ul className="divide-y divide-line">
                  {steps.map((st) => (
                    <li key={st.label}>
                      <Link href={st.href} className="group flex items-center gap-3 px-5 py-3 transition hover:bg-surface-2">
                        <span className={`grid h-7 w-7 shrink-0 place-items-center rounded-full ${st.done ? "bg-emerald-500 text-white" : "border border-dashed border-line-strong text-faint"}`}><Icon name={st.done ? "check" : st.icon} className="h-3.5 w-3.5" /></span>
                        <span className="min-w-0 flex-1"><span className={`block text-[13.5px] font-semibold ${st.done ? "text-muted line-through decoration-line-strong" : "text-ink"}`}>{st.label}</span>{!st.done && <span className="block truncate text-xs text-muted">{st.hint}</span>}</span>
                        {!st.done && <Icon name="chevron" className="h-4 w-4 text-faint transition-transform group-hover:translate-x-0.5 flip-rtl" />}
                      </Link>
                    </li>
                  ))}
                </ul>
              </Section>
            ) : aiOn && usage.data ? (
              <Section title={t("dash.aiSeller")} subtitle={t("dash.aiSellerHint")} icon="sparkles" action={<SectionLink href="/dashboard/conversations">{t("nav.inbox")}</SectionLink>}>
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <p className="text-xs text-muted">{t("dash.aiReplies")}</p>
                    <p className="mt-1 font-display text-2xl font-semibold text-ink tnum"><AnimatedNumber value={usage.data.usedThisMonth} /></p>
                    {usage.data.monthlyCap > 0 && (
                      <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-[color-mix(in_srgb,var(--ink)_6%,transparent)]"><div className="grow-x h-full rounded-full bg-brand" style={{ width: `${Math.min(100, (usage.data.usedThisMonth / usage.data.monthlyCap) * 100)}%` }} /></div>
                    )}
                    {usage.data.monthlyCap > 0 && <p className="mt-1 text-[11px] text-faint tnum">{t("dash.aiOfCap", { n: usage.data.monthlyCap.toLocaleString() })}</p>}
                  </div>
                  <Link href="/dashboard/questions" className="group rounded-[12px] bg-surface-2 p-3 ring-1 ring-inset ring-line transition hover:ring-line-strong">
                    <p className="text-xs text-muted">{t("dash.aiUnanswered")}</p>
                    <p className={`mt-1 font-display text-2xl font-semibold tnum ${usage.data.unanswered > 0 ? "text-amber-600" : "text-ink"}`}>{usage.data.unanswered}</p>
                    <p className="mt-1 flex items-center gap-1 text-[11px] font-semibold text-muted group-hover:text-ink">{t("dash.aiTeach")}<Icon name="chevron" className="h-3 w-3 flip-rtl" /></p>
                  </Link>
                </div>
                <div className="mt-4 flex flex-wrap gap-1.5">
                  {(channels.data ?? []).slice(0, 6).map((c, i) => <span key={c.id ?? i} className="inline-flex items-center gap-1.5 rounded-full bg-surface-2 px-2.5 py-1 text-[11.5px] font-medium text-ink-2 ring-1 ring-inset ring-line"><span className={`h-1.5 w-1.5 rounded-full ${c.status === "CONNECTED" || c.status === "ACTIVE" ? "bg-emerald-500" : "bg-slate-400"}`} />{c.name || t(`channel.${c.platform}`)}</span>)}
                  {channels.data && channels.data.length === 0 && <Link href="/dashboard/ai?tab=channels" className="text-xs font-semibold text-brand hover:underline">{t("onb.channels")} →</Link>}
                </div>
              </Section>
            ) : null}
            <Section title={t("dash.cashTitle")} icon="wallet">
              <div className="grid grid-cols-2 gap-4">
                <div><p className="text-xs text-muted">{t("dash.cashToCollect")}</p><p className="mt-1 font-display text-xl font-semibold text-ink tnum">{fmt(s.cashToCollectMinor)}</p><p className="text-[11px] text-faint">{t("dash.cashOrders", { n: s.cashToCollectCount })}</p></div>
                <div><p className="text-xs text-muted">{t("dash.cashToday")}</p><p className="mt-1 font-display text-xl font-semibold text-emerald-600 tnum">{fmt(s.cashCollectedTodayMinor)}</p></div>
              </div>
            </Section>
          </div>
        </div>
      )}

      {s && (
        <AttentionRow items={[
          { href: "/dashboard/orders", count: s.unconfirmedCount ?? 0, label: t("dash.attnConfirm"), tone: "amber" },
          { href: "/dashboard/returns", count: s.openReturnsCount ?? 0, label: t("dash.attnReturns"), tone: "blue" },
          { href: "/dashboard/inventory", count: s.lowStockCount, label: t("dash.attnLowStock"), tone: "red" },
          { href: "/dashboard/questions", count: usage.data?.unanswered ?? 0, label: t("dash.aiUnanswered"), tone: "amber" },
        ]} />
      )}

      {s && (
        <KpiStrip items={[
          { label: t("dash.salesMonth"), value: fmt(s.salesThisMonthMinor), icon: "billing", tone: "brand", delta: weekTrend, hint: weekTrend != null ? t("dash.trendHint") : undefined },
          { label: t("dash.aov"), value: fmt(s.averageOrderValueMinor), icon: "cart", tone: "green" },
          { label: t("dash.delivered30"), value: s.delivered30 ?? 0, icon: "truck", tone: "violet", hint: t("dash.returnedCancelled", { r: s.returned30 ?? 0, c: s.cancelled30 ?? 0 }) },
          { label: t("dash.products"), value: productCount ?? "—", icon: "box", tone: "amber", href: "/dashboard/products", hint: s.lowStockCount ? t("dash.lowStock", { n: s.lowStockCount }) : undefined },
        ]} />
      )}

      {s && (
        <div className="grid gap-5 lg:grid-cols-[1.6fr_1fr]">
          <Section title={t("dash.last14")} subtitle={metric === "sales" ? fmt(days.reduce((a, d) => a + d.salesMinor, 0)) : t("dash.nOrders", { n: num(days.reduce((a, d) => a + d.orders, 0), locale) })}
            action={<Segmented size="sm" value={metric} onChange={setMetric} tabs={[{ key: "sales", label: t("dash.metricSales") }, { key: "orders", label: t("dash.metricOrders") }]} />}>
            <div className="pt-6">
              {days.length < 2 || !days.some((d) => d.orders > 0) ? <Empty compact icon="chart" title={t("dash.quietTitle")}>{t("dash.quietHint")}</Empty> : metric === "sales"
                ? <AreaChart key="s" height={200} points={days.map((d) => ({ label: d.day.slice(5), value: d.salesMinor }))} format={fmt} />
                : <BarChart key="o" height={176} points={days.map((d) => ({ label: d.day.slice(5), value: d.orders }))} />}
            </div>
          </Section>
          <Section title={t("dash.byStatus")} subtitle={t("dash.nOrders", { n: num(statusTotal, locale) })}>
            {statusSegs.length ? <DonutChart segments={statusSegs} size={132} center={<div><p className="font-display text-xl font-semibold text-ink tnum">{num(statusTotal, locale)}</p><p className="text-[11px] text-muted">{t("dash.metricOrders")}</p></div>} /> : <Empty compact icon="cart">{t("dash.noOrdersYet")}</Empty>}
          </Section>
        </div>
      )}

      <div className="grid gap-5 lg:grid-cols-3">
        <div className="lg:col-span-2">{recent.data && <RecentOrders orders={recent.data.data} />}</div>
        <div className="grid content-start gap-5">
          {s?.topProducts && s.topProducts.length > 0 && (
            <Section title={t("dash.topProducts")} icon="star">
              <ShareBars rows={s.topProducts.slice(0, 5).map((p) => ({ label: p.name, value: p.revenueMinor, sub: t("dash.nUnits", { n: p.units }) }))} format={fmt} />
            </Section>
          )}
          {s?.byGovernorate && s.byGovernorate.length > 0 && (
            <Section title={t("dash.byGovernorate")} icon="pin">
              <ShareBars rows={s.byGovernorate.slice(0, 5).map((g) => ({ label: governorateName(g.governorateCode, locale), value: g.orders, sub: fmt(g.salesMinor) }))} />
            </Section>
          )}
        </div>
      </div>
    </div>
  );
}

function Greeting({ name }: { name?: string }) {
  const { t } = useI18n();
  return <div className="animate-fade-up"><p className="eyebrow">{t("dash.controlPanel")}</p><h1 className="mt-1 font-display text-[2rem] font-semibold text-ink">{t("dash.hello")}, {name}</h1></div>;
}

function RecentOrders({ orders }: { orders: Order[] }) {
  const { t, locale } = useI18n();
  return (
    <Section title={t("dash.recentOrders")} icon="cart" action={<SectionLink href="/dashboard/orders">{t("dash.viewAll")}</SectionLink>} flush>
      {orders.length === 0 ? <div className="p-5"><Empty compact icon="cart" title={t("dash.noOrdersYet")}>{t("dash.noOrdersHint")}</Empty></div> : (
        <ul className="divide-y divide-line">
          {orders.map((o, i) => (
            <li key={o.id} style={{ animation: `fade-up .4s ${i * 0.04}s both` }}>
              <Link href={`/dashboard/orders/${o.id}`} className="group flex items-center gap-3 px-5 py-3 transition hover:bg-surface-2">
                <Avatar name={o.customerNameSnapshot || "?"} className="h-9 w-9 text-xs" />
                <div className="min-w-0 flex-1">
                  <p className="truncate text-[13.5px] font-semibold text-ink">{o.customerNameSnapshot}</p>
                  <p className="truncate text-xs text-muted"><span className="font-mono" dir="ltr">#{o.orderNumber}</span> · {dateTime(o.createdAt, locale)}</p>
                </div>
                <StatusBadge status={o.status} />
                <span className="hidden w-28 text-end text-[13.5px] font-semibold text-ink tnum sm:block">{money(o.totalMinor, o.currency, locale)}</span>
                <Icon name="chevron" className="h-4 w-4 text-faint opacity-0 transition group-hover:opacity-100 flip-rtl" />
              </Link>
            </li>
          ))}
        </ul>
      )}
    </Section>
  );
}
