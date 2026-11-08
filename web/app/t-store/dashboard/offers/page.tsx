"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { api } from "@/lib/client";
import { money } from "@/lib/format";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { ConfirmDialog, Menu } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { ActiveFilters, Chips, FilterGroup, SearchBox } from "@/components/dashboard/filters";
import { DateRangePicker, rangeQuery, type DateRange } from "@/components/date-range";
import { OFFER_TYPES, TYPE_LOOK, offerSummary, type Offer } from "@/components/dashboard/offer-form";
import { useNavTitle } from "@/components/dashboard/section";
import { Button, Empty, ErrorText, KpiStrip, Loading, PageHeader, Tabs } from "@/components/ui";
import { Sheet } from "@/components/overlay";

type Stats = { states: Record<string, number>; orders30d: number; revenue30dMinor: number; discount30dMinor: number };
const STATES = ["ALL", "ACTIVE", "SCHEDULED", "PAUSED", "DRAFT", "ENDED"] as const;
const STATE_DOT: Record<string, string> = { ACTIVE: "bg-emerald-500", SCHEDULED: "bg-sky-500", PAUSED: "bg-amber-500", DRAFT: "bg-slate-400", ENDED: "bg-slate-400" };

function Countdown({ to }: { to: string }) {
  const { t } = useI18n();
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => { const id = setInterval(() => setNow(Date.now()), 30000); return () => clearInterval(id); }, []);
  const ms = new Date(to).getTime() - now;
  if (ms <= 0) return null;
  const d = Math.floor(ms / 86400000), h = Math.floor((ms % 86400000) / 3600000), m = Math.floor((ms % 3600000) / 60000);
  return <span className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold ${ms < 86400000 ? "bg-red-50 text-red-700" : "bg-surface-2 text-ink-2"}`}><Icon name="clock" className="h-3 w-3" />{d > 0 ? t("offer.leftDays", { d, h }) : t("offer.leftHours", { h, m })}</span>;
}

export default function Offers() {
  const { t, locale, currency } = useI18n();
  const { can } = useMe();
  const toast = useToast();
  const sp = useSearchParams();
  const created = sp.get("created") === "1";
  const [state, setState] = useState<(typeof STATES)[number]>("ALL");
  const [types, setTypes] = useState<string[]>(() => (sp.get("type") ? [sp.get("type")!] : []));
  const [q, setQ] = useState("");
  const [range, setRange] = useState<DateRange>({});
  const [sheet, setSheet] = useState(false);
  const qs = rangeQuery(new URLSearchParams(), range);
  if (state !== "ALL") qs.set("state", state);
  if (types[0]) qs.set("type", types[0]);
  if (q.trim()) qs.set("q", q.trim());
  const list = useApi<Offer[]>(`store/offers?${qs}`);
  const stats = useApi<Stats>("store/offers/stats");
  const [confirm, setConfirm] = useState<Offer | null>(null);
  const fmt = (m: number) => money(m, currency, locale);
  const reload = async () => { await list.reload(); await stats.reload(); };
  const act = useAction(async (o: Offer, what: "pause" | "resume" | "dup" | "del") => {
    const base = o.kind === "COUPON" ? `store/coupons/${o.id}` : `store/offers/${o.id}`;
    if (what === "pause" || what === "resume") await api(`${base}/active`, { method: "PUT", body: { active: what === "resume" } });
    if (what === "dup") await api(`store/offers/${o.id}/duplicate`, { body: {} });
    if (what === "del") { await api(base, { method: "DELETE" }); setConfirm(null); }
    toast(what === "del" ? t("offer.toast.deleted") : what === "dup" ? t("offer.toast.duplicated") : what === "pause" ? t("offer.toast.paused") : t("offer.toast.resumed"));
    await reload();
  });
  const manage = can("coupon.manage");
  const s = stats.data;
  const filtered = !!q.trim() || types.length > 0 || state !== "ALL" || !!range.from || !!range.to;
  const clearAll = () => { setQ(""); setTypes([]); setState("ALL"); setRange({}); };
  const active = [
    ...(q.trim() ? [{ key: "q", label: `${t("common.search")}: ${q.trim()}`, onRemove: () => setQ("") }] : []),
    ...(types[0] ? [{ key: "t", label: t(`offer.type.${types[0]}`), onRemove: () => setTypes([]) }] : []),
    ...(state !== "ALL" ? [{ key: "s", label: t(`offer.state.${state}`), onRemove: () => setState("ALL") }] : []),
  ];
  const typeChips = <Chips allLabel={t("offer.allTypes")} options={OFFER_TYPES.map((ty) => ({ value: ty, label: t(`offer.type.${ty}`) }))} value={types} onChange={setTypes} />;
  const newBtn = manage && <Link href="/dashboard/offers/new" className="press inline-flex h-10 items-center gap-1.5 rounded-[11px] bg-ink px-4 text-[13px] font-semibold text-surface shadow-[0_1px_2px_rgb(16_18_24/.2)] transition hover:opacity-90 pointer-coarse:min-h-11"><Icon name="plus" className="h-4 w-4" />{t("offer.new")}</Link>;

  return (
    <>
      <PageHeader eyebrow={t("cat.eyebrow")} title={useNavTitle(t("nav.offers"))} subtitle={t("offer.listSubtitle")} actions={newBtn} />
      {created && <div className="mb-4 flex animate-fade-up items-center gap-2.5 rounded-[12px] bg-emerald-50 px-4 py-3 text-sm font-medium text-emerald-800 ring-1 ring-inset ring-emerald-600/15"><Icon name="check" className="h-4 w-4" />{t("offer.createdMsg")}</div>}
      <KpiStrip className="mb-5" items={[
        { label: t("offer.stat.running"), value: s?.states.ACTIVE ?? 0, icon: "sparkles", tone: "brand" },
        { label: t("offer.stat.orders"), value: s?.orders30d ?? 0, icon: "box", tone: "green" },
        { label: t("offer.stat.revenue"), value: fmt(s?.revenue30dMinor ?? 0), icon: "trending", tone: "green" },
        { label: t("offer.stat.given"), value: fmt(s?.discount30dMinor ?? 0), icon: "coupon", tone: "amber" },
      ]} />
      <div className="mb-3 flex flex-wrap items-center gap-2.5 rounded-[14px] bg-surface p-2.5 shadow-card">
        <SearchBox value={q} onChange={setQ} placeholder={t("offer.search")} />
        <DateRangePicker value={range} onChange={setRange} label={t("offer.runningIn")} />
        <button type="button" onClick={() => setSheet(true)} className={`inline-flex min-h-11 items-center gap-2 rounded-[11px] px-3.5 text-[13px] font-semibold ring-1 transition sm:hidden ${types.length ? "bg-brand-soft text-brand ring-brand/30" : "bg-surface text-ink-2 ring-line-strong"}`}><Icon name="sliders" className="h-4 w-4" />{t("offer.allTypes")}</button>
      </div>
      <div className="mb-3 max-w-full overflow-x-auto"><Tabs value={state} onChange={setState} tabs={STATES.map((k) => ({ key: k, label: t(`offer.state.${k}`), count: k === "ALL" ? undefined : s?.states[k] }))} /></div>
      <div className="mb-3 hidden sm:block">{typeChips}</div>
      <ActiveFilters items={active} onClearAll={clearAll} />
      <ErrorText error={list.error ?? act.error} />
      {list.loading && !list.data ? <Loading variant="cards" /> : list.data && list.data.length === 0 ? (
        filtered
          ? <Empty icon="search" title={t("cat.noMatchTitle")} action={<Button variant="secondary" onClick={clearAll}>{t("filter.clearAll")}</Button>}>{t("cat.noMatchBody")}</Empty>
          : <Empty icon="tag" title={t("offer.emptyTitle")} action={manage ? <Link href="/dashboard/offers/new"><Button variant="ink"><Icon name="plus" className="h-4 w-4" />{t("offer.new")}</Button></Link> : undefined}>{t("offer.emptyBody")}</Empty>
      ) : (
        <div className={`grid gap-4 transition-opacity md:grid-cols-2 xl:grid-cols-3 ${list.loading ? "opacity-60" : ""}`}>
          {list.data?.map((o, i) => {
            const l = TYPE_LOOK[o.type];
            const pct = o.maxUses ? Math.min(100, Math.round((o.usageCount / o.maxUses) * 100)) : null;
            const editable = o.kind === "OFFER" && manage;
            return (
              <article key={o.kind + o.id} style={{ animationDelay: `${Math.min(i, 8) * 30}ms` }} className="flex animate-fade-up flex-col overflow-hidden rounded-[18px] bg-surface shadow-card transition hover:shadow-[0_0_0_1px_var(--line-strong),0_14px_30px_-18px_rgb(16_18_24/.25)] motion-reduce:animate-none">
                <div className="flex items-start gap-3 p-4">
                  <span className="grid h-11 w-11 shrink-0 place-items-center rounded-[13px] text-white shadow-[0_8px_18px_-8px_rgb(16_18_24/.4)]" style={{ background: `linear-gradient(135deg, ${l.from}, ${l.to})` }}><Icon name={l.icon} className="h-5 w-5" /></span>
                  <div className="min-w-0 flex-1">
                    <p className="text-[12px] font-medium text-muted">{t(`offer.type.${o.type}`)}</p>
                    <p className="truncate font-display text-[15px] font-semibold text-ink">{o.kind === "COUPON" ? <span className="font-mono tracking-widest" dir="ltr">{o.code}</span> : o.name}</p>
                  </div>
                  <span className="inline-flex shrink-0 items-center gap-1.5 rounded-full bg-surface-2 px-2.5 py-1 text-[11.5px] font-semibold text-ink-2 ring-1 ring-line"><span className={`h-1.5 w-1.5 rounded-full ${STATE_DOT[o.state] ?? "bg-slate-400"} ${o.state === "ACTIVE" ? "animate-pulse" : ""}`} />{t(`offer.state.${o.state}`)}</span>
                </div>
                <div className="flex flex-1 flex-col gap-3 px-4 pb-4">
                  <p className="text-[13.5px] font-medium text-ink">{offerSummary(o, t, fmt)}</p>
                  <div className="flex flex-wrap items-center gap-2 text-xs text-muted">
                    {o.endsAt && o.state !== "ENDED" && <Countdown to={o.endsAt} />}
                    {o.startsAt && o.state === "SCHEDULED" && <span>{t("offer.startsOn", { d: new Date(o.startsAt).toLocaleString(locale === "ar" ? "ar-EG" : "en-GB", { dateStyle: "medium", timeStyle: "short" }) })}</span>}
                    {o.appliesTo && o.appliesTo !== "ALL" && <span className="rounded-full bg-surface-2 px-2 py-0.5 font-semibold text-ink-2">{t(`offer.applies.${o.appliesTo}`)}{o.appliesTo === "PRODUCTS" ? ` · ${o.productIds?.length ?? 0}` : ` · ${o.categoryIds?.length ?? 0}`}</span>}
                    {o.stackable && <span className="rounded-full bg-violet-50 px-2 py-0.5 font-semibold text-violet-700">{t("offer.stacks")}</span>}
                  </div>
                  <div className="mt-auto">
                    <div className="flex items-center justify-between text-xs text-muted tnum"><span>{t("offer.used", { n: o.usageCount })}{o.maxUses ? ` / ${o.maxUses}` : ""}</span>{(o.discountGivenMinor ?? 0) > 0 && <span>{t("offer.given", { a: fmt(o.discountGivenMinor!) })}</span>}</div>
                    {pct !== null && <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-[color-mix(in_srgb,var(--ink)_8%,transparent)]"><div className="h-full rounded-full" style={{ width: `${pct}%`, background: `linear-gradient(90deg, ${l.from}, ${l.to})` }} /></div>}
                  </div>
                </div>
                {manage && (
                  <div className="flex items-center gap-1.5 px-3 py-2.5 border-t border-line">
                    {editable && <Link href={`/dashboard/offers/${o.id}`}><Button size="sm" variant="secondary"><Icon name="edit" className="h-3.5 w-3.5" />{t("offer.edit")}</Button></Link>}
                    {o.state !== "ENDED" && (o.active && !o.draft
                      ? <Button size="sm" variant="ghost" loading={act.loading} onClick={() => void act.run(o, "pause")}><Icon name="clock" className="h-3.5 w-3.5" />{t("offer.pause")}</Button>
                      : <Button size="sm" variant="ghost" loading={act.loading} onClick={() => void act.run(o, "resume")}><Icon name="play" className="h-3.5 w-3.5" />{o.draft ? t("offer.publish") : t("offer.resume")}</Button>)}
                    <span className="ms-auto">
                      <Menu label={t("common.more")} items={[
                        ...(o.kind === "OFFER" ? [{ label: t("offer.duplicate"), icon: "list", onSelect: () => void act.run(o, "dup") }] : []),
                        ...(o.kind === "OFFER" ? ["divider" as const] : []),
                        { label: t("offer.delete"), icon: "close", danger: true, onSelect: () => setConfirm(o) },
                      ]} trigger={({ toggle }) => <button type="button" onClick={toggle} aria-label={t("common.more")} className="grid h-9 w-9 place-items-center rounded-[10px] text-muted transition hover:bg-surface-2 hover:text-ink pointer-coarse:h-11 pointer-coarse:w-11"><Icon name="menu" className="h-[18px] w-[18px]" /></button>} />
                    </span>
                  </div>
                )}
              </article>
            );
          })}
        </div>
      )}
      <Sheet open={sheet} onClose={() => setSheet(false)} title={t("filter.title")}
        footer={<div className="flex gap-2"><Button variant="secondary" className="flex-1" onClick={() => setTypes([])}>{t("filter.clearAll")}</Button><Button variant="ink" className="flex-1" onClick={() => setSheet(false)}>{t("filter.show")}</Button></div>}>
        <FilterGroup label={t("offer.allTypes")}>{typeChips}</FilterGroup>
      </Sheet>
      <ConfirmDialog open={!!confirm} onClose={() => setConfirm(null)} danger loading={act.loading} onConfirm={() => confirm && void act.run(confirm, "del")}
        title={t("offer.deleteTitle")} body={t("offer.deleteBody", { name: confirm?.kind === "COUPON" ? (confirm.code ?? "") : (confirm?.name ?? "") })} confirmLabel={t("offer.delete")} cancelLabel={t("common.cancel")} />
    </>
  );
}
