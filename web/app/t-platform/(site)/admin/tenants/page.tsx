"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { Page, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { planLabel } from "@/components/dashboard/pay-screen";
import { Icon } from "@/components/icons";
import { Avatar, Empty, ErrorText, Field, Input, Loading, PageHeader, Pager, Select, StatusBadge, Tabs } from "@/components/ui";
import { dateOnly } from "@/lib/format";
import { localPhone } from "@/lib/phone";
import { whatsappLink } from "@/lib/whatsapp";

type Row = {
  id: string; slug: string; name: string; tenantType: string; status: string; createdAt: string; subscriptionStatus?: string; planCode?: string;
  currentPeriodStart?: string; currentPeriodEnd?: string; primaryHost?: string; ownerName?: string; ownerPhone?: string; hasPayRequest: boolean; pointsUsedMinor?: number;
};

export const daysLeft = (iso?: string) => (iso ? Math.ceil((new Date(iso).getTime() - Date.now()) / 86_400_000) : null);

function TenantCard({ r }: { r: Row }) {
  const { t, locale } = useI18n();
  const left = daysLeft(r.currentPeriodEnd);
  const total = r.currentPeriodStart && r.currentPeriodEnd ? Math.max(1, Math.round((new Date(r.currentPeriodEnd).getTime() - new Date(r.currentPeriodStart).getTime()) / 86_400_000)) : 30;
  const pct = left === null ? 0 : Math.min(100, Math.max(0, (left / total) * 100));
  const tone = left === null ? "bg-slate-300" : left <= 0 ? "bg-red-500" : left <= 7 ? "bg-amber-500" : "bg-emerald-500";
  return (
    <div className="hover-lift flex flex-col gap-3 rounded-2xl bg-white p-4 shadow-card">
      <Link href={`/admin/tenants/${r.id}`} className="flex items-start gap-3">
        <Avatar name={r.name} className="h-11 w-11 text-base" />
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2"><p className="truncate font-bold text-ink">{r.name}</p>{r.hasPayRequest && <span title={t("admin.filter.pay")} className="rounded-full bg-amber-100 p-1 text-amber-700"><Icon name="wallet" className="h-3.5 w-3.5" /></span>}</div>
          <p className="truncate text-xs text-slate-500" dir="ltr">{r.primaryHost}</p>
          <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
            <span className="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2 py-0.5 text-xs font-semibold text-slate-600"><Icon name={r.tenantType === "STORE" ? "bag" : r.tenantType === "SERVICE" ? "calendar" : r.tenantType === "LAWYER" ? "scale" : "stethoscope"} className="h-3 w-3" />{r.tenantType === "STORE" ? t("type.store") : r.tenantType === "SERVICE" ? t("type.service") : r.tenantType === "LAWYER" ? t("type.lawyer") : t("type.clinic")}</span>
            <StatusBadge status={r.status} />
            {r.subscriptionStatus && t(`status.${r.subscriptionStatus}`) !== t(`status.${r.status}`) && <StatusBadge status={r.subscriptionStatus} />}
          </div>
        </div>
      </Link>
      <div>
        <div className="mb-1 flex items-center justify-between text-xs"><span className="font-semibold text-slate-700">{planLabel(t, r.planCode)}</span><span className={left !== null && left <= 7 ? "font-bold text-amber-700" : "text-slate-500"}>{left === null ? "-" : left <= 0 ? t("admin.expired") : t("admin.daysLeft", { n: left })}</span></div>
        <div className="h-1.5 overflow-hidden rounded-full bg-slate-100"><div className={`h-full rounded-full ${tone}`} style={{ width: `${pct}%` }} /></div>
        <div className="mt-1 flex justify-between text-[11px] text-slate-400"><span>{dateOnly(r.currentPeriodStart ?? r.createdAt, locale)}</span><span>{dateOnly(r.currentPeriodEnd, locale)}</span></div>
        <p className="mt-1 text-[11px] font-semibold text-slate-500">{t("act.used")}: <span className="tnum">{((r.pointsUsedMinor ?? 0) / 100).toLocaleString(locale === "ar" ? "ar-EG" : "en-US", { maximumFractionDigits: 1 })}</span></p>
      </div>
      <div className="flex items-center justify-between gap-2 border-t border-slate-100 pt-3">
        <div className="min-w-0"><p className="truncate text-sm font-semibold">{r.ownerName || "-"}</p>{r.ownerPhone && <p className="text-xs text-slate-500" dir="ltr">{localPhone(r.ownerPhone)}</p>}</div>
        {r.ownerPhone && (
          <div className="flex shrink-0 gap-2">
            <a href={`tel:${r.ownerPhone}`} aria-label={t("admin.call")} className="flex h-9 w-9 items-center justify-center rounded-full bg-brand text-white transition hover:brightness-110"><Icon name="phone" className="h-4 w-4" /></a>
            <a href={whatsappLink(r.ownerPhone)} target="_blank" rel="noopener noreferrer" aria-label={t("admin.whatsapp")} className="flex h-9 w-9 items-center justify-center rounded-full bg-emerald-500 text-white transition hover:brightness-110"><Icon name="chat" className="h-4 w-4" /></a>
          </div>
        )}
      </div>
    </div>
  );
}

