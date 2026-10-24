"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { dateOnly, money } from "@/lib/format";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Alert, Badge, Button, Card, ErrorText, Field, Input, Loading, Select, Tabs, Textarea } from "../ui";

// ---- loyalty -----------------------------------------------------------------------------------------------------------------

type Loy = { settings: { enabled: boolean; earnPercent: number; minRedeem: number }; top: { phone: string; name?: string; points: number; lifetimePoints: number }[] };

export function LoyaltyTab() {
  const { t } = useI18n();
  const d = useApi<Loy>("store/loyalty");
  const [f, setF] = useState<{ on: string; pct: string; min: string } | null>(null);
  const cur = f ?? (d.data ? { on: d.data.settings.enabled ? "1" : "0", pct: String(d.data.settings.earnPercent), min: String(d.data.settings.minRedeem) } : null);
  const save = useAction(async () => { if (!cur) return; await api("store/loyalty", { method: "PUT", body: { enabled: cur.on === "1", earnPercent: Number(cur.pct), minRedeem: Number(cur.min) } }); setF(null); await d.reload(); });
  if (!cur || !d.data) return <Loading />;
  return (
    <div className="space-y-5">
      <Card className="space-y-4">
        <div><h2 className="font-medium">{t("gr.loyalty")}</h2><p className="mt-1 text-sm text-slate-500">{t("gr.loyaltyHint")}</p></div>
        <div className="grid gap-4 sm:grid-cols-3">
          <Field label={t("gr.enabled")}><Select value={cur.on} onChange={(e) => setF({ ...cur, on: e.target.value })}><option value="0">{t("wr.off")}</option><option value="1">{t("wr.on")}</option></Select></Field>
          <Field label={t("gr.earnPercent")} hint={t("gr.earnHint")}><Input dir="ltr" inputMode="numeric" value={cur.pct} onChange={(e) => setF({ ...cur, pct: e.target.value })} /></Field>
          <Field label={t("gr.minRedeem")}><Input dir="ltr" inputMode="numeric" value={cur.min} onChange={(e) => setF({ ...cur, min: e.target.value })} /></Field>
        </div>
        <ErrorText error={save.error} />
        <Button loading={save.loading} onClick={() => void save.run()}>{t("common.save")}</Button>
      </Card>
      <Card className="space-y-2">
        <h2 className="font-medium">{t("gr.topCustomers")}</h2>
        {d.data.top.length === 0 ? <p className="text-sm text-slate-500">{t("gr.noPoints")}</p> : d.data.top.map((c) => (
          <div key={c.phone} className="flex items-center justify-between border-t pt-2 text-sm first:border-0 first:pt-0"><span>{c.name ?? ""} <span dir="ltr" className="text-slate-500">{c.phone}</span></span><span><b>{c.points}</b> <span className="text-xs text-slate-500">/ {c.lifetimePoints}</span></span></div>
        ))}
      </Card>
    </div>
  );
}

// ---- segments and broadcasts -------------------------------------------------------------------------------------------------

type Seg = { segments: Record<string, number>; withWhatsappConsent: number };
type SegDetail = { customers: { phone: string; name?: string; orders: number; spent: number; lastOrder?: string; whatsappConsent: boolean }[]; preview: { audience: number; withConsent: number; reachableNow: number } };
type Hist = { id: string; segment: string; message: string; audience: number; sentCount: number; skippedWindow: number; skippedConsent: number; createdAt: string }[];
const KEYS = ["NEW", "REPEAT", "VIP", "AT_RISK", "LAPSED"] as const;

