"use client";

import Link from "next/link";
import { useState } from "react";
import { dateOnly, money } from "@/lib/format";
import { useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { DateRangePicker, rangeQuery, type DateRange } from "@/components/date-range";
import { Eyebrow, PaidBar, Rise, useIntl } from "@/components/lawyer/kit";
import { AreaChart, Empty, ErrorText, KpiStrip, Loading, PageHeader, Section } from "@/components/ui";

type Fin = { billedMinor: number; collectedMinor: number; dueMinor: number; collectedInRangeMinor: number; billedInRangeMinor: number;
  monthly: { month: string; collectedMinor: number; billedMinor: number }[];
  topDue: { id: string; number: string; title: string; clientNames?: string; dueMinor: number }[];
  recentPayments: { id: string; amountMinor: number; paidOn: string; method: string; note: string; caseId: string; number: string; title: string; clientName?: string }[] };

export default function Finance() {
  const { t, locale, currency } = useI18n();
  const intl = useIntl();
  const [range, setRange] = useState<DateRange>({});
  const { data, loading, error } = useApi<Fin>(`lawyer/finance?${rangeQuery(new URLSearchParams(), range)}`);
  const fmt = (m: number) => money(m, currency, locale);
  if (loading && !data) return <Loading />;
  const f = data;
  const rate = f && f.billedMinor > 0 ? Math.round((f.collectedMinor / f.billedMinor) * 100) : 0;
  return (
    <div className="space-y-5">
      <PageHeader eyebrow={<Eyebrow>{t("lw.eb.cases")}</Eyebrow>} title={t("lw.nav.finance")} subtitle={t("lw.fin.subtitle")} actions={<DateRangePicker value={range} onChange={setRange} />} />
      <ErrorText error={error} />
      {f && <Rise className="space-y-5">
        <KpiStrip items={[
          { label: t("lw.fin.billed"), value: fmt(f.billedMinor), icon: "receipt", tone: "slate" },
          { label: t("lw.fin.collected"), value: fmt(f.collectedMinor), icon: "wallet", tone: "green", hint: t("lw.fin.rate", { p: rate }) },
          { label: t("lw.fin.due"), value: fmt(f.dueMinor), icon: "alert", tone: f.dueMinor > 0 ? "red" : "green" },
          { label: t("lw.fin.inRange"), value: fmt(f.collectedInRangeMinor), icon: "trending", tone: "green", hint: t("lw.fin.billedInRange", { a: fmt(f.billedInRangeMinor) }) },
        ]} />
        <div className="rounded-[18px] bg-surface p-4 shadow-card sm:px-5"><div className="mb-2 flex items-center justify-between text-[12.5px] font-medium text-muted"><span>{t("lw.fin.collected")}</span><span className="tnum">{rate}%</span></div><PaidBar pct={Math.min(100, rate)} /></div>
        <Section title={t("lw.fin.monthly")} icon="chart">
          <AreaChart points={f.monthly.map((m) => ({ label: new Intl.DateTimeFormat(intl, { month: "short", year: "2-digit" }).format(new Date(`${m.month}-01T00:00:00`)), value: m.collectedMinor / 100 }))} format={(n) => money(n * 100, currency, locale)} />
          <div className="mt-4 grid grid-cols-3 gap-2 text-center text-xs sm:grid-cols-6">{f.monthly.slice(-6).map((m) => <div key={m.month} className="rounded-[12px] bg-surface-2 p-2.5"><p className="text-muted">{new Intl.DateTimeFormat(intl, { month: "short" }).format(new Date(`${m.month}-01T00:00:00`))}</p><p className="mt-0.5 font-semibold text-emerald-700 tnum">{fmt(m.collectedMinor)}</p><p className="text-faint tnum">{fmt(m.billedMinor)}</p></div>)}</div>
        </Section>
        <div className="grid gap-5 lg:grid-cols-2">
          <Section title={t("lw.fin.topDue")} icon="alert" flush>
            {f.topDue.length === 0 ? <div className="p-4"><Empty compact icon="check">{t("lw.fin.noneDue")}</Empty></div> : (
              <ul className="divide-y divide-line">{f.topDue.map((k) => <li key={k.id}><Link href={`/dashboard/cases/${k.id}`} className="group flex items-center justify-between gap-3 px-5 py-3 transition hover:bg-surface-2"><span className="min-w-0"><span className="block truncate text-[13.5px] font-semibold text-ink">{k.title}</span><span className="block truncate text-xs text-muted"><span className="font-mono">#{k.number}</span>{k.clientNames ? ` · ${k.clientNames}` : ""}</span></span><b className="shrink-0 text-[13.5px] font-semibold text-rose-700 tnum">{fmt(k.dueMinor)}</b><Icon name="chevron" className="h-3.5 w-3.5 shrink-0 text-faint flip-rtl" /></Link></li>)}</ul>
            )}
          </Section>
          <Section title={t("lw.fin.recent")} icon="wallet" flush>
            {f.recentPayments.length === 0 ? <div className="p-4"><Empty compact icon="wallet" /></div> : (
              <ul className="divide-y divide-line">{f.recentPayments.map((p) => <li key={p.id}><Link href={`/dashboard/cases/${p.caseId}`} className="flex items-center justify-between gap-3 px-5 py-3 transition hover:bg-surface-2"><span className="min-w-0"><span className="block truncate text-[13.5px] font-semibold text-ink">{p.clientName ?? p.title}</span><span className="block truncate text-xs text-muted">{dateOnly(p.paidOn, locale)} · {t(`lw.pm.${p.method}`)} · <span className="font-mono">#{p.number}</span></span></span><b className="shrink-0 text-[13.5px] font-semibold text-emerald-700 tnum">+{fmt(p.amountMinor)}</b></Link></li>)}</ul>
            )}
          </Section>
        </div>
      </Rise>}
    </div>
  );
}