function Inner() {
  const { t } = useI18n();
  const params = useSearchParams();
  const [q, setQ] = useState("");
  const [types, setTypes] = useState<string[]>([]);
  const [startFrom, setStartFrom] = useState(""); const [startTo, setStartTo] = useState(""); const [endFrom, setEndFrom] = useState(""); const [endTo, setEndTo] = useState("");
  const [expiring, setExpiring] = useState(""); const [minPoints, setMinPoints] = useState(""); const [window_, setWindow] = useState("30");
  const [sort, setSort] = useState("created"); const [dir, setDir] = useState("desc");
  const type = types.join(",");
  const [filter, setFilter] = useState(params.get("filter") ?? "");
  const [page, setPage] = useState(1);
  const status = params.get("status") ?? "";
  const { data, error, loading } = useApi<Page<Row>>(`platform/tenants?page=${page}&pageSize=12${q ? `&q=${encodeURIComponent(q)}` : ""}${type ? `&type=${type}` : ""}${startFrom ? `&startFrom=${startFrom}` : ""}${startTo ? `&startTo=${startTo}` : ""}${endFrom ? `&endFrom=${endFrom}` : ""}${endTo ? `&endTo=${endTo}` : ""}${expiring ? `&expiringDays=${expiring}` : ""}${minPoints.trim() ? `&minPointsMinor=${Math.round(Number(minPoints) * 100)}` : ""}&pointsFrom=${(() => { const d = new Date(); d.setDate(d.getDate() - Number(window_)); return d.toISOString().slice(0, 10); })()}&sort=${sort}&dir=${dir}${status ? `&status=${status}` : ""}${filter ? `&filter=${filter}` : ""}`);
  return (
    <>
      <PageHeader title={t("admin.tenants")} subtitle={data ? `${data.meta.total}` : undefined} />
      <div className="mb-4 space-y-3">
        <div className="relative"><Icon name="search" className="pointer-events-none absolute start-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" /><Input className="ps-10" placeholder={t("admin.searchTenants")} value={q} onChange={(e) => { setQ(e.target.value); setPage(1); }} /></div>
        <div className="flex flex-wrap items-center gap-3">
          <Tabs value={filter} onChange={(f) => { setFilter(f); setPage(1); }} tabs={[
            { key: "", label: t("admin.filter.all") }, { key: "expiring", label: t("admin.filter.expiring") }, { key: "waiting", label: t("admin.filter.waiting") }, { key: "pay", label: t("admin.filter.pay") }, { key: "disabled", label: t("admin.disabledCount") },
          ]} />
          <div className="flex flex-wrap gap-2" role="group" aria-label={t("admin.allTypes")}>
            <button type="button" aria-pressed={types.length === 0} onClick={() => { setTypes([]); setPage(1); }} className={`chip !min-h-11 ${types.length === 0 ? "chip-on" : ""}`}>{t("admin.allTypes")}</button>
            {([["STORE", "admin.shops"], ["CLINIC", "admin.clinics"], ["LAWYER", "admin.lawyers"], ["SERVICE", "admin.serviceBusinesses"]] as const).map(([k, label]) => <button key={k} type="button" aria-pressed={types.includes(k)} onClick={() => { setTypes(types.includes(k) ? types.filter((x) => x !== k) : [...types, k]); setPage(1); }} className={`chip !min-h-11 ${types.includes(k) ? "chip-on" : ""}`}>{t(label)}</button>)}
          </div>
        </div>
      </div>
      <details className="mb-4 rounded-2xl bg-white p-4 shadow-card" open>
        <summary className="cursor-pointer text-sm font-bold text-slate-700">{t("act.filters")}</summary>
        <div className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Field label={t("act.startFrom")}><Input type="date" dir="ltr" value={startFrom} onChange={(e) => { setStartFrom(e.target.value); setPage(1); }} /></Field>
          <Field label={t("act.startTo")}><Input type="date" dir="ltr" value={startTo} onChange={(e) => { setStartTo(e.target.value); setPage(1); }} /></Field>
          <Field label={t("act.endFrom")}><Input type="date" dir="ltr" value={endFrom} onChange={(e) => { setEndFrom(e.target.value); setPage(1); }} /></Field>
          <Field label={t("act.endTo")}><Input type="date" dir="ltr" value={endTo} onChange={(e) => { setEndTo(e.target.value); setPage(1); }} /></Field>
          <Field label={t("act.expiring")}><Select value={expiring} onChange={(e) => { setExpiring(e.target.value); setPage(1); }}><option value="">{t("act.any")}</option>{[7, 14, 30].map((d) => <option key={d} value={d}>{t("act.within", { n: String(d) })}</option>)}</Select></Field>
          <Field label={t("act.pointsWindow")}><Select value={window_} onChange={(e) => { setWindow(e.target.value); setPage(1); }}>{[7, 30, 90].map((d) => <option key={d} value={d}>{t("act.lastDays", { n: String(d) })}</option>)}</Select></Field>
          <Field label={t("act.minPoints")}><Input type="number" inputMode="decimal" min={0} dir="ltr" value={minPoints} onChange={(e) => { setMinPoints(e.target.value); setPage(1); }} placeholder="0" /></Field>
          <Field label={t("act.sort")}><div className="flex gap-2"><Select value={sort} onChange={(e) => { setSort(e.target.value); setPage(1); }}>{["created", "name", "subscriptionEnd", "subscriptionStart", "points", "type"].map((k) => <option key={k} value={k}>{t(`act.sort.${k}`)}</option>)}</Select><Select value={dir} onChange={(e) => setDir(e.target.value)}><option value="desc">{t("act.desc")}</option><option value="asc">{t("act.asc")}</option></Select></div></Field>
        </div>
      </details>
      <ErrorText error={error} />
      {loading && !data ? <Loading /> : data?.data.length === 0 ? <Empty>{t("admin.noTenants")}</Empty> : (
        <>
          <div className="stagger grid gap-4 md:grid-cols-2 xl:grid-cols-3">{data?.data.map((r, i) => <div key={r.id} style={{ "--i": i } as React.CSSProperties}><TenantCard r={r} /></div>)}</div>
          <div className="mt-4"><Pager meta={data?.meta} onPage={setPage} /></div>
        </>
      )}
    </>
  );
}

export default function Tenants() {
  return <Suspense fallback={<Loading />}><Inner /></Suspense>;
}
