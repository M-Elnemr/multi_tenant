"use client";

import { useState } from "react";
import Link from "next/link";
import { Page, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Badge, Empty, ErrorText, Loading, Table, Tabs, Td } from "@/components/ui";
import { dateOnly } from "@/lib/format";
import type { AdminDomainOrder } from "./domain-admin-orders";

const WINDOWS = ["30", "7", "1"] as const;

const daysLeft = (iso?: string | null) => (iso ? Math.ceil((new Date(iso).getTime() - Date.now()) / 86_400_000) : null);

/**
 * Live (registered) domains across every tenant that expire within the chosen window, so the platform can nudge
 * renewals before DNS/SSL quietly break. Filters the cross-tenant LIVE orders list by `expiresAt` client-side —
 * the contract doesn't name a dedicated expiring-soon endpoint, so this reuses the same `GET /orders` list.
 */
export function DomainAdminExpiring() {
  const { t, locale } = useI18n();
  const [span, setSpan] = useState<typeof WINDOWS[number]>("30");
  const { data, error, loading } = useApi<Page<AdminDomainOrder>>(`platform/domains/orders?page=1&pageSize=200&status=LIVE`);
  const rows = (data?.data ?? [])
    .map((r) => ({ r, left: daysLeft(r.expiresAt) }))
    .filter((x): x is { r: AdminDomainOrder; left: number } => x.left !== null && x.left <= Number(span))
    .sort((a, b) => a.left - b.left);
  return (
    <>
      <div className="mb-4"><Tabs value={span} onChange={setSpan} tabs={WINDOWS.map((w) => ({ key: w, label: t(`aih.days.${w}`) }))} /></div>
      <ErrorText error={error} />
      {loading && !data ? <Loading /> : rows.length === 0 ? <Empty>{t("domainBuy.noExpiring")}</Empty> : (
        <Table head={[t("admin.tenant"), t("domainBuy.colDomain"), t("domainBuy.colExpires"), t("domainBuy.colDaysLeft")]}>
          {rows.map(({ r, left }) => (
            <tr key={r.id}>
              <Td>
                <Link href={`/admin/tenants/${r.tenantId}`} className="font-semibold text-brand hover:underline">{r.tenantName}</Link>
                <p className="text-xs text-slate-500" dir="ltr">{r.slug}</p>
              </Td>
              <Td className="font-mono text-xs"><span dir="ltr">{r.domain}</span></Td>
              <Td>{dateOnly(r.expiresAt, locale)}</Td>
              <Td><Badge tone={left <= 1 ? "red" : left <= 7 ? "amber" : "slate"}>{left <= 0 ? t("domainBuy.expired") : left}</Badge></Td>
            </tr>
          ))}
        </Table>
      )}
    </>
  );
}
