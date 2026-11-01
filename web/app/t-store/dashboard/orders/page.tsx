"use client";

import Link from "next/link";
import { useState } from "react";
import { Page, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { ChannelBadge, CHANNELS } from "@/components/dashboard/channel-badge";
import { ActiveFilters, Chips, FilterGroup, MoreFilters, SearchBox } from "@/components/dashboard/filters";
import { DateRangePicker, rangeQuery, type DateRange } from "@/components/date-range";
import { Badge, ErrorText, Input, Loading, PageHeader, Pager, Select, StatusBadge, Table, Td } from "@/components/ui";
import { dateTime, money } from "@/lib/format";
import { GOVERNORATES, governorateName } from "@/lib/governorates";

type Order = { id: string; orderNumber: string; source?: string; channel?: string; whatsappConfirmedAt?: string | null; status: string; paymentStatus: string; paymentMethod: string; totalMinor: number; discountMinor?: number; couponCode?: string | null; currency: string; customerNameSnapshot: string; customerPhoneSnapshot: string; governorateCode?: string; confirmedAt?: string | null; courierName?: string; createdAt: string; riskLevel?: "LOW" | "MEDIUM" | "HIGH" | null; needsConfirmation?: boolean; itemCount?: number };
type Result = Page<Order> & { revenueMinor?: number };
const STATUSES = ["REQUESTED", "PREPARING", "SHIPPED", "ARRIVED", "RETURNED", "CANCELLED"];
const SORTS = ["NEWEST", "OLDEST", "TOTAL_DESC", "TOTAL_ASC"];

type Filters = { q: string; statuses: string[]; range: DateRange; gov: string; channel: string; payMethod: string; payStatus: string; min: string; max: string; discount: string; risk: string; branch: string; source: string; sort: string; unconfirmed: boolean };
const EMPTY: Filters = { q: "", statuses: [], range: {}, gov: "", channel: "", payMethod: "", payStatus: "", min: "", max: "", discount: "", risk: "", branch: "", source: "", sort: "NEWEST", unconfirmed: false };

export default function StoreOrders() {
  const { t, locale } = useI18n();
  const [f, setF] = useState<Filters>(EMPTY);
  const [page, setPage] = useState(1);
  const set = (p: Partial<Filters>) => { setF((x) => ({ ...x, ...p })); setPage(1); };
  const summary = useApi<{ unconfirmed: number }>("store/orders/summary");
  const branches = useApi<{ id: string; name: string }[]>("store/branches");
  const qs = new URLSearchParams({ page: String(page) });
  if (f.statuses.length) qs.set("status", f.statuses.join(","));
  if (f.gov) qs.set("governorate", f.gov);
  if (f.channel) qs.set("channel", f.channel);
  if (f.q.trim()) qs.set("q", f.q.trim());
  if (f.unconfirmed) qs.set("unconfirmed", "true");
  if (f.payMethod) qs.set("paymentMethod", f.payMethod);
  if (f.payStatus) qs.set("paymentStatus", f.payStatus);
  if (f.min) qs.set("minTotal", String(Math.round(Number(f.min) * 100)));
  if (f.max) qs.set("maxTotal", String(Math.round(Number(f.max) * 100)));
  if (f.discount) qs.set("discount", f.discount);
  if (f.risk) qs.set("risk", f.risk);
  if (f.branch) qs.set("branchId", f.branch);
  if (f.source) qs.set("source", f.source);
  if (f.sort !== "NEWEST") qs.set("sort", f.sort);
  rangeQuery(qs, f.range);
  const { data, loading, error } = useApi<Result>(`store/orders?${qs}`);
  const pending = summary.data?.unconfirmed ?? 0;
  const cur = data?.data[0]?.currency ?? "EGP";

  const more = [f.gov, f.channel, f.payMethod, f.payStatus, f.min || f.max, f.discount, f.risk, f.branch, f.source].filter(Boolean).length;
  const active = [
    ...f.statuses.map((s) => ({ key: "s" + s, label: t(`status.${s}`), onRemove: () => set({ statuses: f.statuses.filter((x) => x !== s) }) })),
    f.gov && { key: "gov", label: governorateName(f.gov, locale), onRemove: () => set({ gov: "" }) },
    f.channel && { key: "ch", label: t(`channel.${f.channel}`), onRemove: () => set({ channel: "" }) },
    f.payMethod && { key: "pm", label: t(`pay.${f.payMethod}`), onRemove: () => set({ payMethod: "" }) },
    f.payStatus && { key: "ps", label: t(`status.${f.payStatus}`), onRemove: () => set({ payStatus: "" }) },
    (f.min || f.max) && { key: "tot", label: `${f.min || "0"} – ${f.max || "∞"}`, onRemove: () => set({ min: "", max: "" }) },
    f.discount && { key: "disc", label: t(`orders.discount.${f.discount}`), onRemove: () => set({ discount: "" }) },
    f.risk && { key: "risk", label: t(`orders.risk.${f.risk}`), onRemove: () => set({ risk: "" }) },
    f.branch && { key: "br", label: branches.data?.find((b) => b.id === f.branch)?.name ?? "", onRemove: () => set({ branch: "" }) },
    f.source && { key: "src", label: t(`orders.source.${f.source}`), onRemove: () => set({ source: "" }) },
  ].filter(Boolean) as { key: string; label: string; onRemove: () => void }[];

  return (
    <>
      <PageHeader title={t("nav.orders")} subtitle={data ? t("orders.foundSummary", { n: data.meta.total, total: money(data.revenueMinor ?? 0, cur, locale) }) : undefined} />
      {pending > 0 && (
        <button onClick={() => set({ unconfirmed: !f.unconfirmed })} className={`mb-4 flex w-full items-center justify-between rounded-2xl border p-4 text-start text-sm font-semibold transition ${f.unconfirmed ? "border-amber-400 bg-amber-100" : "border-amber-200 bg-amber-50 hover:bg-amber-100"}`}>
          <span>📞 {t("confirm.banner", { n: pending })}</span><span className="text-amber-800">{f.unconfirmed ? t("filter.clear") : t("confirm.show")}</span>
        </button>
      )}
      <div className="mb-3 flex flex-wrap items-center gap-2 rounded-2xl bg-white p-3 shadow-card">
        <SearchBox value={f.q} onChange={(v) => set({ q: v })} placeholder={t("orders.searchHintMore")} />
        <DateRangePicker value={f.range} onChange={(r) => set({ range: r })} />
        <Select value={f.sort} onChange={(e) => set({ sort: e.target.value })} className="!w-auto min-h-11">{SORTS.map((s) => <option key={s} value={s}>{t(`orders.sort.${s}`)}</option>)}</Select>
        <MoreFilters count={more} onClear={() => setF({ ...EMPTY, q: f.q, statuses: f.statuses, range: f.range })}>
          <FilterGroup label={t("checkout.governorate")}><Select value={f.gov} onChange={(e) => set({ gov: e.target.value })}><option value="">{t("orders.allGovernorates")}</option>{GOVERNORATES.map((g) => <option key={g.code} value={g.code}>{locale === "ar" ? g.ar : g.en}</option>)}</Select></FilterGroup>
          <FilterGroup label={t("orders.channel")}><Chips options={CHANNELS.map((c) => ({ value: c, label: t(`channel.${c}`) }))} value={f.channel ? [f.channel] : []} onChange={(v) => set({ channel: v[0] ?? "" })} /></FilterGroup>
          <FilterGroup label={t("orders.payment")}>
            <Chips options={["CASH_ON_DELIVERY", "CARD"].map((m) => ({ value: m, label: t(`pay.${m}`) }))} value={f.payMethod ? [f.payMethod] : []} onChange={(v) => set({ payMethod: v[0] ?? "" })} />
            <div className="mt-2"><Chips options={["UNPAID", "PAID", "REFUNDED"].map((m) => ({ value: m, label: t(`status.${m}`) }))} value={f.payStatus ? [f.payStatus] : []} onChange={(v) => set({ payStatus: v[0] ?? "" })} /></div>
          </FilterGroup>
          <FilterGroup label={t("orders.totalRange")}>
            <div className="grid grid-cols-2 gap-2"><Input type="number" min={0} inputMode="decimal" placeholder={t("orders.min")} value={f.min} onChange={(e) => set({ min: e.target.value })} /><Input type="number" min={0} inputMode="decimal" placeholder={t("orders.max")} value={f.max} onChange={(e) => set({ max: e.target.value })} /></div>
          </FilterGroup>
          <FilterGroup label={t("orders.discountLabel")}><Chips options={["OFFER", "COUPON", "ANY", "NONE"].map((d) => ({ value: d, label: t(`orders.discount.${d}`) }))} value={f.discount ? [f.discount] : []} onChange={(v) => set({ discount: v[0] ?? "" })} /></FilterGroup>
          <FilterGroup label={t("orders.riskLabel")}><Chips options={["HIGH", "MEDIUM", "LOW"].map((d) => ({ value: d, label: t(`orders.risk.${d}`) }))} value={f.risk ? [f.risk] : []} onChange={(v) => set({ risk: v[0] ?? "" })} /></FilterGroup>
          <FilterGroup label={t("orders.sourceLabel")}><Chips options={["WEB", "AI_CHAT", "POS", "OPEN"].map((d) => ({ value: d, label: t(`orders.source.${d}`) }))} value={f.source ? [f.source] : []} onChange={(v) => set({ source: v[0] ?? "" })} /></FilterGroup>
          {(branches.data?.length ?? 0) > 1 && <FilterGroup label={t("orders.branch")}><Select value={f.branch} onChange={(e) => set({ branch: e.target.value })}><option value="">{t("orders.allBranches")}</option>{branches.data!.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}</Select></FilterGroup>}
        </MoreFilters>
      </div>
      <div className="mb-3"><Chips allLabel={t("orders.allStatuses")} multi options={STATUSES.map((s) => ({ value: s, label: t(`status.${s}`) }))} value={f.statuses} onChange={(v) => set({ statuses: v })} /></div>
      <ActiveFilters items={active} onClearAll={() => set({ ...EMPTY, q: f.q })} />
      <ErrorText error={error} />
      {loading && !data ? <Loading /> : data && data.data.length === 0 ? (
        <div className="rounded-2xl border border-dashed border-slate-300 bg-white/60 p-10 text-center text-sm text-slate-500">{t("orders.noneMatch")}</div>
      ) : (
        <>
          <Table head={[t("orders.number"), t("orders.customer"), t("checkout.governorate"), t("admin.status"), t("orders.payment"), t("checkout.total"), t("admin.created")]}>
            {data?.data.map((o) => (
              <tr key={o.id} className="hover:bg-slate-50">
                <Td><Link href={`/dashboard/orders/${o.id}`} className="font-mono text-brand">{o.orderNumber}</Link><span className="ms-2"><ChannelBadge channel={o.channel} /></span>{o.source === "AI_CHAT" && <span className="ms-2 rounded-full bg-violet-100 px-2 py-0.5 text-[11px] font-semibold text-violet-700">🤖 {t("ai.sourceTag")}</span>}{o.whatsappConfirmedAt && <span className="ms-2 rounded-full bg-emerald-100 px-2 py-0.5 text-[11px] font-semibold text-emerald-700">✓ {t("orders.waConfirmed")}</span>}{o.itemCount ? <div className="mt-0.5 text-xs text-slate-500">{t("orders.items", { n: o.itemCount })}</div> : null}</Td>
                <Td>{o.customerNameSnapshot}<div dir="ltr" className="text-xs text-slate-500">{o.customerPhoneSnapshot}</div></Td>
                <Td>{governorateName(o.governorateCode, locale) || "—"}</Td>
                <Td><StatusBadge status={o.status} />{(o.riskLevel === "HIGH" || o.riskLevel === "MEDIUM") && o.paymentMethod === "CASH_ON_DELIVERY" && <div className="mt-1"><Badge tone={o.riskLevel === "HIGH" ? "red" : "amber"}>{o.riskLevel === "HIGH" ? "⚠️ " + t("risk.high") : t("risk.medium")}{o.needsConfirmation && !o.confirmedAt ? " · " + t("risk.held") : ""}</Badge></div>}{o.status === "REQUESTED" && !o.confirmedAt && <div className="mt-1"><Badge tone="amber">📞 {t("confirm.unconfirmed")}</Badge></div>}</Td>
                <Td><StatusBadge status={o.paymentStatus} /> <span className="text-xs text-slate-500">{t(`pay.${o.paymentMethod}`)}</span></Td>
                <Td>{money(o.totalMinor, o.currency, locale)}{(o.discountMinor ?? 0) > 0 && <div className="text-xs font-semibold text-emerald-600">−{money(o.discountMinor!, o.currency, locale)}{o.couponCode ? ` · ${o.couponCode}` : ""}</div>}</Td>
                <Td>{dateTime(o.createdAt, locale)}</Td>
              </tr>
            ))}
          </Table>
          <Pager meta={data?.meta} onPage={setPage} />
        </>
      )}
    </>
  );
}
