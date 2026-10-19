"use client";

import { useState } from "react";
import { Page, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Empty, ErrorText, Input, Loading, Modal, PageHeader, Pager, Section, Select, Stat, Table, Td } from "@/components/ui";
import { dateTime } from "@/lib/format";

type Count = { name: string; views: number; visitors: number };
type Summary = {
  totals: { visitors: number; sessions: number; pageViews: number; avgMs: number };
  topPages: Count[]; countries: Count[]; devices: Count[]; browsers: Count[]; referrers: Count[];
};
type Visitor = {
  visitorId: string; firstSeen: string; lastSeen: string; pageViews: number; sessions: number; totalMs: number; country?: string; city?: string; ip?: string;
  browser?: string; os?: string; deviceType?: string; language?: string; host?: string; firstReferrer?: string;
};
type View = {
  id: string; sessionId: string; viewedAt: string; host: string; path: string; query?: string; referrer?: string; durationMs?: number; ip?: string; country?: string;
  city?: string; browser?: string; os?: string; deviceType?: string; language?: string; timezone?: string; screenW?: number; screenH?: number; userAgent?: string;
};

/** 83000 -> "1m 23s" */
const span = (ms?: number | null) => {
  if (!ms) return "-";
  const s = Math.round(ms / 1000);
  return s < 60 ? `${s}s` : `${Math.floor(s / 60)}m ${s % 60}s`;
};
const day = (d: Date) => d.toISOString().slice(0, 10);

function TopList({ title, rows }: { title: string; rows?: Count[] }) {
  const { t } = useI18n();
  return (
    <Section title={title}>
      {!rows?.length ? <p className="text-sm text-slate-500">{t("vis.none")}</p> : (
        <ul className="space-y-1.5 text-sm">
          {rows.map((r) => <li key={r.name} className="flex items-center justify-between gap-3"><span className="truncate" dir="ltr">{r.name}</span><span className="shrink-0 font-semibold tabular-nums">{r.views}</span></li>)}
        </ul>
      )}
    </Section>
  );
}

function Timeline({ id, onClose }: { id: string | null; onClose: () => void }) {
  const { t, locale } = useI18n();
  const { data, loading, error } = useApi<{ views: View[] }>(id ? `platform/analytics/visitors/${id}` : null);
  const first = data?.views.at(-1);   // newest first: the oldest view carries the earliest device details
  const last = data?.views[0];
  const facts: [string, string | undefined][] = last ? [
    [t("vis.ip"), last.ip], [t("vis.country"), [last.country, last.city].filter(Boolean).join(" · ")], [t("vis.device"), last.deviceType],
    [t("vis.browser"), last.browser], [t("vis.os"), last.os], [t("vis.language"), last.language], [t("vis.timezone"), last.timezone],
    [t("vis.screen"), last.screenW ? `${last.screenW}×${last.screenH}` : undefined], [t("vis.firstSeen"), first ? dateTime(first.viewedAt, locale) : undefined],
    [t("vis.referrer"), first?.referrer || t("vis.direct")],
  ] : [];
  return (
    <Modal open={!!id} onClose={onClose} title={t("vis.timeline")} wide>
      <ErrorText error={error} />
      {loading && !data ? <Loading /> : (
        <div className="space-y-4">
          <dl className="grid gap-x-6 gap-y-1 text-sm sm:grid-cols-2">
            {facts.map(([k, v]) => <div key={k} className="flex justify-between gap-3 border-b border-slate-100 py-1"><dt className="text-slate-500">{k}</dt><dd className="truncate font-medium" dir="ltr">{v || t("vis.unknown")}</dd></div>)}
          </dl>
          <Table head={[t("admin.when"), t("vis.site"), t("vis.page"), t("vis.timeSpent")]}>
            {data?.views.map((v) => (
              <tr key={v.id}><Td className="whitespace-nowrap">{dateTime(v.viewedAt, locale)}</Td><Td className="[direction:ltr] text-xs">{v.host}</Td><Td className="[direction:ltr] font-mono text-xs">{v.path}{v.query}</Td><Td>{span(v.durationMs)}</Td></tr>
            ))}
          </Table>
        </div>
      )}
    </Modal>
  );
}

