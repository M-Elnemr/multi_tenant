"use client";

import { useState } from "react";
import Link from "next/link";
import { Page, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Empty, ErrorText, Loading, Pager, Table, Td } from "@/components/ui";
import { dateTime } from "@/lib/format";

type ProviderErrorRow = {
  id: string;
  occurredAt: string;
  tenantId?: string | null;
  tenantName?: string | null;
  domain?: string | null;
  orderId?: string | null;
  code: string;
  message: string;
};

/** Recent ProviderException-derived entries (registrar API failures), for troubleshooting — raw codes, not translated, same as the audit log. */
export function DomainAdminErrors() {
  const { t, locale } = useI18n();
  const [page, setPage] = useState(1);
  const { data, error, loading } = useApi<Page<ProviderErrorRow>>(`platform/domains/provider-errors?page=${page}`);
  return (
    <>
      <ErrorText error={error} />
      {loading && !data ? <Loading /> : data?.data.length === 0 ? <Empty>{t("domainBuy.noErrors")}</Empty> : (
        <>
          <Table head={[t("admin.when"), t("admin.tenant"), t("domainBuy.colDomain"), t("domainBuy.colCode"), t("domainBuy.colMessage")]}>
            {data?.data.map((r) => (
              <tr key={r.id}>
                <Td>{dateTime(r.occurredAt, locale)}</Td>
                <Td>{r.tenantId ? <Link href={`/admin/tenants/${r.tenantId}`} className="font-semibold text-brand hover:underline">{r.tenantName ?? r.tenantId.slice(0, 8)}</Link> : "–"}</Td>
                <Td className="font-mono text-xs"><span dir="ltr">{r.domain ?? "–"}</span></Td>
                <Td className="font-mono text-xs">{r.code}</Td>
                <Td className="max-w-md"><span className="line-clamp-2 text-xs">{r.message}</span></Td>
              </tr>
            ))}
          </Table>
          <Pager meta={data?.meta} onPage={setPage} />
        </>
      )}
    </>
  );
}
