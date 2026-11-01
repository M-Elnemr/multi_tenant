"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { dateOnly, dateTime } from "@/lib/format";
import { statusTone } from "@/lib/lawyer";
import { useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { Badge, PageHeader } from "@/components/ui";

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
  const group = (title: string, icon: string, children: React.ReactNode, n: number) => n === 0 ? null : (
    <section className="animate-fade-up rounded-2xl bg-white p-4 shadow-card"><h2 className="mb-2 flex items-center gap-2 text-sm font-bold text-slate-500"><span>{icon}</span>{title} <span className="rounded-full bg-slate-100 px-2 text-xs">{n}</span></h2>{children}</section>
  );
  const row = "flex items-center justify-between gap-2 rounded-xl px-2 py-2 hover:bg-slate-50";
  return (
    <>
      <PageHeader title={t("lw.nav.search")} subtitle={t("lw.search.subtitle")} />
      <label className="relative mb-6 block">
        <Icon name="search" className="pointer-events-none absolute top-1/2 h-5 w-5 -translate-y-1/2 text-slate-400 ltr:left-4 rtl:right-4" />
        <input autoFocus value={q} onChange={(e) => setQ(e.target.value)} placeholder={t("lw.search.ph")} className="w-full rounded-2xl border border-slate-200 bg-white py-4 text-lg shadow-card outline-none transition focus:border-brand focus:ring-4 focus:ring-brand/15 ltr:pl-12 ltr:pr-4 rtl:pl-4 rtl:pr-12" />
      </label>
      {term.length < 2 ? <p className="text-center text-sm text-slate-500">{t("lw.search.hint")}</p> : loading && !data ? <p className="text-center text-sm text-slate-500">…</p> : total === 0 ? <p className="text-center text-sm text-slate-500">{t("lw.search.none", { q: term })}</p> : data && (
        <div className="grid gap-4 lg:grid-cols-2">
          {group(t("lw.nav.cases"), "💼", <ul>{data.cases.map((k) => <li key={k.id}><Link href={`/dashboard/cases/${k.id}`} className={row}><span className="min-w-0"><span className="block truncate font-semibold">{k.title}</span><span className="text-xs text-slate-500">#{k.number}{k.courtCaseNo ? ` · ${k.courtCaseNo}` : ""}{k.court ? ` · ${k.court}` : ""}</span></span><Badge tone={statusTone[k.status]}>{t(`lw.status.${k.status}`)}</Badge></Link></li>)}</ul>, data.cases.length)}
          {group(t("lw.nav.clients"), "👥", <ul>{data.clients.map((c) => <li key={c.id}><Link href={`/dashboard/clients/${c.id}`} className={row}><span className="font-semibold">{c.name}</span><span dir="ltr" className="text-xs text-slate-500">{c.phone}</span></Link></li>)}</ul>, data.clients.length)}
          {group(t("lw.tab.timeline"), "📅", <ul>{data.steps.map((s) => <li key={s.id}><Link href={`/dashboard/cases/${s.caseId}`} className={row}><span className="min-w-0"><span className="block truncate font-semibold">{s.description}</span><span className="text-xs text-slate-500">{t(`lw.cal.${s.kind}`)} · #{s.number}</span></span><span className="text-xs text-slate-500">{dateTime(s.startsAt, locale)}</span></Link></li>)}</ul>, data.steps.length)}
          {group(t("lw.tab.actions"), "📝", <ul>{data.actions.map((a) => <li key={a.id}><Link href={`/dashboard/cases/${a.caseId}`} className={row}><span className="min-w-0"><span className="block truncate font-semibold">{a.title}</span><span className="text-xs text-slate-500">#{a.number} · {a.caseTitle}</span></span><span className="text-xs text-slate-500">{dateOnly(a.actionDate, locale)}</span></Link></li>)}</ul>, data.actions.length)}
          {group(t("lw.sch.kind.NOTE"), "🗒️", <ul>{data.notes.map((n) => <li key={n.id}><Link href={n.caseId ? `/dashboard/cases/${n.caseId}` : "/dashboard/schedule"} className={row}><span className="truncate font-semibold">{n.title}</span><span className="text-xs text-slate-500">{dateTime(n.startsAt, locale)}</span></Link></li>)}</ul>, data.notes.length)}
          {group(t("lw.tab.documents"), "📄", <ul>{data.documents.map((d) => <li key={d.id}><a href={`/api/bff${d.url.replace("/api/v1", "")}`} target="_blank" rel="noopener noreferrer" className={row}><span className="truncate font-semibold">{d.name}</span><span className="text-xs text-slate-500">#{d.number}</span></a></li>)}</ul>, data.documents.length)}
        </div>
      )}
    </>
  );
}
