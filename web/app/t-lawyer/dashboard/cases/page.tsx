"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { api } from "@/lib/client";
import { money } from "@/lib/format";
import { CASE_TYPES, statusTone, type CaseRow } from "@/lib/lawyer";
import { Page, useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { ActiveFilters, Chips, FilterGroup, MoreFilters, SearchBox } from "@/components/dashboard/filters";
import { DateRangePicker, rangeQuery, type DateRange } from "@/components/date-range";
import { CaseModal, ClientPicker } from "@/components/lawyer/case-form";
import { Badge, Button, ErrorText, Input, Loading, PageHeader, Pager, Select } from "@/components/ui";

type Res = Page<CaseRow> & { counts: { open: number; underStudy: number; closed: number; won: number; lost: number; settled: number; total: number } };

function Cases() {
  const { t, locale, currency } = useI18n();
  const router = useRouter();
  const sp = useSearchParams();
  const { canWrite } = useMe();
  const [q, setQ] = useState("");
  const [status, setStatus] = useState<string[]>([]);
  const [outcome, setOutcome] = useState<string[]>([]);
  const [types, setTypes] = useState<string[]>([]);
  const [client, setClient] = useState<{ id: string; name: string }[]>(() => (sp.get("clientId") ? [{ id: sp.get("clientId")!, name: sp.get("clientName") ?? "…" }] : []));
  const [court, setCourt] = useState("");
  const [range, setRange] = useState<DateRange>({});
  const [sort, setSort] = useState("NEWEST");
  const [page, setPage] = useState(1);
  const [creating, setCreating] = useState(sp.get("new") === "1");
  const reset = () => setPage(1);
  const qs = rangeQuery(new URLSearchParams({ page: String(page) }), range);
  if (q.trim()) qs.set("q", q.trim());
  if (status.length) qs.set("status", status.join(","));
  if (outcome.length) qs.set("outcome", outcome.join(","));
  if (types.length) qs.set("type", types.join(","));
  if (client[0]) qs.set("clientId", client[0].id);
  if (court.trim()) qs.set("court", court.trim());
  if (sort !== "NEWEST") qs.set("sort", sort);
  const { data, loading, error, reload } = useApi<Res>(`lawyer/cases?${qs}`);
  const quick = useAction(async (id: string, body: { status?: string; outcome?: string }) => { await api(`lawyer/cases/${id}/status`, { method: "PUT", body }); await reload(); });
  const c = data?.counts;
  const fmt = (m: number) => money(m, currency, locale);
  const more = [types.length, client.length, court.trim()].filter(Boolean).length;
  const active = [
    ...types.map((x) => ({ key: "t" + x, label: t(`lw.type.${x}`), onRemove: () => setTypes(types.filter((y) => y !== x)) })),
    ...client.map((x) => ({ key: "c" + x.id, label: `👤 ${x.name}`, onRemove: () => setClient([]) })),
    ...(court.trim() ? [{ key: "court", label: `🏛 ${court}`, onRemove: () => setCourt("") }] : []),
  ];

  return (
    <>
      <PageHeader title={t("lw.nav.cases")} subtitle={c ? t("lw.cases.subtitle", { n: c.total, open: c.open }) : undefined}
        actions={canWrite("cases") && <Button onClick={() => setCreating(true)}><Icon name="plus" className="h-4 w-4" />{t("lw.case.new")}</Button>} />
      <div className="mb-3 flex flex-wrap items-center gap-2 rounded-2xl bg-white p-3 shadow-card">
        <SearchBox value={q} onChange={(v) => { setQ(v); reset(); }} placeholder={t("lw.cases.search")} />
        <DateRangePicker value={range} onChange={(r) => { setRange(r); reset(); }} label={t("lw.cases.opened")} />
        <Select className="!w-auto min-h-11" value={sort} onChange={(e) => setSort(e.target.value)}>{["NEWEST", "OLDEST", "NEXT_STEP", "UPDATED"].map((s) => <option key={s} value={s}>{t(`lw.sort.${s}`)}</option>)}</Select>
        <MoreFilters count={more} onClear={() => { setTypes([]); setClient([]); setCourt(""); reset(); }}>
          <FilterGroup label={t("lw.case.type")}><div className="flex flex-wrap gap-1.5">{CASE_TYPES.map((x) => <button key={x} type="button" onClick={() => { setTypes(types.includes(x) ? types.filter((y) => y !== x) : [...types, x]); reset(); }} className={`chip ${types.includes(x) ? "chip-on" : ""}`}>{t(`lw.type.${x}`)}</button>)}</div></FilterGroup>
          <FilterGroup label={t("lw.case.clients")}><ClientPicker single value={client} onChange={(v) => { setClient(v); reset(); }} /></FilterGroup>
          <FilterGroup label={t("lw.case.court")}><Input value={court} onChange={(e) => { setCourt(e.target.value); reset(); }} placeholder={t("lw.case.courtPh")} /></FilterGroup>
        </MoreFilters>
      </div>
      <div className="mb-2 flex flex-wrap items-center gap-x-4 gap-y-2">
        <Chips allLabel={t("lw.cases.allStatus")} multi options={[
          { value: "OPEN", label: t("lw.status.OPEN"), count: c?.open, dot: "#3b82f6" },
          { value: "UNDER_STUDY", label: t("lw.status.UNDER_STUDY"), count: c?.underStudy, dot: "#f59e0b" },
          { value: "CLOSED", label: t("lw.status.CLOSED"), count: c?.closed, dot: "#94a3b8" },
        ]} value={status} onChange={(v) => { setStatus(v); reset(); }} />
        <span className="hidden h-6 w-px bg-slate-200 sm:block" />
        <Chips multi options={[
          { value: "WON", label: "🏆 " + t("lw.outcome.WON"), count: c?.won },
          { value: "LOST", label: t("lw.outcome.LOST"), count: c?.lost },
          { value: "SETTLED", label: "🤝 " + t("lw.outcome.SETTLED"), count: c?.settled },
        ]} value={outcome} onChange={(v) => { setOutcome(v); reset(); }} />
      </div>
      <div className="mb-3"><Chips options={["CRIMINAL", "FAMILY", "CIVIL", "COMMERCIAL", "LABOUR", "REAL_ESTATE"].map((x) => ({ value: x, label: t(`lw.type.${x}`) }))} multi value={types} onChange={(v) => { setTypes(v); reset(); }} /></div>
      <ActiveFilters items={active} onClearAll={() => { setTypes([]); setClient([]); setCourt(""); setStatus([]); setOutcome([]); setRange({}); setQ(""); reset(); }} />
      <ErrorText error={error ?? quick.error} />
      {loading && !data ? <Loading /> : data && data.data.length === 0 ? (
        <div className="animate-fade-up rounded-3xl border border-dashed border-slate-300 bg-white/70 p-10 text-center">
          <p className="text-5xl">⚖️</p><p className="mt-3 text-lg font-bold text-ink">{c?.total ? t("lw.cases.noneMatch") : t("lw.cases.emptyTitle")}</p>
          {!c?.total && <><p className="mx-auto mt-1 max-w-md text-sm text-slate-500">{t("lw.cases.emptyBody")}</p><Button className="mt-5" onClick={() => setCreating(true)}>{t("lw.case.new")}</Button></>}
        </div>
      ) : (
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          {data?.data.map((k, i) => {
            const due = k.billedMinor - k.paidMinor;
            return (
              <article key={k.id} style={{ animationDelay: `${Math.min(i, 8) * 35}ms` }} className="group relative flex animate-fade-up flex-col rounded-2xl bg-white p-4 shadow-card ring-1 ring-slate-100 transition hover:-translate-y-0.5 hover:shadow-lift">
                <span className="absolute inset-y-4 start-0 w-1 rounded-e-full" style={{ background: k.outcome === "WON" ? "#10b981" : k.outcome === "LOST" ? "#ef4444" : k.status === "OPEN" ? "#3b82f6" : k.status === "UNDER_STUDY" ? "#f59e0b" : "#94a3b8" }} />
                <div className="flex items-start justify-between gap-2">
                  <Link href={`/dashboard/cases/${k.id}`} className="min-w-0"><p className="font-mono text-xs font-bold text-slate-400">#{k.number}{k.courtCaseNo ? ` · ${k.courtCaseNo}` : ""}</p><p className="mt-0.5 line-clamp-2 font-bold text-ink group-hover:text-brand">{k.title}</p></Link>
                  <div className="flex shrink-0 flex-col items-end gap-1"><Badge tone={statusTone[k.status]}>{t(`lw.status.${k.status}`)}</Badge>{k.outcome !== "NONE" && <Badge tone={statusTone[k.outcome]}>{t(`lw.outcome.${k.outcome}`)}</Badge>}</div>
                </div>
                <div className="mt-2 flex flex-wrap gap-1.5 text-xs">
                  <span className="rounded-full bg-slate-100 px-2 py-0.5 font-semibold text-slate-600">{t(`lw.type.${k.caseType}`)}</span>
                  {k.court && <span className="rounded-full bg-slate-100 px-2 py-0.5 text-slate-600">🏛 {k.court}</span>}
                </div>
                {k.clientNames && <p className="mt-2 truncate text-sm text-slate-600">👤 {k.clientNames}</p>}
                {k.opponent && <p className="truncate text-xs text-slate-500">{t("lw.case.vs")} {k.opponent}</p>}
                <div className="mt-auto flex items-end justify-between gap-2 pt-3">
                  {k.nextStepAt ? <p className="rounded-lg bg-indigo-50 px-2 py-1 text-xs font-semibold text-indigo-700">📅 {new Date(k.nextStepAt).toLocaleString(locale === "ar" ? "ar-EG" : "en-GB", { day: "numeric", month: "short", hour: "numeric", minute: "2-digit" })}</p> : <span />}
                  {due > 0 ? <p className="text-xs font-bold text-red-600">{t("lw.due")} {fmt(due)}</p> : k.billedMinor > 0 ? <p className="text-xs font-bold text-emerald-600">✓ {t("lw.paidUp")}</p> : null}
                </div>
                {canWrite("cases") && k.status !== "CLOSED" && (
                  <div className="mt-3 flex gap-1.5 border-t border-slate-100 pt-3 opacity-100 transition sm:opacity-0 sm:group-hover:opacity-100">
                    {k.status === "UNDER_STUDY" && <button type="button" onClick={() => quick.run(k.id, { status: "OPEN" })} className="chip">▶ {t("lw.quick.open")}</button>}
                    <button type="button" onClick={() => quick.run(k.id, { status: "CLOSED", outcome: "WON" })} className="chip">🏆 {t("lw.quick.won")}</button>
                    <button type="button" onClick={() => quick.run(k.id, { status: "CLOSED", outcome: "LOST" })} className="chip">{t("lw.quick.lost")}</button>
                  </div>
                )}
              </article>
            );
          })}
        </div>
      )}
      <Pager meta={data?.meta} onPage={setPage} />
      {creating && <CaseModal open onClose={() => setCreating(false)} onSaved={(k) => { setCreating(false); router.push(`/dashboard/cases/${k.id}`); }} />}
    </>
  );
}

export default function CasesPage() { return <Suspense><Cases /></Suspense>; }
