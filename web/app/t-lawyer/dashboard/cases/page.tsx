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
import { Menu } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { BRASS, DotsButton, Eyebrow, caseAccent, useIntl } from "@/components/lawyer/kit";
import { Badge, Button, Empty, ErrorText, Input, KpiStrip, Loading, PageHeader, Pager, Select, Table, Td } from "@/components/ui";

type Res = Page<CaseRow> & { counts: { open: number; underStudy: number; closed: number; won: number; lost: number; settled: number; total: number } };

function Cases() {
  const { t, locale, currency } = useI18n();
  const router = useRouter();
  const intl = useIntl();
  const toast = useToast();
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
  const doQuick = async (id: string, body: { status?: string; outcome?: string }) => { await quick.run(id, body); toast(t("lw.toast.caseUpdated")); };
  const c = data?.counts;
  const fmt = (m: number) => money(m, currency, locale);
  const more = [types.length, client.length, court.trim()].filter(Boolean).length;
  const active = [
    ...types.map((x) => ({ key: "t" + x, label: t(`lw.type.${x}`), onRemove: () => setTypes(types.filter((y) => y !== x)) })),
    ...client.map((x) => ({ key: "c" + x.id, label: x.name, onRemove: () => setClient([]) })),
    ...(court.trim() ? [{ key: "court", label: court, onRemove: () => setCourt("") }] : []),
  ];

  return (
    <div className="space-y-4">
      <PageHeader eyebrow={<Eyebrow>{t("lw.eb.cases")}</Eyebrow>} title={t("lw.nav.cases")} subtitle={c ? t("lw.cases.subtitle", { n: c.total, open: c.open }) : undefined}
        actions={canWrite("cases") && <Button onClick={() => setCreating(true)}><Icon name="plus" className="h-4 w-4" />{t("lw.case.new")}</Button>} />
      <KpiStrip items={[
        { label: t("lw.status.OPEN"), value: c?.open ?? 0, icon: "briefcase", tone: "slate" },
        { label: t("lw.status.UNDER_STUDY"), value: c?.underStudy ?? 0, icon: "scale", tone: "amber" },
        { label: t("lw.outcome.WON"), value: c?.won ?? 0, icon: "award", tone: "green" },
        { label: t("lw.status.CLOSED"), value: c?.closed ?? 0, icon: "folder", tone: "slate" },
      ]} />
      <div className="flex flex-wrap items-center gap-2.5 rounded-[18px] bg-surface p-2.5 shadow-card">
        <SearchBox value={q} onChange={(v) => { setQ(v); reset(); }} placeholder={t("lw.cases.search")} />
        <DateRangePicker value={range} onChange={(r) => { setRange(r); reset(); }} label={t("lw.cases.opened")} />
        <Select className="!w-auto min-h-11" value={sort} onChange={(e) => setSort(e.target.value)}>{["NEWEST", "OLDEST", "NEXT_STEP", "UPDATED"].map((s) => <option key={s} value={s}>{t(`lw.sort.${s}`)}</option>)}</Select>
        <MoreFilters count={more} onClear={() => { setTypes([]); setClient([]); setCourt(""); reset(); }}>
          <FilterGroup label={t("lw.case.type")}><div className="flex flex-wrap gap-1.5">{CASE_TYPES.map((x) => <button key={x} type="button" onClick={() => { setTypes(types.includes(x) ? types.filter((y) => y !== x) : [...types, x]); reset(); }} className={`chip ${types.includes(x) ? "chip-on" : ""}`}>{t(`lw.type.${x}`)}</button>)}</div></FilterGroup>
          <FilterGroup label={t("lw.case.clients")}><ClientPicker single value={client} onChange={(v) => { setClient(v); reset(); }} /></FilterGroup>
          <FilterGroup label={t("lw.case.court")}><Input value={court} onChange={(e) => { setCourt(e.target.value); reset(); }} placeholder={t("lw.case.courtPh")} /></FilterGroup>
        </MoreFilters>
      </div>
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <Chips allLabel={t("lw.cases.allStatus")} multi options={[
          { value: "OPEN", label: t("lw.status.OPEN"), count: c?.open, dot: BRASS },
          { value: "UNDER_STUDY", label: t("lw.status.UNDER_STUDY"), count: c?.underStudy, dot: "#f59e0b" },
          { value: "CLOSED", label: t("lw.status.CLOSED"), count: c?.closed, dot: "#94a3b8" },
        ]} value={status} onChange={(v) => { setStatus(v); reset(); }} />
        <span className="hidden h-6 w-px bg-line sm:block" />
        <Chips multi options={[
          { value: "WON", label: t("lw.outcome.WON"), count: c?.won, dot: "#10b981" },
          { value: "LOST", label: t("lw.outcome.LOST"), count: c?.lost, dot: "#e11d48" },
          { value: "SETTLED", label: t("lw.outcome.SETTLED"), count: c?.settled, dot: "#0ea5e9" },
        ]} value={outcome} onChange={(v) => { setOutcome(v); reset(); }} />
      </div>
      <Chips options={["CRIMINAL", "FAMILY", "CIVIL", "COMMERCIAL", "LABOUR", "REAL_ESTATE"].map((x) => ({ value: x, label: t(`lw.type.${x}`) }))} multi value={types} onChange={(v) => { setTypes(v); reset(); }} />
      <ActiveFilters items={active} onClearAll={() => { setTypes([]); setClient([]); setCourt(""); setStatus([]); setOutcome([]); setRange({}); setQ(""); reset(); }} />
      <ErrorText error={error ?? quick.error} />
      {loading && !data ? <Loading variant="table" /> : data && data.data.length === 0 ? (
        <Empty icon="briefcase" title={c?.total ? t("lw.cases.noneMatch") : t("lw.cases.emptyTitle")} action={!c?.total && canWrite("cases") ? <Button onClick={() => setCreating(true)}><Icon name="plus" className="h-4 w-4" />{t("lw.case.new")}</Button> : undefined}>{!c?.total && t("lw.cases.emptyBody")}</Empty>
      ) : (
        <Table head={[t("lw.case.title"), t("lw.case.status"), t("lw.case.clients"), t("lw.case.nextStep"), t("lw.fin.balance"), ""]}>
          {data?.data.map((k) => {
            const due = k.billedMinor - k.paidMinor;
            const items = canWrite("cases") && k.status !== "CLOSED" ? [
              ...(k.status === "UNDER_STUDY" ? [{ label: t("lw.quick.open"), icon: "check", onSelect: () => void doQuick(k.id, { status: "OPEN" }) }] : []),
              { label: t("lw.quick.won"), icon: "award", onSelect: () => void doQuick(k.id, { status: "CLOSED", outcome: "WON" }) },
              { label: t("lw.quick.lost"), icon: "xcircle", onSelect: () => void doQuick(k.id, { status: "CLOSED", outcome: "LOST" }) },
            ] : [];
            return (
              <tr key={k.id} className="relative">
                <Td>
                  <Link href={`/dashboard/cases/${k.id}`} className="group flex min-w-0 items-center gap-3">
                    <span className="h-9 w-1 shrink-0 rounded-full" style={{ background: caseAccent(k.status, k.outcome) }} aria-hidden />
                    <span className="min-w-0"><span className="block font-mono text-[11.5px] text-muted">#{k.number}{k.courtCaseNo ? ` · ${k.courtCaseNo}` : ""}</span>
                      <span className="block max-w-[22rem] truncate text-[14px] font-semibold text-ink group-hover:text-brand">{k.title}</span>
                      <span className="block truncate text-xs text-muted">{t(`lw.type.${k.caseType}`)}{k.court ? ` · ${k.court}` : ""}{k.opponent ? ` · ${t("lw.case.vs")} ${k.opponent}` : ""}</span></span>
                  </Link>
                </Td>
                <Td><span className="flex flex-wrap gap-1"><Badge tone={statusTone[k.status]}>{t(`lw.status.${k.status}`)}</Badge>{k.outcome !== "NONE" && <Badge tone={statusTone[k.outcome]}>{t(`lw.outcome.${k.outcome}`)}</Badge>}</span></Td>
                <Td><span className="block max-w-[12rem] truncate text-[13px] text-ink-2">{k.clientNames || "—"}</span></Td>
                <Td>{k.nextStepAt ? <span className="inline-flex items-center gap-1.5 text-[12.5px] font-semibold text-ink-2 tnum"><Icon name="calendar" className="h-3.5 w-3.5" style={{ color: BRASS }} />{new Date(k.nextStepAt).toLocaleString(intl, { day: "numeric", month: "short", hour: "numeric", minute: "2-digit" })}</span> : <span className="text-faint">—</span>}</Td>
                <Td>{due > 0 ? <span className="text-[13px] font-semibold text-rose-700 tnum">{fmt(due)}</span> : k.billedMinor > 0 ? <span className="inline-flex items-center gap-1 text-xs font-semibold text-emerald-700"><Icon name="check" className="h-3.5 w-3.5" />{t("lw.paidUp")}</span> : <span className="text-faint">—</span>}</Td>
                <Td className="w-12 text-end">{items.length > 0 && <Menu items={items} label={t("common.more")} trigger={({ toggle, open }) => <DotsButton onClick={toggle} open={open} label={t("common.more")} />} />}</Td>
              </tr>
            );
          })}
        </Table>
      )}
      <Pager meta={data?.meta} onPage={setPage} />
      {creating && <CaseModal open onClose={() => setCreating(false)} onSaved={(k) => { setCreating(false); toast(t("lw.toast.caseCreated")); router.push(`/dashboard/cases/${k.id}`); }} />}
    </div>
  );
}

export default function CasesPage() { return <Suspense><Cases /></Suspense>; }
