"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { Page, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { planLabel } from "@/components/dashboard/pay-screen";
import { Chips } from "@/components/dashboard/filters";
import { Icon } from "@/components/icons";
import { Sheet } from "@/components/overlay";
import { AdminSearch, ContactButtons, Fact, PeriodBar, Retry, ResultBar, SortHead, TypeTag } from "@/components/admin/kit";
import { Avatar, Button, Empty, Field, Input, Loading, PageHeader, Pager, Select, StatusBadge, Table, Tabs, Td, Alert } from "@/components/ui";
import { dateOnly } from "@/lib/format";
import { localPhone } from "@/lib/phone";

type Row = {
  id: string; slug: string; name: string; tenantType: string; status: string; createdAt: string; subscriptionStatus?: string; planCode?: string;
  currentPeriodStart?: string; currentPeriodEnd?: string; primaryHost?: string; ownerName?: string; ownerPhone?: string; hasPayRequest: boolean; pointsUsedMinor?: number;
};

function Inner() {
  const { t, locale } = useI18n();
  const params = useSearchParams();
  const [q, setQ] = useState("");
  const [types, setTypes] = useState<string[]>(params.get("type") ? params.get("type")!.split(",") : []);
  const [startFrom, setStartFrom] = useState(""); const [startTo, setStartTo] = useState(""); const [endFrom, setEndFrom] = useState(""); const [endTo, setEndTo] = useState("");
  const [expiring, setExpiring] = useState(""); const [minPoints, setMinPoints] = useState(""); const [window_, setWindow] = useState("30");
  const [sort, setSort] = useState("created"); const [dir, setDir] = useState<"asc" | "desc">("desc");
  const [more, setMore] = useState(false);
  const [peek, setPeek] = useState<Row | null>(null);
  const type = types.join(",");
  const [filter, setFilter] = useState(params.get("filter") ?? "");
  const [page, setPage] = useState(1);
  const status = params.get("status") ?? "";
  const { data, error, loading, reload } = useApi<Page<Row>>(`platform/tenants?page=${page}&pageSize=15${q ? `&q=${encodeURIComponent(q)}` : ""}${type ? `&type=${type}` : ""}${startFrom ? `&startFrom=${startFrom}` : ""}${startTo ? `&startTo=${startTo}` : ""}${endFrom ? `&endFrom=${endFrom}` : ""}${endTo ? `&endTo=${endTo}` : ""}${expiring ? `&expiringDays=${expiring}` : ""}${minPoints.trim() ? `&minPointsMinor=${Math.round(Number(minPoints) * 100)}` : ""}&pointsFrom=${(() => { const d = new Date(); d.setDate(d.getDate() - Number(window_)); return d.toISOString().slice(0, 10); })()}&sort=${sort}&dir=${dir}${status ? `&status=${status}` : ""}${filter ? `&filter=${filter}` : ""}`);

  const sortBy = (k: string) => { if (sort === k) setDir(dir === "asc" ? "desc" : "asc"); else { setSort(k); setDir(k === "name" || k === "type" ? "asc" : "desc"); } setPage(1); };
  const head = (label: string, k: string) => <SortHead label={label} active={sort === k} dir={dir} onClick={() => sortBy(k)} />;
  const advanced = [startFrom, startTo, endFrom, endTo, expiring, minPoints].filter(Boolean).length;
  const reset = () => { setQ(""); setTypes([]); setFilter(""); setStartFrom(""); setStartTo(""); setEndFrom(""); setEndTo(""); setExpiring(""); setMinPoints(""); setPage(1); };
  const anyFilter = !!(q || types.length || filter || advanced);
  const pts = (r: Row) => ((r.pointsUsedMinor ?? 0) / 100).toLocaleString(locale === "ar" ? "ar-EG" : "en-US", { maximumFractionDigits: 1 });

  return (
    <>
      <PageHeader eyebrow={t("admin.nav.operate")} title={t("admin.tenants")} subtitle={t("admin.tenantsSub")} />
      <div className="mb-3 space-y-2.5 rounded-[14px] bg-surface p-2.5 shadow-card">
        <div className="flex flex-wrap items-center gap-2">
          <AdminSearch value={q} onChange={(v) => { setQ(v); setPage(1); }} placeholder={t("admin.searchTenants")} />
          <Button variant="secondary" onClick={() => setMore(!more)} aria-expanded={more}><Icon name="sliders" className="h-4 w-4" />{t("act.filters")}{advanced > 0 && <span className="tnum grid h-5 min-w-5 place-items-center rounded-full bg-brand px-1 text-[11px] text-white">{advanced}</span>}</Button>
          {anyFilter && <Button variant="ghost" onClick={reset}>{t("filter.clearAll")}</Button>}
        </div>
        <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
          <Tabs size="sm" value={filter} onChange={(f) => { setFilter(f); setPage(1); }} tabs={[
            { key: "", label: t("admin.filter.all") }, { key: "expiring", label: t("admin.filter.expiring") }, { key: "waiting", label: t("admin.filter.waiting") }, { key: "pay", label: t("admin.filter.pay") }, { key: "disabled", label: t("admin.disabledCount") },
          ]} />
          <Chips multi value={types} onChange={(v) => { setTypes(v); setPage(1); }} options={[
            { value: "STORE", label: t("admin.shops") }, { value: "CLINIC", label: t("admin.clinics") }, { value: "LAWYER", label: t("admin.lawyers") }, { value: "SERVICE", label: t("admin.serviceBusinesses") },
          ]} />
        </div>
        {more && (
          <div className="grid gap-3 border-t border-line pt-3 sm:grid-cols-2 lg:grid-cols-4">
            <Field label={t("act.startFrom")}><Input type="date" dir="ltr" value={startFrom} onChange={(e) => { setStartFrom(e.target.value); setPage(1); }} /></Field>
            <Field label={t("act.startTo")}><Input type="date" dir="ltr" value={startTo} onChange={(e) => { setStartTo(e.target.value); setPage(1); }} /></Field>
            <Field label={t("act.endFrom")}><Input type="date" dir="ltr" value={endFrom} onChange={(e) => { setEndFrom(e.target.value); setPage(1); }} /></Field>
            <Field label={t("act.endTo")}><Input type="date" dir="ltr" value={endTo} onChange={(e) => { setEndTo(e.target.value); setPage(1); }} /></Field>
            <Field label={t("act.expiring")}><Select value={expiring} onChange={(e) => { setExpiring(e.target.value); setPage(1); }}><option value="">{t("act.any")}</option>{[7, 14, 30].map((d) => <option key={d} value={d}>{t("act.within", { n: String(d) })}</option>)}</Select></Field>
            <Field label={t("act.pointsWindow")}><Select value={window_} onChange={(e) => { setWindow(e.target.value); setPage(1); }}>{[7, 30, 90].map((d) => <option key={d} value={d}>{t("act.lastDays", { n: String(d) })}</option>)}</Select></Field>
            <Field label={t("act.minPoints")}><Input type="number" inputMode="decimal" min={0} dir="ltr" value={minPoints} onChange={(e) => { setMinPoints(e.target.value); setPage(1); }} placeholder="0" /></Field>
          </div>
        )}
      </div>

      {error && <div className="mb-3"><Alert>{t("admin.loadFailed")}<Retry onRetry={() => void reload()} /></Alert></div>}
      <ResultBar>{data ? t("admin.resultsCount", { n: data.meta.total }) : ""}</ResultBar>
      {loading && !data ? <Loading variant="table" /> : data?.data.length === 0 ? (
        <Empty icon="search" title={t("admin.noTenants")} action={anyFilter ? <Button variant="secondary" onClick={reset}>{t("filter.clearAll")}</Button> : undefined}>{anyFilter ? t("admin.noTenantsFiltered") : undefined}</Empty>
      ) : (
        <>
          <Table head={[head(t("admin.business"), "name"), head(t("admin.type"), "type"), t("admin.status"), t("admin.plan"), head(t("admin.endsOn"), "subscriptionEnd"), head(t("act.used"), "points"), t("admin.owner"), ""]}>
            {data?.data.map((r) => (
              <tr key={r.id} className="cursor-pointer" onClick={() => setPeek(r)}>
                <Td>
                  <div className="flex min-w-0 items-center gap-2.5">
                    <Avatar name={r.name} className="h-8 w-8 text-xs" />
                    <div className="min-w-0">
                      <p className="flex items-center gap-1.5 truncate font-semibold text-ink">{r.name}{r.hasPayRequest && <span title={t("admin.filter.pay")} className="grid h-4 w-4 place-items-center rounded-full bg-amber-100 text-amber-700"><Icon name="receipt" className="h-2.5 w-2.5" /></span>}</p>
                      <p className="max-w-[16rem] truncate text-[12px] text-muted" dir="ltr">{r.primaryHost}</p>
                    </div>
                  </div>
                </Td>
                <Td><TypeTag type={r.tenantType} /></Td>
                <Td><div className="flex flex-wrap gap-1"><StatusBadge status={r.status} />{r.subscriptionStatus && t(`status.${r.subscriptionStatus}`) !== t(`status.${r.status}`) && <StatusBadge status={r.subscriptionStatus} />}</div></Td>
                <Td className="whitespace-nowrap text-[13px]">{planLabel(t, r.planCode)}</Td>
                <Td><PeriodBar start={r.currentPeriodStart} end={r.currentPeriodEnd} /></Td>
                <Td className="tnum text-[13px]">{pts(r)}</Td>
                <Td><p className="truncate text-[13px] font-medium">{r.ownerName || "-"}</p>{r.ownerPhone && <p className="text-[12px] text-muted" dir="ltr">{localPhone(r.ownerPhone)}</p>}</Td>
                <Td><div className="flex items-center justify-end gap-1.5" onClick={(e) => e.stopPropagation()}>{r.ownerPhone && <ContactButtons phone={r.ownerPhone} />}<Link href={`/admin/tenants/${r.id}`} aria-label={t("admin.openFull")} title={t("admin.openFull")} className="grid h-8 w-8 place-items-center rounded-[9px] text-muted transition hover:bg-surface-2 hover:text-ink"><Icon name="arrow" className="h-4 w-4 flip-rtl" /></Link></div></Td>
              </tr>
            ))}
          </Table>
          <Pager meta={data?.meta} onPage={setPage} />
        </>
      )}

      <Sheet open={!!peek} onClose={() => setPeek(null)} title={peek?.name} width="28rem"
        footer={peek && <Link href={`/admin/tenants/${peek.id}`}><Button className="w-full">{t("admin.openFull")}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Button></Link>}>
        {peek && (
          <div className="space-y-5">
            <div className="flex items-center gap-3"><Avatar name={peek.name} className="h-12 w-12 text-base" /><div className="min-w-0"><p className="truncate text-[13px] text-muted" dir="ltr">{peek.primaryHost}</p><div className="mt-1 flex flex-wrap items-center gap-2"><TypeTag type={peek.tenantType} /><StatusBadge status={peek.status} /></div></div></div>
            <dl className="grid grid-cols-2 gap-4 rounded-[12px] bg-surface-2 p-4">
              <Fact label={t("admin.plan")}>{planLabel(t, peek.planCode)}</Fact>
              <Fact label={t("act.used")}><span className="tnum">{pts(peek)}</span></Fact>
              <Fact label={t("admin.subscribedSince")}>{dateOnly(peek.currentPeriodStart ?? peek.createdAt, locale)}</Fact>
              <Fact label={t("admin.endsOn")}>{dateOnly(peek.currentPeriodEnd, locale)}</Fact>
              <Fact label={t("admin.signedUp")}>{dateOnly(peek.createdAt, locale)}</Fact>
            </dl>
            <PeriodBar start={peek.currentPeriodStart} end={peek.currentPeriodEnd} className="!min-w-0" />
            <div>
              <p className="eyebrow mb-2">{t("admin.owner")}</p>
              <div className="flex items-center justify-between gap-3 rounded-[12px] ring-1 ring-line p-3">
                <div className="min-w-0"><p className="truncate text-[14px] font-semibold">{peek.ownerName || "-"}</p>{peek.ownerPhone && <p className="text-[12.5px] text-muted" dir="ltr">{localPhone(peek.ownerPhone)}</p>}</div>
                {peek.ownerPhone && <ContactButtons phone={peek.ownerPhone} size="md" />}
              </div>
            </div>
            {peek.hasPayRequest && <Link href="/admin/pay-requests" className="flex items-center gap-2 rounded-[12px] bg-amber-50 px-3.5 py-2.5 text-[13px] font-semibold text-amber-800"><Icon name="receipt" className="h-4 w-4" />{t("admin.filter.pay")}<Icon name="arrow" className="ms-auto h-4 w-4 flip-rtl" /></Link>}
          </div>
        )}
      </Sheet>
    </>
  );
}

export default function Tenants() {
  return <Suspense fallback={<Loading variant="table" />}><Inner /></Suspense>;
}
