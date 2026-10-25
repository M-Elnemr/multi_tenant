"use client";

import { useMemo, useState } from "react";
import { useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { AreaChart, Badge, Button, Card, Empty, ErrorText, Input, Loading, Modal, PageHeader, Section, Stat, Table, Tabs, Td } from "@/components/ui";

type Sums = { requests: number; instant: number; modelCalls: number; input: number; output: number; cached: number };
type Prices = { provider: string; inputPerMillion: number; outputPerMillion: number; cachedPerMillion: number };
type Shop = Sums & { tenantId: string | null; name: string; conversations: number };
type Day = {
  day: string; prices: Prices; totals: Sums; byHour: (Sums & { hour: number })[]; series: (Sums & { day: string })[]; shops: Shop[];
  byFeature: (Sums & { feature: string })[]; byChannel: (Sums & { channel: string })[]; byStep: { step: string; feature: string; rounds: number; input: number; output: number; cached: number }[];
};
type Conv = Sums & { conversationId: string; channel: string | null; firstAt: string; lastAt: string; firstMessage: string | null };
type Other = { id: number; createdAt: string; feature: string; modelCalls: number; input: number; output: number; cached: number };
type ShopDay = Omit<Day, "shops" | "series" | "byChannel"> & { shop: { id: string; name: string }; conversations: Conv[]; other: Other[] };
type Round = { tools: string[]; in: number; out: number; cached: number };
type Reply = { id: number; createdAt: string; feature: string; instant: boolean; preview: string | null; modelCalls: number; input: number; output: number; cached: number; calls: Round[] };
type ConvDetail = { conversationId: string; channel: string | null; prices: Prices; replies: Reply[] };

const cairoToday = () => new Intl.DateTimeFormat("en-CA", { timeZone: "Africa/Cairo" }).format(new Date());
const shiftDay = (d: string, by: number) => { const x = new Date(`${d}T12:00:00Z`); x.setUTCDate(x.getUTCDate() + by); return x.toISOString().slice(0, 10); };
const total = (s: { input: number; output: number }) => s.input + s.output;

function download(name: string, rows: (string | number)[][]) {
  const csv = "﻿" + rows.map((r) => r.map((c) => `"${String(c).replace(/"/g, '""')}"`).join(",")).join("\r\n");
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
  a.download = name;
  a.click();
  URL.revokeObjectURL(a.href);
}

/** The platform admin sees how many AI tokens each shop used on a day and where they went: shops, conversations, replies and the model rounds behind each reply. */
export default function AiUsage() {
  const { t, locale } = useI18n();
  const [day, setDay] = useState(cairoToday);
  const [shopId, setShopId] = useState<string | null>(null);
  const [convId, setConvId] = useState<string | null>(null);
  const [tab, setTab] = useState<"shops" | "steps" | "features" | "channels">("shops");
  const [shopTab, setShopTab] = useState<"conversations" | "steps" | "features" | "other">("conversations");
  const [price, setPrice] = useState<{ i: string; o: string; c: string } | null>(null);

  const data = useApi<Day>(`platform/ai-usage?day=${day}`);
  const shop = useApi<ShopDay>(shopId ? `platform/ai-usage/shops/${shopId}?day=${day}` : null);
  const conv = useApi<ConvDetail>(convId ? `platform/ai-usage/conversations/${convId}` : null);

  const n = (v: number) => v.toLocaleString(locale);
  const pct = (a: number, b: number) => (b > 0 ? `${Math.round((a / b) * 100)}%` : "–");
  const p = data.data?.prices;
  const pin = price ? Number(price.i) || 0 : p?.inputPerMillion ?? 0;
  const pout = price ? Number(price.o) || 0 : p?.outputPerMillion ?? 0;
  const pc = price ? Number(price.c) || 0 : p?.cachedPerMillion ?? 0;
  const cost = (s: { input: number; output: number; cached: number }) => ((Math.max(0, s.input - s.cached) * pin + s.cached * pc + s.output * pout) / 1e6);
  const usd = (v: number) => `$${v < 1 ? v.toFixed(4) : v.toFixed(2)}`;
  const time = (iso: string) => new Date(iso).toLocaleTimeString(locale, { hour: "2-digit", minute: "2-digit", timeZone: "Africa/Cairo" });
  const feature = (f: string) => { const k = `aiu.f.${f}`; const v = t(k); return v === k ? f : v; };
  const hourMax = useMemo(() => Math.max(1, ...(data.data?.byHour ?? []).map(total)), [data.data]);

  const stepTable = (rows: Day["byStep"]) => (
    <Table head={[t("aiu.step"), t("aiu.feature"), t("aiu.rounds"), t("aiu.input"), t("aiu.output"), t("aiu.cached"), t("aiu.cost")]}>
      {rows.map((r, i) => (
        <tr key={i}>
          <Td className="font-mono text-xs">{r.step === "(final answer)" ? t("aiu.finalAnswer") : r.step}</Td><Td>{feature(r.feature)}</Td><Td>{n(r.rounds)}</Td><Td>{n(r.input)}</Td><Td>{n(r.output)}</Td><Td>{pct(r.cached, r.input)}</Td><Td>{usd(cost(r))}</Td>
        </tr>
      ))}
    </Table>
  );
  const featureTable = (rows: (Sums & { feature: string })[]) => (
    <Table head={[t("aiu.feature"), t("aiu.requests"), t("aiu.instant"), t("aiu.input"), t("aiu.output"), t("aiu.cached"), t("aiu.cost")]}>
      {rows.map((r) => (
        <tr key={r.feature}><Td>{feature(r.feature)}</Td><Td>{n(r.requests)}</Td><Td>{n(r.instant)}</Td><Td>{n(r.input)}</Td><Td>{n(r.output)}</Td><Td>{pct(r.cached, r.input)}</Td><Td>{usd(cost(r))}</Td></tr>
      ))}
    </Table>
  );

  const d = data.data;
  const sd = shop.data;
  return (
    <>
      <PageHeader title={t("aiu.title")} subtitle={t("aiu.subtitle")} actions={
        <div className="flex flex-wrap items-center gap-2">
          <Button variant="secondary" size="sm" onClick={() => { setDay(shiftDay(day, -1)); setConvId(null); }}>‹</Button>
          <Input type="date" value={day} max={cairoToday()} onChange={(e) => { if (e.target.value) { setDay(e.target.value); setConvId(null); } }} className="w-44" aria-label={t("aiu.day")} />
          <Button variant="secondary" size="sm" onClick={() => { setDay(shiftDay(day, 1)); setConvId(null); }} disabled={day >= cairoToday()}>›</Button>
          <Button variant="secondary" size="sm" onClick={() => setDay(cairoToday())}>{t("aiu.today")}</Button>
        </div>} />
      <ErrorText error={data.error} />
      {!d ? <Loading /> : (
        <div className="space-y-6">
          <div className="grid grid-cols-2 gap-4 lg:grid-cols-5">
            <Stat label={t("aiu.tokens")} value={n(total(d.totals))} icon="chart" hint={`${n(d.totals.input)} ${t("aiu.in")} · ${n(d.totals.output)} ${t("aiu.out")}`} />
            <Stat label={t("aiu.requests")} value={n(d.totals.requests)} icon="chat" hint={`${n(d.totals.modelCalls)} ${t("aiu.modelCalls")}`} />
            <Stat label={t("aiu.instant")} value={n(d.totals.instant)} icon="check" tone="green" hint={t("aiu.instantHint")} />
            <Stat label={t("aiu.cacheHit")} value={pct(d.totals.cached, d.totals.input)} icon="sparkles" tone="amber" hint={`${n(d.totals.cached)} ${t("aiu.cachedTokens")}`} />
            <Stat label={t("aiu.cost")} value={usd(cost(d.totals))} icon="wallet" tone="red" hint={d.prices.provider} />
          </div>

          <Card>
            <p className="mb-3 text-sm font-semibold text-ink">{t("aiu.prices")}</p>
            <div className="grid grid-cols-3 gap-3 sm:max-w-md">
              <label className="text-xs text-slate-500">{t("aiu.priceIn")}<Input type="number" step="0.01" min="0" value={price?.i ?? String(d.prices.inputPerMillion)} onChange={(e) => setPrice({ i: e.target.value, o: price?.o ?? String(d.prices.outputPerMillion), c: price?.c ?? String(d.prices.cachedPerMillion) })} /></label>
              <label className="text-xs text-slate-500">{t("aiu.priceOut")}<Input type="number" step="0.01" min="0" value={price?.o ?? String(d.prices.outputPerMillion)} onChange={(e) => setPrice({ i: price?.i ?? String(d.prices.inputPerMillion), o: e.target.value, c: price?.c ?? String(d.prices.cachedPerMillion) })} /></label>
              <label className="text-xs text-slate-500">{t("aiu.priceCached")}<Input type="number" step="0.001" min="0" value={price?.c ?? String(d.prices.cachedPerMillion)} onChange={(e) => setPrice({ i: price?.i ?? String(d.prices.inputPerMillion), o: price?.o ?? String(d.prices.outputPerMillion), c: e.target.value })} /></label>
            </div>
            <p className="mt-2 text-xs text-slate-500">{t("aiu.pricesHint")}</p>
          </Card>

          <div className="grid gap-6 lg:grid-cols-2">
            <Section title={t("aiu.byHour")}>
              <div className="flex h-36 items-end gap-1" dir="ltr">
                {d.byHour.map((h) => (
                  <div key={h.hour} className="group relative flex h-full flex-1 items-end" title={`${h.hour}:00 · ${n(total(h))}`}>
                    <div className="w-full rounded-t bg-brand/70 transition group-hover:bg-brand" style={{ height: `${Math.max(2, (total(h) / hourMax) * 100)}%` }} />
                  </div>
                ))}
              </div>
              <div className="mt-1 flex justify-between text-[10px] text-slate-400" dir="ltr"><span>00</span><span>06</span><span>12</span><span>18</span><span>23</span></div>
            </Section>
            <Section title={t("aiu.last30")}>
              <AreaChart points={d.series.map((s) => ({ label: s.day, value: total(s) }))} format={n} />
              {d.series.length < 2 && <p className="text-sm text-slate-500">{t("aiu.notEnoughDays")}</p>}
            </Section>
          </div>

          <div className="flex flex-wrap items-center justify-between gap-3">
            <Tabs tabs={[{ key: "shops", label: t("aiu.shops"), count: d.shops.length }, { key: "steps", label: t("aiu.steps") }, { key: "features", label: t("aiu.features") }, { key: "channels", label: t("aiu.channels") }]} value={tab} onChange={setTab} />
            {tab === "shops" && d.shops.length > 0 && <Button variant="secondary" size="sm" onClick={() => download(`ai-usage-${day}.csv`, [["shop", "conversations", "requests", "instant", "model_calls", "input", "output", "cached", "est_cost_usd"], ...d.shops.map((s) => [s.name, s.conversations, s.requests, s.instant, s.modelCalls, s.input, s.output, s.cached, cost(s).toFixed(4)])])}>{t("aiu.csv")}</Button>}
          </div>

          {tab === "shops" && (d.shops.length === 0 ? <Empty>{t("aiu.none")}</Empty> : (
            <Table head={[t("aiu.shop"), t("aiu.conversations"), t("aiu.requests"), t("aiu.instant"), t("aiu.input"), t("aiu.output"), t("aiu.cached"), t("aiu.tokens"), t("aiu.cost")]}>
              {d.shops.map((s) => (
                <tr key={s.tenantId ?? "none"} className={s.tenantId ? "cursor-pointer" : ""} onClick={() => { if (s.tenantId) { setShopId(s.tenantId); setShopTab("conversations"); setConvId(null); } }}>
                  <Td className="font-semibold text-brand-dark">{s.tenantId ? s.name : t("aiu.platformLevel")}</Td><Td>{n(s.conversations)}</Td><Td>{n(s.requests)}</Td><Td>{n(s.instant)}</Td>
                  <Td>{n(s.input)}</Td><Td>{n(s.output)}</Td><Td>{pct(s.cached, s.input)}</Td><Td className="font-semibold">{n(total(s))}</Td><Td>{usd(cost(s))}</Td>
                </tr>
              ))}
            </Table>
          ))}
          {tab === "steps" && <><p className="text-xs text-slate-500">{t("aiu.stepsHint")}</p>{d.byStep.length ? stepTable(d.byStep) : <Empty>{t("aiu.none")}</Empty>}</>}
          {tab === "features" && (d.byFeature.length ? featureTable(d.byFeature) : <Empty>{t("aiu.none")}</Empty>)}
          {tab === "channels" && (d.byChannel.length === 0 ? <Empty>{t("aiu.none")}</Empty> : (
            <Table head={[t("aiu.channel"), t("aiu.requests"), t("aiu.instant"), t("aiu.input"), t("aiu.output"), t("aiu.cached"), t("aiu.cost")]}>
              {d.byChannel.map((c) => <tr key={c.channel}><Td>{c.channel}</Td><Td>{n(c.requests)}</Td><Td>{n(c.instant)}</Td><Td>{n(c.input)}</Td><Td>{n(c.output)}</Td><Td>{pct(c.cached, c.input)}</Td><Td>{usd(cost(c))}</Td></tr>)}
            </Table>
          ))}

          {shopId && (
            <Section title={`${sd?.shop.name ?? "…"} · ${day}`} action={<Button variant="secondary" size="sm" onClick={() => { setShopId(null); setConvId(null); }}>{t("aiu.close")}</Button>}>
              <ErrorText error={shop.error} />
              {!sd ? <Loading /> : (
                <div className="space-y-4">
                  <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                    <Stat label={t("aiu.tokens")} value={n(total(sd.totals))} hint={`${n(sd.totals.input)} ${t("aiu.in")} · ${n(sd.totals.output)} ${t("aiu.out")}`} />
                    <Stat label={t("aiu.requests")} value={n(sd.totals.requests)} hint={`${n(sd.totals.modelCalls)} ${t("aiu.modelCalls")}`} />
                    <Stat label={t("aiu.instant")} value={n(sd.totals.instant)} tone="green" />
                    <Stat label={t("aiu.cost")} value={usd(cost(sd.totals))} tone="red" hint={`${t("aiu.cacheHit")} ${pct(sd.totals.cached, sd.totals.input)}`} />
                  </div>
                  <div className="flex flex-wrap items-center justify-between gap-3">
                    <Tabs tabs={[{ key: "conversations", label: t("aiu.conversations"), count: sd.conversations.length }, { key: "steps", label: t("aiu.steps") }, { key: "features", label: t("aiu.features") }, { key: "other", label: t("aiu.other"), count: sd.other.length }]} value={shopTab} onChange={setShopTab} />
                    {shopTab === "conversations" && sd.conversations.length > 0 && <Button variant="secondary" size="sm" onClick={() => download(`ai-usage-${sd.shop.name}-${day}.csv`, [["conversation", "channel", "first_message", "from", "to", "requests", "instant", "model_calls", "input", "output", "cached", "est_cost_usd"], ...sd.conversations.map((c) => [c.conversationId, c.channel ?? "", c.firstMessage ?? "", c.firstAt, c.lastAt, c.requests, c.instant, c.modelCalls, c.input, c.output, c.cached, cost(c).toFixed(4)])])}>{t("aiu.csv")}</Button>}
                  </div>
                  {shopTab === "conversations" && (sd.conversations.length === 0 ? <Empty>{t("aiu.none")}</Empty> : (
                    <Table head={[t("aiu.firstMessage"), t("aiu.channel"), t("aiu.time"), t("aiu.requests"), t("aiu.instant"), t("aiu.input"), t("aiu.output"), t("aiu.tokens"), t("aiu.cost")]}>
                      {sd.conversations.map((c) => (
                        <tr key={c.conversationId} className="cursor-pointer" onClick={() => setConvId(c.conversationId)}>
                          <Td className="max-w-xs truncate font-semibold text-brand-dark">{c.firstMessage ?? "—"}</Td><Td>{c.channel ?? "—"}</Td><Td>{time(c.firstAt)}–{time(c.lastAt)}</Td>
                          <Td>{n(c.requests)}</Td><Td>{n(c.instant)}</Td><Td>{n(c.input)}</Td><Td>{n(c.output)}</Td><Td className="font-semibold">{n(total(c))}</Td><Td>{usd(cost(c))}</Td>
                        </tr>
                      ))}
                    </Table>
                  ))}
                  {shopTab === "steps" && (sd.byStep.length ? stepTable(sd.byStep) : <Empty>{t("aiu.none")}</Empty>)}
                  {shopTab === "features" && (sd.byFeature.length ? featureTable(sd.byFeature) : <Empty>{t("aiu.none")}</Empty>)}
                  {shopTab === "other" && (sd.other.length === 0 ? <Empty>{t("aiu.none")}</Empty> : (
                    <Table head={[t("aiu.time"), t("aiu.feature"), t("aiu.modelCalls"), t("aiu.input"), t("aiu.output"), t("aiu.cost")]}>
                      {sd.other.map((o) => <tr key={o.id}><Td>{time(o.createdAt)}</Td><Td>{feature(o.feature)}</Td><Td>{n(o.modelCalls)}</Td><Td>{n(o.input)}</Td><Td>{n(o.output)}</Td><Td>{usd(cost(o))}</Td></tr>)}
                    </Table>
                  ))}
                </div>
              )}
            </Section>
          )}
        </div>
      )}

      <Modal open={!!convId} onClose={() => setConvId(null)} title={t("aiu.conversation")} wide>
        <ErrorText error={conv.error} />
        {!conv.data ? <Loading /> : (
          <div className="space-y-3">
            <p className="text-xs text-slate-500">{conv.data.channel ?? ""} · {n(conv.data.replies.reduce((s, r) => s + r.input + r.output, 0))} {t("aiu.tokens")}</p>
            {conv.data.replies.map((r) => (
              <div key={r.id} className="rounded-xl border border-slate-100 p-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <p className="min-w-0 truncate text-sm font-semibold text-ink">{r.preview ?? "—"}</p>
                  <div className="flex items-center gap-2 text-xs text-slate-500">
                    <span>{time(r.createdAt)}</span>{r.instant ? <Badge tone="green">{t("aiu.instantBadge")}</Badge> : <Badge>{n(r.input + r.output)} {t("aiu.tokens")}</Badge>}
                    {!r.instant && <span>{usd(cost(r))}</span>}
                  </div>
                </div>
                {r.calls.length > 0 && (
                  <ul className="mt-2 space-y-1 text-xs" dir="ltr">
                    {r.calls.map((c, i) => (
                      <li key={i} className="flex flex-wrap items-center gap-x-3 rounded-lg bg-slate-50 px-2.5 py-1.5">
                        <span className="font-semibold text-slate-500">#{i + 1}</span>
                        <span className="font-mono">{c.tools.length ? c.tools.join(", ") : t("aiu.finalAnswer")}</span>
                        <span className="ms-auto text-slate-500">{n(c.in)} {t("aiu.in")} ({n(c.cached)} {t("aiu.cached")}) · {n(c.out)} {t("aiu.out")}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            ))}
          </div>
        )}
      </Modal>
    </>
  );
}
