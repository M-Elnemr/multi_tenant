"use client";

import Link from "next/link";
import { useState } from "react";
import { money } from "@/lib/format";
import type { Client } from "@/lib/lawyer";
import { Page, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { useToast } from "@/components/toast";
import { SearchBox } from "@/components/dashboard/filters";
import { DateRangePicker, rangeQuery, type DateRange } from "@/components/date-range";
import { ClientModal } from "@/components/lawyer/client-modal";
import { ContactButtons, Eyebrow } from "@/components/lawyer/kit";
import { Avatar, Badge, Button, Empty, ErrorText, Loading, PageHeader, Pager, Table, Td } from "@/components/ui";

export default function Clients() {
  const { t, locale, currency } = useI18n();
  const toast = useToast();
  const { canWrite } = useMe();
  const [q, setQ] = useState("");
  const [range, setRange] = useState<DateRange>({});
  const [page, setPage] = useState(1);
  const [adding, setAdding] = useState(false);
  const qs = rangeQuery(new URLSearchParams({ page: String(page) }), range);
  if (q.trim()) qs.set("q", q.trim());
  const { data, loading, error, reload } = useApi<Page<Client>>(`lawyer/clients?${qs}`);
  return (
    <div className="space-y-4">
      <PageHeader eyebrow={<Eyebrow>{t("lw.eb.cases")}</Eyebrow>} title={t("lw.nav.clients")} subtitle={data ? t("lw.clients.count", { n: data.meta.total }) : undefined}
        actions={canWrite("clients") && <Button onClick={() => setAdding(true)}><Icon name="plus" className="h-4 w-4" />{t("lw.client.new")}</Button>} />
      <div className="flex flex-wrap items-center gap-2.5 rounded-[18px] bg-surface p-2.5 shadow-card">
        <SearchBox value={q} onChange={(v) => { setQ(v); setPage(1); }} placeholder={t("lw.client.searchFull")} />
        <DateRangePicker value={range} onChange={(r) => { setRange(r); setPage(1); }} label={t("customers.joined")} />
      </div>
      <ErrorText error={error} />
      {loading && !data ? <Loading variant="table" /> : data && data.data.length === 0 ? (
        <Empty icon="users" title={t("lw.clients.empty")} action={canWrite("clients") ? <Button onClick={() => setAdding(true)}><Icon name="plus" className="h-4 w-4" />{t("lw.client.new")}</Button> : undefined} />
      ) : (
        <Table head={[t("lw.client.name"), t("lw.client.phone"), t("lw.nav.cases"), t("lw.fin.balance"), ""]}>
          {data?.data.map((c) => (
            <tr key={c.id}>
              <Td>
                <Link href={`/dashboard/clients/${c.id}`} className="group flex min-w-0 items-center gap-3">
                  <Avatar name={c.name} className="h-10 w-10 text-sm" />
                  <span className="min-w-0"><span className="flex items-center gap-1.5 truncate text-[14px] font-semibold text-ink group-hover:text-brand">{c.name}{c.kind === "COMPANY" && <Icon name="building" className="h-3.5 w-3.5 shrink-0 text-muted" />}</span>
                    {c.tags.length > 0 && <span className="mt-0.5 flex flex-wrap gap-1">{c.tags.slice(0, 3).map((tg) => <span key={tg} className="rounded-full bg-surface-2 px-1.5 text-[11px] font-medium text-muted ring-1 ring-line">#{tg}</span>)}</span>}</span>
                </Link>
              </Td>
              <Td><span dir="ltr" className="text-[13px] text-ink-2 tnum">{c.phone || "—"}</span></Td>
              <Td><span className="flex items-center gap-1.5 text-[13px] text-ink-2 tnum">{t("lw.clients.cases", { n: c.caseCount ?? 0 })}{(c.openCases ?? 0) > 0 && <Badge tone="blue">{t("lw.clients.open", { n: c.openCases! })}</Badge>}</span></Td>
              <Td>{(c.balanceMinor ?? 0) > 0 ? <span className="text-[13px] font-semibold text-rose-700 tnum">{money(c.balanceMinor!, currency, locale)}</span> : <span className="text-faint">—</span>}</Td>
              <Td className="w-28 text-end"><ContactButtons phone={c.phone} size="sm" /></Td>
            </tr>
          ))}
        </Table>
      )}
      <Pager meta={data?.meta} onPage={setPage} />
      {adding && <ClientModal onClose={() => setAdding(false)} onSaved={async () => { setAdding(false); toast(t("lw.toast.clientAdded")); await reload(); }} />}
    </div>
  );
}