export function SegmentsTab() {
  const { t, locale, currency } = useI18n();
  const seg = useApi<Seg>("store/segments");
  const hist = useApi<Hist>("store/broadcasts");
  const [key, setKey] = useState<(typeof KEYS)[number]>("NEW");
  const det = useApi<SegDetail>(`store/segments/${key}`);
  const [msg, setMsg] = useState("");
  const [done, setDone] = useState<string | null>(null);
  const send = useAction(async () => {
    const r = await api<{ sent: number; skippedNoWindow: number; skippedNoConsent: number }>("store/broadcasts", { body: { segment: key, message: msg } });
    setDone(t("gr.sentResult", { sent: r.sent, window: r.skippedNoWindow, consent: r.skippedNoConsent }));
    setMsg(""); await hist.reload();
  });
  return (
    <div className="space-y-5">
      <div className="grid gap-3 sm:grid-cols-5">
        {KEYS.map((k) => (
          <button key={k} type="button" onClick={() => { setKey(k); setDone(null); }} className={`rounded-2xl border p-4 text-start transition ${k === key ? "border-brand bg-brand/5 ring-2 ring-brand/20" : "bg-white hover:bg-slate-50"}`}>
            <div className="text-xs text-slate-500">{t(`gr.seg.${k}`)}</div><div className="text-2xl font-bold">{seg.data?.segments[k] ?? 0}</div>
          </button>
        ))}
      </div>
      <p className="text-sm text-slate-500">{t(`gr.segHint.${key}`)}</p>
      <Card className="space-y-3">
        <h2 className="font-medium">{t("gr.broadcast")}</h2>
        {det.data && <p className="text-sm">{t("gr.reach", { audience: det.data.preview.audience, consent: det.data.preview.withConsent, now: det.data.preview.reachableNow })}</p>}
        <Alert tone="blue">{t("gr.freeOnly")}</Alert>
        <Field label={t("gr.message")} hint={t("gr.messageHint")}><Textarea rows={3} maxLength={800} value={msg} onChange={(e) => setMsg(e.target.value)} /></Field>
        <ErrorText error={send.error} />
        {done && <Alert tone="green">{done}</Alert>}
        <Button loading={send.loading} disabled={msg.trim().length < 5} onClick={() => { setDone(null); void send.run(); }}>{t("gr.send")}</Button>
      </Card>
      <Card className="space-y-2">
        <h2 className="font-medium">{t("gr.customers")}</h2>
        {(det.data?.customers ?? []).length === 0 ? <p className="text-sm text-slate-500">{t("gr.noCustomers")}</p> : det.data?.customers.map((c) => (
          <div key={c.phone} className="flex flex-wrap items-center justify-between gap-2 border-t pt-2 text-sm first:border-0 first:pt-0">
            <span>{c.name ?? ""} <span dir="ltr" className="text-slate-500">{c.phone}</span> {c.whatsappConsent && <Badge tone="green">WhatsApp</Badge>}</span>
            <span className="text-xs text-slate-500">{c.orders} · {money(c.spent, currency, locale)}{c.lastOrder ? ` · ${dateOnly(c.lastOrder, locale)}` : ""}</span>
          </div>
        ))}
      </Card>
      {(hist.data ?? []).length > 0 && (
        <Card className="space-y-2">
          <h2 className="font-medium">{t("gr.history")}</h2>
          {hist.data?.map((h) => <div key={h.id} className="border-t pt-2 text-sm first:border-0 first:pt-0"><div className="flex justify-between"><Badge tone="blue">{t(`gr.seg.${h.segment}`)}</Badge><span className="text-xs text-slate-500">{dateOnly(h.createdAt, locale)}</span></div><p className="mt-1">{h.message}</p><p className="text-xs text-slate-500">{t("gr.sentResult", { sent: h.sentCount, window: h.skippedWindow, consent: h.skippedConsent })}</p></div>)}
        </Card>
      )}
    </div>
  );
}

// ---- feeds and tracking ------------------------------------------------------------------------------------------------------

type Trk = { enabled: boolean; hasToken: boolean; pixelId: string | null; lastStatus?: string | null };