export default function Visitors() {
  const { t, locale } = useI18n();
  const [from, setFrom] = useState(() => day(new Date(Date.now() - 6 * 86_400_000)));
  const [to, setTo] = useState(() => day(new Date()));
  const [path, setPath] = useState("");
  const [device, setDevice] = useState("");
  const [page, setPage] = useState(1);
  const [open, setOpen] = useState<string | null>(null);
  // the API's "to" is exclusive: add a day so the chosen end date is included
  const end = day(new Date(new Date(to).getTime() + 86_400_000));
  const q = `from=${from}&to=${end}`;
  const summary = useApi<Summary>(`platform/analytics/summary?${q}`);
  const list = useApi<Page<Visitor>>(`platform/analytics/visitors?${q}&page=${page}&path=${encodeURIComponent(path)}&device=${device}`);
  const s = summary.data;
  return (
    <>
      <PageHeader title={t("admin.visitors")} subtitle={t("vis.sub")} />
      <div className="mb-4 grid gap-2 sm:grid-cols-4">
        <label className="text-xs text-slate-500">{t("vis.from")}<Input type="date" value={from} max={to} onChange={(e) => { setFrom(e.target.value); setPage(1); }} dir="ltr" /></label>
        <label className="text-xs text-slate-500">{t("vis.to")}<Input type="date" value={to} min={from} onChange={(e) => { setTo(e.target.value); setPage(1); }} dir="ltr" /></label>
        <label className="text-xs text-slate-500">{t("vis.filterPath")}<Input value={path} onChange={(e) => { setPath(e.target.value); setPage(1); }} placeholder="/products" dir="ltr" /></label>
        <label className="text-xs text-slate-500">{t("vis.device")}
          <Select value={device} onChange={(e) => { setDevice(e.target.value); setPage(1); }}><option value="">-</option><option value="desktop">desktop</option><option value="mobile">mobile</option><option value="tablet">tablet</option></Select>
        </label>
      </div>
      <ErrorText error={summary.error ?? list.error} />
      <div className="mb-6 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label={t("vis.visitors")} value={s?.totals.visitors ?? 0} icon="users" />
        <Stat label={t("vis.sessions")} value={s?.totals.sessions ?? 0} icon="clock" tone="green" />
        <Stat label={t("vis.pageViews")} value={s?.totals.pageViews ?? 0} icon="file" tone="amber" />
        <Stat label={t("vis.avgTime")} value={span(s?.totals.avgMs)} icon="clock" />
      </div>
      <div className="mb-6 grid gap-3 md:grid-cols-2 xl:grid-cols-5">
        <TopList title={t("vis.topPages")} rows={s?.topPages} />
        <TopList title={t("vis.countries")} rows={s?.countries} />
        <TopList title={t("vis.devices")} rows={s?.devices} />
        <TopList title={t("vis.browsers")} rows={s?.browsers} />
        <TopList title={t("vis.referrers")} rows={s?.referrers} />
      </div>
      {list.loading && !list.data ? <Loading /> : !list.data?.data.length ? <Empty>{t("vis.none")}</Empty> : (
        <>
          <Table head={[t("vis.lastSeen"), t("vis.site"), t("vis.country"), t("vis.ip"), t("vis.device"), t("vis.browser"), t("vis.pages"), t("vis.timeSpent"), t("vis.referrer")]}>
            {list.data.data.map((v) => (
              <tr key={v.visitorId} onClick={() => setOpen(v.visitorId)} className="cursor-pointer">
                <Td className="whitespace-nowrap">{dateTime(v.lastSeen, locale)}</Td>
                <Td className="[direction:ltr] text-xs">{v.host}</Td>
                <Td>{[v.country, v.city].filter(Boolean).join(" · ") || "-"}</Td>
                <Td className="[direction:ltr] font-mono text-xs">{v.ip ?? "-"}</Td>
                <Td>{v.deviceType} · {v.os}</Td><Td>{v.browser}</Td>
                <Td className="tabular-nums">{v.pageViews}</Td><Td className="whitespace-nowrap">{span(v.totalMs)}</Td>
                <Td className="[direction:ltr] max-w-[12rem] truncate text-xs">{v.firstReferrer || t("vis.direct")}</Td>
              </tr>
            ))}
          </Table>
          <Pager meta={list.data.meta} onPage={setPage} />
        </>
      )}
      <Timeline id={open} onClose={() => setOpen(null)} />
    </>
  );
}
