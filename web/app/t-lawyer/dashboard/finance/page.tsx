"use client";

import Link from "next/link";
import { useState } from "react";
import { dateOnly, money } from "@/lib/format";
import { useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { DateRangePicker, rangeQuery, type DateRange } from "@/components/date-range";
import { AreaChart, ErrorText, Loading, PageHeader, Section, Stat } from "@/components/ui";

type Fin = { billedMinor: number; collectedMinor: number; dueMinor: number; collectedInRangeMinor: number; billedInRangeMinor: number;
  monthly: { month: string; collectedMinor: number; billedMinor: number }[];
  topDue: { id: string; number: string; title: string; clientNames?: string; dueMinor: number }[];
  recentPayments: { id: string; amountMinor: number; paidOn: string; method: string; note: string; caseId: string; number: string; title: string; clientName?: string }[] };

export default function Finance() {
  const { t, locale, currency } = useI18n();
  const [range, setRange] = useState<DateRange>({});
  const { data, loading, error } = useApi<Fin>(`lawyer/finance?${rangeQuery(new URLSearchParams(), range)}`);
  const fmt = (m: number) => money(m, currency, locale);
  const intl = locale === "ar" ? "ar-EG" : "en-GB";
  if (loading && !data) return <Loading />;
  const f = data;
  const rate = f && f.billedMinor > 0 ? Math.round((f.collectedMinor / f.billedMinor) * 100) : 0;
  return (
    <>
      <PageHeader title={t("lw.nav.finance")} subtitle={t("lw.fin.subtitle")} actions={<DateRangePicker value={range} onChange={setRange} />} />
      <ErrorText error={error} />
      {f && <>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Stat label={t("lw.fin.billed")} value={fmt(f.billedMinor)} icon="receipt" />
          <Stat label={t("lw.fin.collected")} value={fmt(f.collectedMinor)} icon="wallet" tone="green" hint={t("lw.fin.rate", { p: rate })} />
          <Stat label={t("lw.fin.due")} value={fmt(f.dueMinor)} icon="alert" tone={f.dueMinor > 0 ? "red" : "green"} />
          <Stat label={t("lw.fin.inRange")} value={fmt(f.collectedInRangeMinor)} icon="trending" tone="green" hint={t("lw.fin.billedInRange", { a: fmt(f.billedInRangeMinor) })} />
        </div>
        <div className="mt-5"><Section title={t("lw.fin.monthly")}>
          <AreaChart points={f.monthly.map((m) => ({ label: new Intl.DateTimeFormat(intl, { month: "short", year: "2-digit" }).format(new Date(`${m.month}-01T00:00:00`)), value: m.collectedMinor / 100 }))} format={(n) => money(n * 100, currency, locale)} />
          <div className="mt-4 grid grid-cols-3 gap-2 text-center text-xs sm:grid-cols-6">{f.monthly.slice(-6).map((m) => <div key={m.month} className="rounded-xl bg-slate-50 p-2"><p className="text-slate-500">{new Intl.DateTimeFormat(intl, { month: "short" }).format(new Date(`${m.month}-01T00:00:00`))}</p><p className="font-bold text-emerald-700">{fmt(m.collectedMinor)}</p><p className="text-slate-400">{fmt(m.billedMinor)}</p></div>)}</div>
        </Section></div>
        <div className="mt-5 grid gap-5 lg:grid-cols-2">
          <Section title={t("lw.fin.topDue")}>
            {f.topDue.length === 0 ? <p className="py-4 text-center text-sm text-slate-500">🎉 {t("lw.fin.noneDue")}</p> : (
              <ul className="divide-y divide-slate-100">{f.topDue.map((k) => <li key={k.id}><Link href={`/dashboard/cases/${k.id}`} className="flex items-center justify-between gap-2 py-2.5 hover:text-brand"><span className="min-w-0"><span className="block truncate font-semibold">{k.title}</span><span className="block truncate text-xs text-slate-500">#{k.number}{k.clientNames ? ` · ${k.clientNames}` : ""}</span></span><b className="shrink-0 text-red-600">{fmt(k.dueMinor)}</b></Link></li>)}</ul>
            )}
          </Section>
          <Section title={t("lw.fin.recent")}>
            {f.recentPayments.length === 0 ? <p className="py-4 text-center text-sm text-slate-500">—</p> : (
              <ul className="divide-y divide-slate-100">{f.recentPayments.map((p) => <li key={p.id}><Link href={`/dashboard/cases/${p.caseId}`} className="flex items-center justify-between gap-2 py-2.5"><span className="min-w-0"><span className="block truncate font-semibold">{p.clientName ?? p.title}</span><span className="block truncate text-xs text-slate-500">{dateOnly(p.paidOn, locale)} · {t(`lw.pm.${p.method}`)} · #{p.number}</span></span><b className="shrink-0 text-emerald-700">+{fmt(p.amountMinor)}</b></Link></li>)}</ul>
            )}
          </Section>
        </div>
      </>}
    </>
  );
}