export function FeedsTab() {
  const { t } = useI18n();
  const trk = useApi<Trk>("store/tracking");
  const [token, setToken] = useState("");
  const [on, setOn] = useState<string | null>(null);
  const save = useAction(async () => { await api("store/tracking", { method: "PUT", body: { enabled: (on ?? (trk.data?.enabled ? "1" : "0")) === "1", token: token || undefined } }); setToken(""); setOn(null); await trk.reload(); });
  const origin = typeof window === "undefined" ? "" : window.location.origin;
  const feeds = [["gr.feedGoogle", "/feeds/google.xml"], ["gr.feedFacebook", "/feeds/facebook.csv"], ["gr.feedJson", "/feeds/products.json"], ["gr.feedLlms", "/llms.txt"]] as const;
  return (
    <div className="space-y-5">
      <Card className="space-y-3">
        <h2 className="font-medium">{t("gr.feeds")}</h2><p className="text-sm text-slate-500">{t("gr.feedsHint")}</p>
        {feeds.map(([label, path]) => (
          <div key={path} className="flex flex-wrap items-center justify-between gap-2 rounded-xl bg-slate-50 p-3 text-sm"><span className="font-medium">{t(label)}</span><a href={path} target="_blank" rel="noreferrer" dir="ltr" className="break-all text-brand hover:underline">{origin}{path}</a></div>
        ))}
      </Card>
      <Card className="space-y-4">
        <div><h2 className="font-medium">{t("gr.capi")}</h2><p className="mt-1 text-sm text-slate-500">{t("gr.capiHint")}</p></div>
        {trk.data && !trk.data.pixelId && <Alert tone="amber">{t("gr.noPixel")}</Alert>}
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label={t("gr.capiOn")}><Select value={on ?? (trk.data?.enabled ? "1" : "0")} onChange={(e) => setOn(e.target.value)}><option value="0">{t("wr.off")}</option><option value="1">{t("wr.on")}</option></Select></Field>
          <Field label={t("gr.capiToken")} hint={trk.data?.hasToken ? t("gr.tokenSaved") : undefined}><Input dir="ltr" type="password" autoComplete="off" value={token} onChange={(e) => setToken(e.target.value)} /></Field>
        </div>
        {trk.data?.lastStatus && <p className="text-xs text-slate-500">{t("gr.lastStatus")}: {trk.data.lastStatus}</p>}
        <ErrorText error={save.error} />
        <Button loading={save.loading} onClick={() => void save.run()}>{t("common.save")}</Button>
      </Card>
    </div>
  );
}


// ---- announcement A/B tests and the AI store builder -----------------------------------------------------------------------

type Exp = { id: string; name: string; variantA: string; variantB: string; status: string; winner?: string | null; viewsA: number; viewsB: number; ordersA: number; ordersB: number; rateA: number; rateB: number; enoughData: boolean; significant: boolean; recommendation?: string | null };

export function ExperimentsTab() {
  const { t } = useI18n();
  const list = useApi<Exp[]>("store/experiments");
  const [f, setF] = useState({ name: "", a: "", b: "" });
  const start = useAction(async () => { await api("store/experiments", { body: f }); setF({ name: "", a: "", b: "" }); await list.reload(); });
  const act = useAction(async (id: string, what: "stop" | "apply", winner?: string) => { await api(`store/experiments/${id}/${what}`, { body: winner ? { winner } : {} }); await list.reload(); });
  return (
    <div className="space-y-5">
      <Card className="space-y-3">
        <div><h2 className="font-medium">{t("gr.exp")}</h2><p className="mt-1 text-sm text-slate-500">{t("gr.expHint")}</p></div>
        <div className="grid gap-3 sm:grid-cols-3"><Field label={t("gr.expName")}><Input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} /></Field><Field label={t("gr.expA")}><Input value={f.a} onChange={(e) => setF({ ...f, a: e.target.value })} /></Field><Field label={t("gr.expB")}><Input value={f.b} onChange={(e) => setF({ ...f, b: e.target.value })} /></Field></div>
        <ErrorText error={start.error ?? act.error} />
        <Button loading={start.loading} disabled={f.a.trim().length < 3 || f.b.trim().length < 3} onClick={() => void start.run()}>{t("gr.expStart")}</Button>
      </Card>
      {(list.data ?? []).map((e) => (
        <Card key={e.id} className="space-y-3">
          <div className="flex items-center justify-between"><b>{e.name}</b><Badge tone={e.status === "RUNNING" ? "green" : "blue"}>{e.status === "RUNNING" ? t("gr.expRunning") : e.winner ? t("gr.expApplied", { w: e.winner }) : t("gr.expStopped")}</Badge></div>
          <div className="grid gap-3 sm:grid-cols-2">
            {(["A", "B"] as const).map((v) => {
              const views = v === "A" ? e.viewsA : e.viewsB, orders = v === "A" ? e.ordersA : e.ordersB, rate = v === "A" ? e.rateA : e.rateB;
              return <div key={v} className={`rounded-xl border p-3 text-sm ${e.recommendation === v ? "border-emerald-400 bg-emerald-50" : ""}`}><div className="font-semibold">{v}: {v === "A" ? e.variantA : e.variantB}</div><div className="mt-1 text-slate-600">{t("gr.expNumbers", { views, orders })} · <span dir="ltr">{rate}%</span></div>{e.recommendation === v && <div className="mt-1 text-xs font-bold text-emerald-700">{t("gr.expBetter")}</div>}</div>;
            })}
          </div>
          {!e.enoughData && e.status === "RUNNING" && <p className="text-xs text-slate-500">{t("gr.expMore")}</p>}
          {e.status === "RUNNING" && (
            <div className="flex flex-wrap gap-2"><Button size="sm" onClick={() => void act.run(e.id, "apply", "A")}>{t("gr.expUse", { v: "A" })}</Button><Button size="sm" onClick={() => void act.run(e.id, "apply", "B")}>{t("gr.expUse", { v: "B" })}</Button><Button size="sm" variant="ghost" onClick={() => void act.run(e.id, "stop")}>{t("gr.expStop")}</Button></div>
          )}
        </Card>
      ))}
    </div>
  );
}

