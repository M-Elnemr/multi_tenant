"use client";

import { useState } from "react";
import Link from "next/link";
import { Page, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Empty, ErrorText, Input, Loading, Pager, Select, StatusBadge, Table, Td } from "@/components/ui";
import { dateOnly, money } from "@/lib/format";
import type { Translate } from "@/lib/i18n";

export type AdminDomainOrder = {
  id: string;
  tenantId: string;
  tenantName: string;
  slug: string;
  domain: string;
  status: string;
  years: number;
  priceMinor: number;
  providerCostMinor: number | null;
  currency: string;
  errorCode?: string | null;
  lastError?: string | null;
  attempts?: number | null;
  expiresAt?: string | null;
  createdAt: string;
};

/** The order statuses the backend exposes (contract: PENDING_PAYMENT → PAID → REGISTERING → DNS_CONFIGURING → VERIFYING → LIVE, terminal FAILED/REFUNDED/CANCELLED). */
export const ORDER_STATUSES = ["PENDING_PAYMENT", "PAID", "REGISTERING", "DNS_CONFIGURING", "VERIFYING", "LIVE", "FAILED", "REFUNDED", "CANCELLED"] as const;

/** Same fallback StatusBadge uses: the translated label, or the raw code when this status has none yet. */
export function statusLabel(t: Translate, status: string): string {
  const label = t(`status.${status}`);
  return label === `status.${status}` ? status : label;
}

export function DomainAdminOrders() {
  const { t, locale } = useI18n();
  const [q, setQ] = useState("");
  const [status, setStatus] = useState("");
  const [tenantId, setTenantId] = useState("");
  const [page, setPage] = useState(1);
  const qs = `page=${page}${q ? `&q=${encodeURIComponent(q)}` : ""}${status ? `&status=${status}` : ""}${tenantId ? `&tenantId=${encodeURIComponent(tenantId)}` : ""}`;
  const { data, error, loading } = useApi<Page<AdminDomainOrder>>(`platform/domains/orders?${qs}`);
  return (
    <>
      <div className="mb-4 flex flex-wrap gap-2.5 rounded-[14px] bg-surface p-2.5 shadow-card">
        <Input className="max-w-xs" placeholder={t("domainBuy.searchPlaceholder")} value={q} onChange={(e) => { setQ(e.target.value); setPage(1); }} />
        <Input className="max-w-[14rem]" placeholder="tenant id" dir="ltr" value={tenantId} onChange={(e) => { setTenantId(e.target.value); setPage(1); }} />
        <Select className="max-w-[12rem]" value={status} onChange={(e) => { setStatus(e.target.value); setPage(1); }}>
          <option value="">{t("domainBuy.allStatuses")}</option>
          {ORDER_STATUSES.map((s) => <option key={s} value={s}>{statusLabel(t, s)}</option>)}
        </Select>
      </div>
      <ErrorText error={error} />
      {loading && !data ? <Loading /> : data?.data.length === 0 ? <Empty>{t("domainBuy.noOrders")}</Empty> : (
        <>
          <Table head={[t("admin.tenant"), t("domainBuy.colDomain"), t("admin.status"), t("domainBuy.colYears"), t("domainBuy.colCost"), t("domainBuy.colPrice"), t("domainBuy.colMargin"), t("admin.created")]}>
            {data?.data.map((r) => {
              const margin = r.providerCostMinor === null ? null : r.priceMinor - r.providerCostMinor;
              return (
                <tr key={r.id}>
                  <Td>
                    <Link href={`/admin/tenants/${r.tenantId}`} className="font-semibold text-brand hover:underline">{r.tenantName}</Link>
                    <p className="text-xs text-slate-500" dir="ltr">{r.slug}</p>
                  </Td>
                  <Td className="font-mono text-xs"><span dir="ltr">{r.domain}</span></Td>
                  <Td><StatusBadge status={r.status} /></Td>
                  <Td>{r.years}</Td>
                  <Td>{r.providerCostMinor === null ? "–" : money(r.providerCostMinor, r.currency, locale)}</Td>
                  <Td>{money(r.priceMinor, r.currency, locale)}</Td>
                  <Td className={margin !== null && margin < 0 ? "font-semibold text-red-600" : "font-semibold text-emerald-700"}>{margin === null ? "–" : money(margin, r.currency, locale)}</Td>
                  <Td>{dateOnly(r.createdAt, locale)}</Td>
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
