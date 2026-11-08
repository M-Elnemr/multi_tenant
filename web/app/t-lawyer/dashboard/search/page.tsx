"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { dateOnly, dateTime } from "@/lib/format";
import { statusTone } from "@/lib/lawyer";
import { useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon, type IconName } from "@/components/icons";
import { Eyebrow, Rise } from "@/components/lawyer/kit";
import { Badge, Empty, PageHeader } from "@/components/ui";

type R = { clients: { id: string; name: string; phone: string }[]; cases: { id: string; number: string; title: string; status: string; outcome: string; court: string; courtCaseNo: string }[];
  actions: { id: string; title: string; actionDate: string; caseId: string; number: string; caseTitle: string }[]; steps: { id: string; kind: string; startsAt: string; description: string; caseId: string; number: string }[];
  notes: { id: string; title: string; startsAt: string; caseId?: string }[]; documents: { id: string; name: string; url: string; caseId: string; number: string }[] };

/** One box for everything in the office. */
export default function Search() {
  const { t, locale } = useI18n();
  const [q, setQ] = useState("");
  const [term, setTerm] = useState("");
  useEffect(() => { const h = setTimeout(() => setTerm(q.trim()), 250); return () => clearTimeout(h); }, [q]);
  const { data, loading } = useApi<R>(term.length >= 2 ? `lawyer/search?q=${encodeURIComponent(term)}` : null);
  const total = data ? Object.values(data).reduce((s, l) => s + l.length, 0) : 0;
  const group = (title: string, icon: IconName, children: React.ReactNode, n: number) => n === 0 ? null : (
    <section className="overflow-hidden rounded-[18px] bg-surface shadow-card">
      <h2 className="flex items-center gap-2.5 px-4 py-3 text-[13.5px] font-semibold text-ink hairline-b"><span className="grid h-7 w-7 place-items-center rounded-[8px] bg-surface-2 text-muted"><Icon name={icon} className="h-4 w-4" /></span>{title}<span className="rounded-full bg-surface-2 px-2 text-[11.5px] font-semibold text-muted tnum">{n}</span></h2>
      <div className="p-1.5">{children}</div>
    </section>
  );
  const row = "flex items-center justify-between gap-3 rounded-[10px] px-3 py-2.5 transition hover:bg-surface-2";
  return (
    <div className="space-y-5">
      <PageHeader eyebrow={<Eyebrow>{t("lw.eb.cases")}</Eyebrow>} title={t("lw.nav.search")} subtitle={t("lw.search.subtitle")} />
      <label className="relative block">
        <Icon name="search" className="pointer-events-none absolute top-1/2 h-5 w-5 -translate-y-1/2 text-faint ltr:left-4 rtl:right-4" />
        <input autoFocus value={q} onChange={(e) => setQ(e.target.value)} placeholder={t("lw.search.ph")} type="search"
          className="w-full rounded-[16px] border border-line-strong bg-surface py-4 text-[17px] text-ink shadow-card outline-none transition placeholder:text-faint focus:border-[var(--rail-accent)] focus:ring-4 focus:ring-[color-mix(in_srgb,var(--rail-accent)_18%,transparent)] ltr:pl-12 ltr:pr-4 rtl:pl-4 rtl:pr-12" />
      </label>
      {term.length < 2 ? <Empty compact icon="search">{t("lw.search.hint")}</Empty>
        : loading && !data ? <div className="grid gap-4 lg:grid-cols-2" aria-busy="true">{[0, 1].map((i) => <div key={i} className="skeleton h-40 !rounded-[18px]" />)}</div>
        : total === 0 ? <Empty icon="search">{t("lw.search.none", { q: term })}</Empty> : data && (
          <Rise className="grid items-start gap-4 lg:grid-cols-2">
            {group(t("lw.nav.cases"), "briefcase", <ul>{data.cases.map((k) => <li key={k.id}><Link href={`/dashboard/cases/${k.id}`} className={row}><span className="min-w-0"><span className="block truncate text-[13.5px] font-semibold text-ink">{k.title}</span><span className="text-xs text-muted"><span className="font-mono">#{k.number}</span>{k.courtCaseNo ? ` · ${k.courtCaseNo}` : ""}{k.court ? ` · ${k.court}` : ""}</span></span><Badge tone={statusTone[k.status]}>{t(`lw.status.${k.status}`)}</Badge></Link></li>)}</ul>, data.cases.length)}
            {group(t("lw.nav.clients"), "users", <ul>{data.clients.map((c) => <li key={c.id}><Link href={`/dashboard/clients/${c.id}`} className={row}><span className="text-[13.5px] font-semibold text-ink">{c.name}</span><span dir="ltr" className="text-xs text-muted tnum">{c.phone}</span></Link></li>)}</ul>, data.clients.length)}
            {group(t("lw.tab.timeline"), "calendar", <ul>{data.steps.map((s) => <li key={s.id}><Link href={`/dashboard/cases/${s.caseId}`} className={row}><span className="min-w-0"><span className="block truncate text-[13.5px] font-semibold text-ink">{s.description}</span><span className="text-xs text-muted">{t(`lw.cal.${s.kind}`)} · <span className="font-mono">#{s.number}</span></span></span><span className="shrink-0 text-xs text-muted tnum">{dateTime(s.startsAt, locale)}</span></Link></li>)}</ul>, data.steps.length)}
            {group(t("lw.tab.actions"), "file", <ul>{data.actions.map((a) => <li key={a.id}><Link href={`/dashboard/cases/${a.caseId}`} className={row}><span className="min-w-0"><span className="block truncate text-[13.5px] font-semibold text-ink">{a.title}</span><span className="text-xs text-muted"><span className="font-mono">#{a.number}</span> · {a.caseTitle}</span></span><span className="shrink-0 text-xs text-muted tnum">{dateOnly(a.actionDate, locale)}</span></Link></li>)}</ul>, data.actions.length)}
            {group(t("lw.sch.kind.NOTE"), "edit", <ul>{data.notes.map((n) => <li key={n.id}><Link href={n.caseId ? `/dashboard/cases/${n.caseId}` : "/dashboard/schedule"} className={row}><span className="truncate text-[13.5px] font-semibold text-ink">{n.title}</span><span className="shrink-0 text-xs text-muted tnum">{dateTime(n.startsAt, locale)}</span></Link></li>)}</ul>, data.notes.length)}
            {group(t("lw.tab.documents"), "folder", <ul>{data.documents.map((d) => <li key={d.id}><a href={`/api/bff${d.url.replace("/api/v1", "")}`} target="_blank" rel="noopener noreferrer" className={row}><span className="truncate text-[13.5px] font-semibold text-ink">{d.name}</span><span className="shrink-0 font-mono text-xs text-muted">#{d.number}</span></a></li>)}</ul>, data.documents.length)}
          </Rise>
        )}
    </div>
  );
}
