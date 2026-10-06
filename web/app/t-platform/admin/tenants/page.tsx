"use client";

import Link from "next/link";
import { useState } from "react";
import { Page, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { ErrorText, Input, Loading, PageHeader, Pager, Select, StatusBadge, Table, Td } from "@/components/ui";
import { dateOnly } from "@/lib/format";

type Row = { id: string; slug: string; name: string; tenantType: string; status: string; createdAt: string; subscriptionStatus?: string; planCode?: string; primaryHost?: string };

export default function Tenants() {
  const { t, locale } = useI18n();
  const [q, setQ] = useState("");
  const [type, setType] = useState("");
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(1);
  const { data, error, loading } = useApi<Page<Row>>(`platform/tenants?page=${page}&q=${encodeURIComponent(q)}&type=${type}&status=${status}`);
  return (
    <>
      <PageHeader title={t("admin.tenants")} />
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
          <Table head={[t("admin.name"), t("admin.type"), t("admin.status"), t("admin.plan"), t("admin.created")]}>
            {data?.data.map((r) => (
              <tr key={r.id} className="hover:bg-slate-50">
                <Td><Link href={`/admin/tenants/${r.id}`} className="font-medium text-brand">{r.name}</Link><div className="text-xs text-slate-500" dir="ltr">{r.primaryHost}</div></Td>
                <Td>{r.tenantType}</Td><Td><StatusBadge status={r.status} /></Td><Td>{r.planCode ?? "-"}</Td><Td>{dateOnly(r.createdAt, locale)}</Td>
              </tr>
            ))}
          </Table>
          <Pager meta={data?.meta} onPage={setPage} />
        </>
      )}
    </>
  );
}