type Built = { shortDescription: string; about: string; descriptionSaved: boolean; suggestedTemplate: string; products: { id: string; name: string; priceMinor: number }[] };

export function BuilderTab() {
  const { t, locale, currency } = useI18n();
  const [d, setD] = useState("");
  const [res, setRes] = useState<Built | null>(null);
  const go = useAction(async () => { setRes(await api<Built>("store/builder", { body: { description: d, language: locale } })); });
  return (
    <div className="space-y-5">
      <Card className="space-y-3">
        <div><h2 className="font-medium">{t("gr.builder")}</h2><p className="mt-1 text-sm text-slate-500">{t("gr.builderHint")}</p></div>
        <Field label={t("gr.builderLabel")}><Textarea rows={5} maxLength={1500} value={d} onChange={(e) => setD(e.target.value)} placeholder={t("gr.builderExample")} /></Field>
        <ErrorText error={go.error} />
        <Button loading={go.loading} disabled={d.trim().length < 15} onClick={() => void go.run()}>{t("gr.builderGo")}</Button>
      </Card>
      {res && (
        <Card className="space-y-2 text-sm">
          <Alert tone="green">{t("gr.builderDone", { n: res.products.length })}</Alert>
          {res.shortDescription && <p><b>{res.descriptionSaved ? t("gr.builderSaved") : t("gr.builderKept")}:</b> {res.shortDescription}</p>}
          <p>{t("gr.builderTemplate", { tpl: res.suggestedTemplate })}</p>
          <ul className="list-disc ps-5">{res.products.map((p) => <li key={p.id}>{p.name} · {money(p.priceMinor, currency, locale)}</li>)}</ul>
          <a href="/dashboard/products" className="font-semibold text-brand hover:underline">{t("gr.builderReview")}</a>
        </Card>
      )}
    </div>
  );
}

export default function GrowthView() {
  const { t } = useI18n();
  const [tab, setTab] = useState<"loyalty" | "segments" | "feeds" | "exp" | "builder">("segments");
  return (
    <div className="space-y-5">
      <Tabs tabs={[{ key: "segments", label: t("gr.tab.segments") }, { key: "loyalty", label: t("gr.tab.loyalty") }, { key: "feeds", label: t("gr.tab.feeds") }, { key: "exp", label: t("gr.tab.exp") }, { key: "builder", label: t("gr.tab.builder") }]} value={tab} onChange={setTab} />
      {tab === "segments" ? <SegmentsTab /> : tab === "loyalty" ? <LoyaltyTab /> : tab === "feeds" ? <FeedsTab /> : tab === "exp" ? <ExperimentsTab /> : <BuilderTab />}
    </div>
  );
}
