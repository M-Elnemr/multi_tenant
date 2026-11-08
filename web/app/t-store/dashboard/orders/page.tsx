"use client";

import { useRouter } from "next/navigation";
import { useCallback, useState } from "react";
import { Page, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { useToast } from "@/components/toast";
import { ChannelBadge, ChannelMark, CHANNELS } from "@/components/dashboard/channel-badge";
import { useNavTitle } from "@/components/dashboard/section";
import { ActiveChips, Bar, Cell, Chip, ChipRow, DataTable, FilterButton, FilterGroup, LinkButton, MenuButton, Notice, Row, RowMenu, SearchField } from "@/components/dashboard/sell/kit";
import { DateRangePicker, rangeQuery, type DateRange } from "@/components/date-range";
import { Badge, Empty, ErrorText, Input, KpiStrip, PageHeader, Pager, Select, StatusBadge } from "@/components/ui";
import { dateTime, money } from "@/lib/format";
import { GOVERNORATES, governorateName } from "@/lib/governorates";
import { whatsappLink } from "@/lib/whatsapp";

type Order = { id: string; orderNumber: string; source?: string; channel?: string; whatsappConfirmedAt?: string | null; status: string; paymentStatus: string; paymentMethod: string; totalMinor: number; discountMinor?: number; couponCode?: string | null; currency: string; customerNameSnapshot: string; customerPhoneSnapshot: string; governorateCode?: string; confirmedAt?: string | null; courierName?: string; createdAt: string; riskLevel?: "LOW" | "MEDIUM" | "HIGH" | null; needsConfirmation?: boolean; itemCount?: number };
type Result = Page<Order> & { revenueMinor?: number };
const STATUSES = ["REQUESTED", "PREPARING", "SHIPPED", "ARRIVED", "RETURNED", "CANCELLED"];
const SORTS = ["NEWEST", "OLDEST", "TOTAL_DESC", "TOTAL_ASC"];

type Filters = { q: string; statuses: string[]; range: DateRange; gov: string; channel: string; payMethod: string; payStatus: string; min: string; max: string; discount: string; risk: string; branch: string; source: string; sort: string; unconfirmed: boolean };
const EMPTY: Filters = { q: "", statuses: [], range: {}, gov: "", channel: "", payMethod: "", payStatus: "", min: "", max: "", discount: "", risk: "", branch: "", source: "", sort: "NEWEST", unconfirmed: false };

/** Every order of the shop: what needs a call, quick status chips, search, a filter panel, and a table that opens each order. */
export default function StoreOrders() {
  const { t, locale } = useI18n();
  const router = useRouter();
  const toast = useToast();
  const title = useNavTitle(t("nav.orders"));
  const [f, setF] = useState<Filters>(EMPTY);
  const [page, setPage] = useState(1);
  const set = (p: Partial<Filters>) => { setF((x) => ({ ...x, ...p })); setPage(1); };
  const setQ = useCallback((q: string) => { setF((x) => ({ ...x, q })); setPage(1); }, []);
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
  const n = (x: number) => x.toLocaleString(locale === "ar" ? "ar-EG" : "en-US");

  const more = [f.gov, f.channel, f.payMethod, f.payStatus, f.min || f.max, f.discount, f.risk, f.branch, f.source].filter(Boolean).length;
  const active = [
    ...f.statuses.map((s) => ({ key: "s" + s, label: t(`status.${s}`), onRemove: () => set({ statuses: f.statuses.filter((x) => x !== s) }) })),
    f.unconfirmed && { key: "unc", label: t("confirm.unconfirmed"), onRemove: () => set({ unconfirmed: false }) },
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
  const anyFilter = active.length > 0 || !!f.q.trim() || !!f.range.from || !!f.range.to;
  const clearAll = () => set({ ...EMPTY, q: f.q });

  const pick = (key: keyof Filters, v: string) => set({ [key]: f[key] === v ? "" : v } as Partial<Filters>);
  const copy = (text: string) => { void navigator.clipboard?.writeText(text).then(() => toast(t("common.copied"))); };

  return (
    <div className="space-y-5">
      <PageHeader eyebrow={t("sell.eyebrow")} title={title} subtitle={t("sell.orders.subtitle")}
        actions={<><LinkButton href="/dashboard/returns" icon="return">{t("nav.returns")}</LinkButton><LinkButton href="/dashboard/shipping" icon="truck">{t("sell.shipments")}</LinkButton></>} />

      <KpiStrip items={[
        { label: t("sell.kpi.orders"), value: data ? n(data.meta.total) : "–", icon: "box", tone: "brand", hint: anyFilter ? t("sell.kpi.matching") : undefined },
        { label: t("sell.kpi.revenue"), value: data ? money(data.revenueMinor ?? 0, cur, locale) : "–", icon: "wallet", tone: "green" },
        { label: t("sell.kpi.avg"), value: data && data.meta.total ? money(Math.round((data.revenueMinor ?? 0) / data.meta.total), cur, locale) : "–", icon: "chart", tone: "slate" },
        { label: t("confirm.unconfirmed"), value: summary.data ? n(pending) : "–", icon: "phone", tone: pending ? "amber" : "slate" },
      ]} />

      {pending > 0 && (
        <Notice icon="phone" action={<button type="button" onClick={() => set({ unconfirmed: !f.unconfirmed })} className="rounded-[9px] bg-amber-100 px-3 py-1.5 text-[12.5px] font-semibold text-amber-900 ring-1 ring-amber-600/20 transition hover:bg-amber-200">{f.unconfirmed ? t("filter.clear") : t("confirm.show")}</button>}>
          <span className="font-semibold">{t("confirm.banner", { n: pending })}</span>
        </Notice>
      )}

      <div className="space-y-3">
        <Bar>
          <SearchField value={f.q} onChange={setQ} placeholder={t("orders.searchHintMore")} />
          <DateRangePicker value={f.range} onChange={(r) => set({ range: r })} />
          <MenuButton icon="sort" label={t("sell.sort")} text={t(`orders.sort.${f.sort}`)} items={SORTS.map((s) => ({ label: t(`orders.sort.${s}`), checked: f.sort === s, onSelect: () => set({ sort: s }) }))} />
          <FilterButton count={more} onClear={() => setF({ ...EMPTY, q: f.q, statuses: f.statuses, range: f.range, unconfirmed: f.unconfirmed, sort: f.sort })}>
            <FilterGroup label={t("orders.channel")}>{CHANNELS.map((c) => <Chip key={c} on={f.channel === c} onClick={() => pick("channel", c)}><ChannelMark channel={c} className="h-3.5 w-3.5" />{t(`channel.${c}`)}</Chip>)}</FilterGroup>
            <FilterGroup label={t("orders.payment")}>
              {["CASH_ON_DELIVERY", "CARD"].map((m) => <Chip key={m} on={f.payMethod === m} onClick={() => pick("payMethod", m)}>{t(`pay.${m}`)}</Chip>)}
              <span className="basis-full" />
              {["UNPAID", "PAID", "REFUNDED"].map((m) => <Chip key={m} on={f.payStatus === m} onClick={() => pick("payStatus", m)}>{t(`status.${m}`)}</Chip>)}
            </FilterGroup>
            <FilterGroup label={t("orders.sourceLabel")}>{["WEB", "AI_CHAT", "POS", "OPEN"].map((d) => <Chip key={d} on={f.source === d} onClick={() => pick("source", d)}>{d === "AI_CHAT" && <Icon name="sparkles" className="h-3.5 w-3.5" />}{t(`orders.source.${d}`)}</Chip>)}</FilterGroup>
            <FilterGroup label={t("orders.discountLabel")}>{["OFFER", "COUPON", "ANY", "NONE"].map((d) => <Chip key={d} on={f.discount === d} onClick={() => pick("discount", d)}>{t(`orders.discount.${d}`)}</Chip>)}</FilterGroup>
            <FilterGroup label={t("orders.riskLabel")}>{["HIGH", "MEDIUM", "LOW"].map((d) => <Chip key={d} on={f.risk === d} onClick={() => pick("risk", d)}><span className={`h-2 w-2 rounded-full ${d === "HIGH" ? "bg-red-500" : d === "MEDIUM" ? "bg-amber-500" : "bg-emerald-500"}`} />{t(`orders.risk.${d}`)}</Chip>)}</FilterGroup>
            <FilterGroup label={t("orders.totalRange")}>
              <div className="grid w-full grid-cols-2 gap-2"><Input type="number" min={0} inputMode="decimal" placeholder={t("orders.min")} value={f.min} onChange={(e) => set({ min: e.target.value })} /><Input type="number" min={0} inputMode="decimal" placeholder={t("orders.max")} value={f.max} onChange={(e) => set({ max: e.target.value })} /></div>
            </FilterGroup>
            <FilterGroup label={t("checkout.governorate")}><Select value={f.gov} onChange={(e) => set({ gov: e.target.value })}><option value="">{t("orders.allGovernorates")}</option>{GOVERNORATES.map((g) => <option key={g.code} value={g.code}>{locale === "ar" ? g.ar : g.en}</option>)}</Select></FilterGroup>
            {(branches.data?.length ?? 0) > 1 && <FilterGroup label={t("orders.branch")}><Select value={f.branch} onChange={(e) => set({ branch: e.target.value })}><option value="">{t("orders.allBranches")}</option>{branches.data!.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}</Select></FilterGroup>}
          </FilterButton>
        </Bar>
        <ChipRow>
          <Chip on={f.statuses.length === 0} onClick={() => set({ statuses: [] })}>{t("orders.allStatuses")}</Chip>
          {STATUSES.map((s) => <Chip key={s} on={f.statuses.includes(s)} onClick={() => set({ statuses: f.statuses.includes(s) ? f.statuses.filter((x) => x !== s) : [...f.statuses, s] })}>{t(`status.${s}`)}</Chip>)}
        </ChipRow>
        <ActiveChips items={active} onClearAll={clearAll} />
      </div>

      <ErrorText error={error} />
      {data && data.data.length === 0 ? (
        <Empty icon="box" title={anyFilter ? t("orders.noneMatch") : t("sell.orders.noneTitle")}
          action={anyFilter ? <button type="button" onClick={() => setF({ ...EMPTY })} className="h-10 rounded-[11px] bg-ink px-4 text-[13px] font-semibold text-surface">{t("filter.clearAll")}</button> : <LinkButton href="/dashboard/pos" icon="receipt">{t("pos.title")}</LinkButton>}>
          {anyFilter ? t("sell.orders.noneHint") : t("sell.orders.noneBody")}
        </Empty>
      ) : (
        <>
          <DataTable loading={loading && !data} cols={[{ label: t("orders.number") }, { label: t("orders.customer") }, { label: t("checkout.governorate") }, { label: t("admin.status") }, { label: t("orders.payment") }, { label: t("checkout.total"), end: true }, { label: t("admin.created") }, { label: "", end: true }]}>
            {data?.data.map((o) => (
              <Row key={o.id} onOpen={() => router.push(`/dashboard/orders/${o.id}`)}>
                <Cell>
                  <span className="font-mono text-[13px] font-semibold text-ink" dir="ltr">{o.orderNumber}</span>
                  <div className="mt-1 flex flex-wrap items-center justify-end gap-1 sm:justify-start">
                    <ChannelBadge channel={o.channel} />
                    {o.source === "AI_CHAT" && <span className="inline-flex items-center gap-1 rounded-full bg-[linear-gradient(135deg,rgb(124_92_255/.12),rgb(34_211_238/.12))] px-2 py-0.5 text-[11px] font-semibold text-violet-700 ring-1 ring-violet-600/15"><Icon name="sparkles" className="h-3 w-3" />{t("ai.sourceTag")}</span>}
                    {o.whatsappConfirmedAt && <span className="inline-flex items-center gap-1 rounded-full bg-emerald-50 px-2 py-0.5 text-[11px] font-semibold text-emerald-700 ring-1 ring-emerald-600/15"><Icon name="check" className="h-3 w-3" />{t("orders.waConfirmed")}</span>}
                  </div>
                  {o.itemCount ? <div className="mt-0.5 text-[12px] text-faint">{t("orders.items", { n: o.itemCount })}</div> : null}
                </Cell>
                <Cell><span className="font-medium text-ink">{o.customerNameSnapshot}</span><div dir="ltr" className="text-[12px] text-muted tnum">{o.customerPhoneSnapshot}</div></Cell>
                <Cell className="text-ink-2">{governorateName(o.governorateCode, locale) || "—"}</Cell>
                <Cell>
                  <div className="flex flex-col items-end gap-1 sm:items-start">
                    <StatusBadge status={o.status} />
                    {(o.riskLevel === "HIGH" || o.riskLevel === "MEDIUM") && o.paymentMethod === "CASH_ON_DELIVERY" && <Badge tone={o.riskLevel === "HIGH" ? "red" : "amber"}>{o.riskLevel === "HIGH" ? t("risk.high") : t("risk.medium")}{o.needsConfirmation && !o.confirmedAt ? " · " + t("risk.held") : ""}</Badge>}
                    {o.status === "REQUESTED" && !o.confirmedAt && <Badge tone="amber">{t("confirm.unconfirmed")}</Badge>}
                  </div>
                </Cell>
                <Cell><div className="flex flex-col items-end gap-0.5 sm:items-start"><StatusBadge status={o.paymentStatus} /><span className="text-[12px] text-muted">{t(`pay.${o.paymentMethod}`)}</span></div></Cell>
                <Cell end><span className="font-semibold text-ink tnum">{money(o.totalMinor, o.currency, locale)}</span>{(o.discountMinor ?? 0) > 0 && <div className="text-[12px] font-semibold text-emerald-600 tnum">−{money(o.discountMinor!, o.currency, locale)}{o.couponCode ? ` · ${o.couponCode}` : ""}</div>}</Cell>
                <Cell className="whitespace-nowrap text-[12.5px] text-muted tnum">{dateTime(o.createdAt, locale)}</Cell>
                <Cell end>
                  <RowMenu label={t("inbox.more")} items={[
                    { label: t("sell.openOrder"), icon: "eye", onSelect: () => router.push(`/dashboard/orders/${o.id}`) },
                    { label: t("print.invoice"), icon: "receipt", onSelect: () => router.push(`/dashboard/orders/${o.id}/print?doc=invoice`) },
                    { label: t("print.label"), icon: "tag", onSelect: () => router.push(`/dashboard/orders/${o.id}/print?doc=label`) },
                    "divider",
                    { label: t("sell.whatsappCustomer"), icon: "chat", onSelect: () => window.open(whatsappLink(o.customerPhoneSnapshot, t("orders.waHello", { name: o.customerNameSnapshot, number: o.orderNumber })), "_blank", "noopener") },
                    { label: t("sell.copyNumber"), icon: "file", onSelect: () => copy(o.orderNumber) },
                  ]} />
                </Cell>
              </Row>
            ))}
          </DataTable>
          <Pager meta={data?.meta} onPage={setPage} />
        </>
      )}
    </div>
  );
}
