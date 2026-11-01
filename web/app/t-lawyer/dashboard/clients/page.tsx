"use client";

import Link from "next/link";
import { useState } from "react";
import { money } from "@/lib/format";
import type { Client } from "@/lib/lawyer";
import { Page, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { SearchBox } from "@/components/dashboard/filters";
import { DateRangePicker, rangeQuery, type DateRange } from "@/components/date-range";
import { ClientModal } from "@/components/lawyer/client-modal";
import { Avatar, Button, ErrorText, Loading, PageHeader, Pager } from "@/components/ui";

export default function Clients() {
  const { t, locale, currency } = useI18n();
  const { canWrite } = useMe();
  const [q, setQ] = useState("");
  const [range, setRange] = useState<DateRange>({});
  const [page, setPage] = useState(1);
  const [adding, setAdding] = useState(false);
  const qs = rangeQuery(new URLSearchParams({ page: String(page) }), range);
  if (q.trim()) qs.set("q", q.trim());
  const { data, loading, error, reload } = useApi<Page<Client>>(`lawyer/clients?${qs}`);
  return (
    <>
      <PageHeader title={t("lw.nav.clients")} subtitle={data ? t("lw.clients.count", { n: data.meta.total }) : undefined} actions={canWrite("clients") && <Button onClick={() => setAdding(true)}><Icon name="plus" className="h-4 w-4" />{t("lw.client.new")}</Button>} />
      <div className="mb-4 flex flex-wrap items-center gap-2 rounded-2xl bg-white p-3 shadow-card">
        <SearchBox value={q} onChange={(v) => { setQ(v); setPage(1); }} placeholder={t("lw.client.searchFull")} />
        <DateRangePicker value={range} onChange={(r) => { setRange(r); setPage(1); }} label={t("customers.joined")} />
      </div>
      <ErrorText error={error} />
      {loading && !data ? <Loading /> : data && data.data.length === 0 ? (
        <div className="rounded-3xl border border-dashed border-slate-300 bg-white/70 p-10 text-center"><p className="text-5xl">👥</p><p className="mt-3 font-bold text-ink">{t("lw.clients.empty")}</p></div>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
          {data?.data.map((c) => (
            <Link key={c.id} href={`/dashboard/clients/${c.id}`} className="flex animate-fade-up items-center gap-3 rounded-2xl bg-white p-4 shadow-card ring-1 ring-slate-100 transition hover:-translate-y-0.5 hover:shadow-lift">
              <Avatar name={c.name} className="h-12 w-12 text-base" />
              <div className="min-w-0 flex-1">
                <p className="truncate font-bold text-ink">{c.kind === "COMPANY" ? "🏢 " : ""}{c.name}</p>
                <p dir="ltr" className="text-end text-xs text-slate-500 rtl:text-start">{c.phone || "—"}</p>
                <div className="mt-1 flex flex-wrap gap-1.5 text-[11px] font-semibold">
                  <span className="rounded-full bg-slate-100 px-2 py-0.5 text-slate-600">{t("lw.clients.cases", { n: c.caseCount ?? 0 })}</span>
                  {(c.openCases ?? 0) > 0 && <span className="rounded-full bg-blue-50 px-2 py-0.5 text-blue-700">{t("lw.clients.open", { n: c.openCases! })}</span>}
                  {(c.balanceMinor ?? 0) > 0 && <span className="rounded-full bg-red-50 px-2 py-0.5 text-red-600">{t("lw.due")} {money(c.balanceMinor!, currency, locale)}</span>}
                  {c.tags.map((tg) => <span key={tg} className="rounded-full bg-violet-50 px-2 py-0.5 text-violet-700">#{tg}</span>)}
                </div>
              </div>
            </Link>
          ))}
        </div>
      )}
      <Pager meta={data?.meta} onPage={setPage} />
      {adding && <ClientModal onClose={() => setAdding(false)} onSaved={async () => { setAdding(false); await reload(); }} />}
    </>
  );
}
