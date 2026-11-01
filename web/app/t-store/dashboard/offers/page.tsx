"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { api } from "@/lib/client";
import { money } from "@/lib/format";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { Chips, SearchBox } from "@/components/dashboard/filters";
import { DateRangePicker, rangeQuery, type DateRange } from "@/components/date-range";
import { OFFER_TYPES, TYPE_LOOK, offerSummary, type Offer } from "@/components/dashboard/offer-form";
import { Alert, Button, ErrorText, Loading, Modal, PageHeader, Stat, Tabs } from "@/components/ui";

type Stats = { states: Record<string, number>; orders30d: number; revenue30dMinor: number; discount30dMinor: number };
const STATES = ["ALL", "ACTIVE", "SCHEDULED", "PAUSED", "DRAFT", "ENDED"] as const;

function Countdown({ to }: { to: string }) {
  const { t } = useI18n();
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => { const id = setInterval(() => setNow(Date.now()), 30000); return () => clearInterval(id); }, []);
  const ms = new Date(to).getTime() - now;
  if (ms <= 0) return null;
  const d = Math.floor(ms / 86400000), h = Math.floor((ms % 86400000) / 3600000), m = Math.floor((ms % 3600000) / 60000);
  return <span className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-bold ${ms < 86400000 ? "bg-red-50 text-red-600" : "bg-slate-100 text-slate-600"}`}><Icon name="clock" className="h-3 w-3" />{d > 0 ? t("offer.leftDays", { d, h }) : t("offer.leftHours", { h, m })}</span>;
}

export default function Offers() {
  const { t, locale, currency } = useI18n();
  const { can } = useMe();
  const sp = useSearchParams();
  const created = sp.get("created") === "1";
  const [state, setState] = useState<(typeof STATES)[number]>("ALL");
  const [types, setTypes] = useState<string[]>(() => (sp.get("type") ? [sp.get("type")!] : []));
  const [q, setQ] = useState("");
  const [range, setRange] = useState<DateRange>({});
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
    await reload();
  });
  const manage = can("coupon.manage");
  const s = stats.data;

  return (
    <>
      <PageHeader title={t("nav.offers")} subtitle={t("offer.listSubtitle")} actions={manage && <Link href="/dashboard/offers/new"><Button><Icon name="plus" className="h-4 w-4" />{t("offer.new")}</Button></Link>} />
      {created && <div className="mb-4"><Alert tone="green">{t("offer.createdMsg")}</Alert></div>}
      <div className="mb-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label={t("offer.stat.running")} value={s?.states.ACTIVE ?? 0} icon="sparkles" />
        <Stat label={t("offer.stat.orders")} value={s?.orders30d ?? 0} icon="box" tone="green" />
        <Stat label={t("offer.stat.revenue")} value={fmt(s?.revenue30dMinor ?? 0)} icon="trending" tone="green" />
        <Stat label={t("offer.stat.given")} value={fmt(s?.discount30dMinor ?? 0)} icon="coupon" tone="amber" />
      </div>
      <div className="mb-3 flex flex-wrap items-center gap-2 rounded-2xl bg-white p-3 shadow-card">
        <SearchBox value={q} onChange={setQ} placeholder={t("offer.search")} />
        <DateRangePicker value={range} onChange={setRange} label={t("offer.runningIn")} />
      </div>
      <div className="mb-3 overflow-x-auto"><Tabs value={state} onChange={setState} tabs={STATES.map((k) => ({ key: k, label: t(`offer.state.${k}`), count: k === "ALL" ? undefined : s?.states[k] }))} /></div>
      <div className="mb-4"><Chips allLabel={t("offer.allTypes")} options={OFFER_TYPES.map((ty) => ({ value: ty, label: `${TYPE_LOOK[ty].emoji} ${t(`offer.type.${ty}`)}` }))} value={types} onChange={setTypes} /></div>
      <ErrorText error={list.error ?? act.error} />
      {list.loading && !list.data ? <Loading /> : list.data && list.data.length === 0 ? (
        <div className="animate-fade-up rounded-3xl border border-dashed border-slate-300 bg-white/70 p-10 text-center">
          <p className="text-5xl">🎁</p>
          <p className="mt-3 text-lg font-bold text-ink">{t("offer.emptyTitle")}</p>
          <p className="mx-auto mt-1 max-w-md text-sm text-slate-500">{t("offer.emptyBody")}</p>
          {manage && <Link href="/dashboard/offers/new" className="mt-5 inline-block"><Button>{t("offer.new")}</Button></Link>}
        </div>
      ) : (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {list.data?.map((o, i) => {
            const l = TYPE_LOOK[o.type];
            const pct = o.maxUses ? Math.min(100, Math.round((o.usageCount / o.maxUses) * 100)) : null;
            const editable = o.kind === "OFFER" && manage;
            return (
              <article key={o.kind + o.id} style={{ animationDelay: `${Math.min(i, 8) * 40}ms` }} className="group flex animate-fade-up flex-col overflow-hidden rounded-2xl bg-white shadow-card ring-1 ring-slate-100 transition hover:-translate-y-0.5 hover:shadow-lift">
                <div className="relative p-4 text-white" style={{ background: `linear-gradient(120deg, ${l.from}, ${l.to})` }}>
                  <div className="flex items-start justify-between gap-2">
                    <span className="text-3xl drop-shadow">{l.emoji}</span>
                    <span className={`rounded-full px-2.5 py-1 text-[11px] font-bold backdrop-blur ${o.state === "ACTIVE" ? "bg-white text-emerald-700" : "bg-black/20 text-white"}`}>{o.state === "ACTIVE" && <span className="me-1 inline-block h-1.5 w-1.5 animate-pulse rounded-full bg-emerald-500" />}{t(`offer.state.${o.state}`)}</span>
                  </div>
                  <p className="mt-2 text-xs font-semibold opacity-90">{t(`offer.type.${o.type}`)}</p>
                  <p className="text-lg font-extrabold leading-tight">{o.kind === "COUPON" ? <span className="font-mono tracking-widest" dir="ltr">{o.code}</span> : o.name}</p>
                </div>
                <div className="flex flex-1 flex-col gap-3 p-4">
                  <p className="text-sm font-semibold text-slate-800">{offerSummary(o, t, fmt)}</p>
                  <div className="flex flex-wrap items-center gap-2 text-xs text-slate-500">
                    {o.endsAt && o.state !== "ENDED" && <Countdown to={o.endsAt} />}
                    {o.startsAt && o.state === "SCHEDULED" && <span>{t("offer.startsOn", { d: new Date(o.startsAt).toLocaleString(locale === "ar" ? "ar-EG" : "en-GB", { dateStyle: "medium", timeStyle: "short" }) })}</span>}
                    {o.appliesTo && o.appliesTo !== "ALL" && <span className="rounded-full bg-slate-100 px-2 py-0.5 font-semibold">{t(`offer.applies.${o.appliesTo}`)}{o.appliesTo === "PRODUCTS" ? ` · ${o.productIds?.length ?? 0}` : ` · ${o.categoryIds?.length ?? 0}`}</span>}
                    {o.stackable && <span className="rounded-full bg-violet-50 px-2 py-0.5 font-semibold text-violet-700">{t("offer.stacks")}</span>}
                  </div>
                  <div className="mt-auto">
                    <div className="flex items-center justify-between text-xs text-slate-500"><span>{t("offer.used", { n: o.usageCount })}{o.maxUses ? ` / ${o.maxUses}` : ""}</span>{(o.discountGivenMinor ?? 0) > 0 && <span>{t("offer.given", { a: fmt(o.discountGivenMinor!) })}</span>}</div>
                    {pct !== null && <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-slate-100"><div className="h-full rounded-full" style={{ width: `${pct}%`, background: `linear-gradient(90deg, ${l.from}, ${l.to})` }} /></div>}
                  </div>
                  {manage && (
                    <div className="flex flex-wrap gap-1.5 border-t border-slate-100 pt-3">
                      {editable && <Link href={`/dashboard/offers/${o.id}`}><Button size="sm" variant="secondary"><Icon name="edit" className="h-3.5 w-3.5" />{t("offer.edit")}</Button></Link>}
                      {o.state !== "ENDED" && (o.active && !o.draft
                        ? <Button size="sm" variant="ghost" onClick={() => act.run(o, "pause")}>⏸ {t("offer.pause")}</Button>
                        : <Button size="sm" variant="ghost" onClick={() => act.run(o, "resume")}>▶ {o.draft ? t("offer.publish") : t("offer.resume")}</Button>)}
                      {o.kind === "OFFER" && <Button size="sm" variant="ghost" onClick={() => act.run(o, "dup")}>⧉ {t("offer.duplicate")}</Button>}
                      <Button size="sm" variant="ghost" className="ms-auto !text-red-600" onClick={() => setConfirm(o)}><Icon name="close" className="h-3.5 w-3.5" /></Button>
                    </div>
                  )}
                </div>
              </article>
            );
          })}
        </div>
      )}
      <Modal open={!!confirm} onClose={() => setConfirm(null)} title={t("offer.deleteTitle")}>
        <p className="text-sm text-slate-600">{t("offer.deleteBody", { name: confirm?.name ?? "" })}</p>
        <div className="mt-5 flex gap-2"><Button variant="danger" loading={act.loading} onClick={() => confirm && act.run(confirm, "del")}>{t("offer.delete")}</Button><Button variant="secondary" onClick={() => setConfirm(null)}>{t("common.cancel")}</Button></div>
      </Modal>
    </>
  );
}
