"use client";

import { useState } from "react";
import { Page, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { ErrorText, Input, Loading, PageHeader, Pager, Table, Td } from "@/components/ui";
import { dateTime } from "@/lib/format";

type Row = { id: string; occurredAt: string; action: string; entityType: string; tenantId?: string; actorUserId?: string };

export default function Audit() {
  const { t, locale } = useI18n();
  const [action, setAction] = useState("");
  const [page, setPage] = useState(1);
  const { data, error, loading } = useApi<Page<Row>>(`platform/audit?page=${page}&action=${encodeURIComponent(action)}`);
  return (
    <>
      <PageHeader title={t("admin.audit")} />
      <div className="mb-4 max-w-sm"><Input placeholder={t("admin.actionFilter")} value={action} onChange={(e) => { setAction(e.target.value.toUpperCase()); setPage(1); }} dir="ltr" /></div>
      <ErrorText error={error} />
      {loading && !data ? <Loading /> : (
        <>
          <Table head={[t("admin.when"), t("admin.action"), t("admin.entity"), t("admin.tenant")]}>
            {data?.data.map((r) => (
              <tr key={r.id}><Td>{dateTime(r.occurredAt, locale)}</Td><Td className="font-mono text-xs">{r.action}</Td><Td>{r.entityType}</Td><Td className="font-mono text-xs">{r.tenantId?.slice(0, 8)}</Td></tr>
            ))}
          </Table>
          <Pager meta={data?.meta} onPage={setPage} />
        </>
      )}
    </>
  );
}
