"use client";

import { localPhone } from "@/lib/phone";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { Page, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { planLabel } from "@/components/dashboard/pay-screen";
import { ErrorText, Input, Loading, PageHeader, Pager, Select, StatusBadge, Table, Td, Tabs } from "@/components/ui";
import { dateOnly } from "@/lib/format";
import { whatsappLink } from "@/lib/whatsapp";

type Row = {
  id: string; slug: string; name: string; tenantType: string; status: string; createdAt: string; subscriptionStatus?: string; planCode?: string;
  currentPeriodStart?: string; currentPeriodEnd?: string; primaryHost?: string; ownerName?: string; ownerPhone?: string; hasPayRequest: boolean;
};

export const daysLeft = (iso?: string) => (iso ? Math.ceil((new Date(iso).getTime() - Date.now()) / 86_400_000) : null);

function Inner() {
  const { t, locale } = useI18n();
  const params = useSearchParams();
  const [q, setQ] = useState("");
  const [type, setType] = useState("");
  const [status, setStatus] = useState(params.get("status") ?? "");
  const [filter, setFilter] = useState(params.get("filter") ?? "");
  const [page, setPage] = useState(1);
  const { data, error, loading } = useApi<Page<Row>>(`platform/tenants?page=${page}&q=${encodeURIComponent(q)}&type=${type}&status=${status}&filter=${filter}`);
  return (
    <>
      <PageHeader title={t("admin.tenants")} />
      <div className="mb-3"><Tabs value={filter} onChange={(f) => { setFilter(f); setPage(1); }} tabs={[
        { key: "", label: t("admin.filter.all") }, { key: "expiring", label: t("admin.filter.expiring") }, { key: "waiting", label: t("admin.filter.waiting") }, { key: "pay", label: t("admin.filter.pay") },
      ]} /></div>
      <div className="mb-4 grid gap-3 sm:grid-cols-3">
        <Input placeholder={t("common.search")} value={q} onChange={(e) => { setQ(e.target.value); setPage(1); }} />
        <Select value={type} onChange={(e) => { setType(e.target.value); setPage(1); }}>
          <option value="">{t("admin.allTypes")}</option><option value="STORE">{t("type.store")}</option><option value="CLINIC">{t("type.clinic")}</option>
        </Select>
        <Select value={status} onChange={(e) => { setStatus(e.target.value); setPage(1); }}>
          <option value="">{t("admin.allStatuses")}</option>
          {["TRIAL", "ACTIVE", "PAST_DUE", "SUSPENDED", "CANCELLED", "ARCHIVED"].map((s) => <option key={s} value={s}>{t(`status.${s}`)}</option>)}
        </Select>
      </div>
      <ErrorText error={error} />
      {loading && !data ? <Loading /> : (
        <>
          <Table head={[t("admin.name"), t("admin.owner"), t("admin.status"), t("admin.plan"), t("admin.subscribedSince"), t("admin.endsOn")]}>
            {data?.data.map((r) => {
              const left = daysLeft(r.currentPeriodEnd);
              return (
                <tr key={r.id} className="hover:bg-slate-50">
                  <Td><Link href={`/admin/tenants/${r.id}`} className="font-medium text-brand">{r.name}</Link> {r.hasPayRequest && <span title={t("admin.filter.pay")}>💬</span>}
                    <div className="text-xs text-slate-500" dir="ltr">{r.primaryHost} · {r.tenantType === "STORE" ? t("type.store") : t("type.clinic")}</div></Td>
                  <Td>{r.ownerName}{r.ownerPhone && <div className="text-xs" dir="ltr"><a className="text-brand" href={`tel:${r.ownerPhone}`}>{localPhone(r.ownerPhone)}</a> · <a className="text-emerald-700" target="_blank" rel="noopener noreferrer" href={whatsappLink(r.ownerPhone)}>{t("admin.whatsapp")}</a></div>}</Td>
                  <Td><StatusBadge status={r.status} />{r.subscriptionStatus && r.subscriptionStatus !== r.status && <div className="mt-1"><StatusBadge status={r.subscriptionStatus} /></div>}</Td>
                  <Td>{planLabel(t, r.planCode)}</Td>
                  <Td>{dateOnly(r.currentPeriodStart ?? r.createdAt, locale)}</Td>
                  <Td>{dateOnly(r.currentPeriodEnd, locale)}{left !== null && <div className={`text-xs ${left <= 0 ? "text-red-600" : left <= 7 ? "text-amber-700" : "text-slate-500"}`}>{left <= 0 ? t("admin.expired") : t("admin.daysLeft", { n: left })}</div>}</Td>
                </tr>
              );
            })}
          </Table>
          <Pager meta={data?.meta} onPage={setPage} />
        </>
      )}
    </>
  );
}

export default function Tenants() {
  return <Suspense fallback={<Loading />}><Inner /></Suspense>;
}
