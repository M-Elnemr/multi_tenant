"use client";

import { useState } from "react";
import Link from "next/link";
import { api } from "@/lib/client";
import { Page, useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Button, Empty, ErrorText, Loading, Pager, Table, Td } from "@/components/ui";
import { dateTime } from "@/lib/format";
import type { AdminDomainOrder } from "./domain-admin-orders";

/**
 * Failed domain orders, cross-tenant. The Retry button is always shown (the simpler option than pre-computing
 * retryability client-side) — the backend decides per the contract and answers 409 NOT_RETRYABLE when it can't be
 * retried, which ErrorText then surfaces inline on that row.
 */
export function DomainAdminFailed() {
  const { t } = useI18n();
  const [page, setPage] = useState(1);
  const { data, error, loading, reload } = useApi<Page<AdminDomainOrder>>(`platform/domains/orders?page=${page}&status=FAILED`);
  return (
    <>
      <ErrorText error={error} />
      {loading && !data ? <Loading /> : data?.data.length === 0 ? <Empty>{t("domainBuy.noFailed")}</Empty> : (
        <>
          <Table head={[t("admin.tenant"), t("domainBuy.colDomain"), t("domainBuy.colError"), t("domainBuy.colAttempts"), t("admin.created"), ""]}>
            {data?.data.map((r) => <FailedRow key={r.id} r={r} onDone={() => void reload()} />)}
          </Table>
          <Pager meta={data?.meta} onPage={setPage} />
        </>
      )}
    </>
  );
}

function FailedRow({ r, onDone }: { r: AdminDomainOrder; onDone: () => void }) {
  const { t, locale } = useI18n();
  const [done, setDone] = useState(false);
  const act = useAction(async () => { await api(`platform/domains/orders/${r.id}/retry`, { method: "POST" }); setDone(true); onDone(); });
  return (
    <tr>
      <Td>
        <Link href={`/admin/tenants/${r.tenantId}`} className="font-semibold text-brand hover:underline">{r.tenantName}</Link>
        <p className="text-xs text-slate-500" dir="ltr">{r.slug}</p>
      </Td>
      <Td className="font-mono text-xs"><span dir="ltr">{r.domain}</span></Td>
      <Td className="max-w-xs"><span className="line-clamp-2 text-xs text-red-700">{r.errorCode ? `${r.errorCode} — ` : ""}{r.lastError ?? "–"}</span></Td>
      <Td>{r.attempts ?? "–"}</Td>
      <Td>{dateTime(r.createdAt, locale)}</Td>
      <Td>
        {done ? <span className="text-xs font-semibold text-emerald-700">{t("domainBuy.retried")}</span> : <Button size="sm" variant="secondary" loading={act.loading} onClick={() => void act.run()}>{t("domainBuy.retry")}</Button>}
        <ErrorText error={act.error} />
      </Td>
    </tr>
  );
}
