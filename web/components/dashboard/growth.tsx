"use client";

import { useState } from "react";
import { useSection } from "./section";
import { api } from "@/lib/client";
import { money } from "@/lib/format";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Icon } from "../icons";
import { ConfirmDialog } from "../overlay";
import { useToast } from "../toast";
import SegmentsTab from "./growth/segments-screen";
import { Alert, Avatar, Badge, Button, Empty, ErrorText, Field, Input, Select, Textarea } from "../ui";
import { Bar, CardSkeletons, CopyField, Panel, PanelHead, Rise, SwitchRow } from "./marketing/kit";

// ---- loyalty -----------------------------------------------------------------------------------------------------------------

type Loy = { settings: { enabled: boolean; earnPercent: number; minRedeem: number }; top: { phone: string; name?: string; points: number; lifetimePoints: number }[] };

export function LoyaltyTab() {
  const { t, locale } = useI18n();
  const toast = useToast();
  const d = useApi<Loy>("store/loyalty");
  const [f, setF] = useState<{ on: string; pct: string; min: string } | null>(null);
  const cur = f ?? (d.data ? { on: d.data.settings.enabled ? "1" : "0", pct: String(d.data.settings.earnPercent), min: String(d.data.settings.minRedeem) } : null);
  const save = useAction(async () => {
    if (!cur) return;
    await api("store/loyalty", { method: "PUT", body: { enabled: cur.on === "1", earnPercent: Number(cur.pct), minRedeem: Number(cur.min) } });
    setF(null); toast(t("common.saved")); await d.reload();
  });
  if (!cur || !d.data) return <div className="grid gap-5 lg:grid-cols-[1.1fr_0.9fr]"><div className="skeleton h-80 rounded-[18px]" /><div className="skeleton h-80 rounded-[18px]" /></div>;
  const n = (x: number) => x.toLocaleString(locale === "ar" ? "ar-EG" : "en-US");
  const max = Math.max(1, ...d.data.top.map((c) => c.lifetimePoints));
  return (
    <div className="grid gap-5 lg:grid-cols-[1.1fr_0.9fr] lg:items-start">
      <Panel className="space-y-5 p-5">
        <PanelHead icon="award" title={t("gr.loyalty")} hint={t("gr.loyaltyHint")} />
        <SwitchRow title={t("gr.enabled")} checked={cur.on === "1"} onChange={(v) => setF({ ...cur, on: v ? "1" : "0" })} />
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label={t("gr.earnPercent")} hint={t("gr.earnHint")}><Input dir="ltr" inputMode="numeric" value={cur.pct} onChange={(e) => setF({ ...cur, pct: e.target.value })} /></Field>
          <Field label={t("gr.minRedeem")}><Input dir="ltr" inputMode="numeric" value={cur.min} onChange={(e) => setF({ ...cur, min: e.target.value })} /></Field>
        </div>
        <ErrorText error={save.error} />
        <div className="flex justify-end"><Button variant="ink" loading={save.loading} onClick={() => void save.run()}>{t("common.save")}</Button></div>
      </Panel>
      <Panel className="p-5">
        <PanelHead icon="users" title={t("gr.topCustomers")} />
        {d.data.top.length === 0 ? <div className="mt-4"><Empty compact icon="award">{t("gr.noPoints")}</Empty></div> : (
          <ol className="mt-4 space-y-3">
            {d.data.top.map((c, i) => (
              <li key={c.phone} className="flex items-center gap-3">
                <span className="grid h-6 w-6 shrink-0 place-items-center rounded-full bg-brand-soft text-[11px] font-bold text-brand">{i + 1}</span>
                <Avatar name={c.name || c.phone} className="h-8 w-8 text-[12px]" />
                <div className="min-w-0 flex-1">
                  <p className="flex items-baseline justify-between gap-2 text-[13px]"><span className="truncate font-semibold text-ink">{c.name || <span dir="ltr">{c.phone}</span>}</span><span className="shrink-0 tnum"><b>{n(c.points)}</b> <span className="text-[11.5px] text-faint">/ {n(c.lifetimePoints)}</span></span></p>
                  <div className="mt-1"><Bar value={c.lifetimePoints} max={max} /></div>
                </div>
              </li>
            ))}
          </ol>
        )}
      </Panel>
    </div>
  );
}

// ---- feeds and tracking ------------------------------------------------------------------------------------------------------

type Trk = { enabled: boolean; hasToken: boolean; pixelId: string | null; lastStatus?: string | null };

export function FeedsTab() {
  const { t } = useI18n();
  const toast = useToast();
  const trk = useApi<Trk>("store/tracking");
  const [token, setToken] = useState("");
  const [on, setOn] = useState<string | null>(null);
  const save = useAction(async () => { await api("store/tracking", { method: "PUT", body: { enabled: (on ?? (trk.data?.enabled ? "1" : "0")) === "1", token: token || undefined } }); setToken(""); setOn(null); toast(t("common.saved")); await trk.reload(); });
  const origin = typeof window === "undefined" ? "" : window.location.origin;
  const feeds = [["gr.feedGoogle", "/feeds/google.xml", "search"], ["gr.feedFacebook", "/feeds/facebook.csv", "globe"], ["gr.feedJson", "/feeds/products.json", "file"], ["gr.feedLlms", "/llms.txt", "sparkles"]] as const;
  const enabled = (on ?? (trk.data?.enabled ? "1" : "0")) === "1";
  return (
    <div className="grid gap-5 lg:grid-cols-2 lg:items-start">
      <Panel className="space-y-4 p-5">
        <PanelHead icon="link" title={t("gr.feeds")} hint={t("gr.feedsHint")} />
        <ul className="space-y-3">
          {feeds.map(([label, path, icon]) => (
            <li key={path} className="space-y-1.5">
              <p className="flex items-center gap-2 text-[13px] font-semibold text-ink"><Icon name={icon} className="h-4 w-4 text-muted" />{t(label)}</p>
              <CopyField value={`${origin}${path}`} href={path} />
            </li>
          ))}
        </ul>
      </Panel>
      <Panel className="space-y-4 p-5">
        <PanelHead icon="shield" title={t("gr.capi")} hint={t("gr.capiHint")} />
        {trk.data && !trk.data.pixelId && <Alert tone="amber">{t("gr.noPixel")}</Alert>}
        <SwitchRow title={t("gr.capiOn")} checked={enabled} onChange={(v) => setOn(v ? "1" : "0")} />
        <Field label={t("gr.capiToken")} hint={trk.data?.hasToken ? t("gr.tokenSaved") : undefined}><Input dir="ltr" type="password" autoComplete="off" value={token} onChange={(e) => setToken(e.target.value)} /></Field>
        {trk.data?.lastStatus && <p className="text-[12px] text-muted">{t("gr.lastStatus")}: {trk.data.lastStatus}</p>}
        <ErrorText error={save.error} />
        <div className="flex justify-end"><Button variant="ink" loading={save.loading} onClick={() => void save.run()}>{t("common.save")}</Button></div>
      </Panel>
    </div>
  );
}

// ---- announcement A/B tests and the AI store builder -----------------------------------------------------------------------

type Exp = { id: string; name: string; variantA: string; variantB: string; status: string; winner?: string | null; viewsA: number; viewsB: number; ordersA: number; ordersB: number; rateA: number; rateB: number; enoughData: boolean; significant: boolean; recommendation?: string | null };

export function ExperimentsTab() {
  const { t } = useI18n();
  const toast = useToast();
  const list = useApi<Exp[]>("store/experiments");
  const [f, setF] = useState({ name: "", a: "", b: "" });
  const [stopId, setStopId] = useState<string | null>(null);
  const start = useAction(async () => { await api("store/experiments", { body: f }); setF({ name: "", a: "", b: "" }); toast(t("grx.expStarted")); await list.reload(); });
  const act = useAction(async (id: string, what: "stop" | "apply", winner?: string) => { await api(`store/experiments/${id}/${what}`, { body: winner ? { winner } : {} }); toast(t("common.saved")); await list.reload(); });
  return (
    <div className="space-y-5">
      <Panel className="space-y-4 p-5">
        <PanelHead icon="chart" title={t("gr.exp")} hint={t("gr.expHint")} />
        <div className="grid gap-3 sm:grid-cols-3"><Field label={t("gr.expName")}><Input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} /></Field><Field label={t("gr.expA")}><Input value={f.a} onChange={(e) => setF({ ...f, a: e.target.value })} /></Field><Field label={t("gr.expB")}><Input value={f.b} onChange={(e) => setF({ ...f, b: e.target.value })} /></Field></div>
        <ErrorText error={start.error ?? act.error} />
        <div className="flex justify-end"><Button variant="ink" loading={start.loading} disabled={f.a.trim().length < 3 || f.b.trim().length < 3} onClick={() => void start.run()}>{t("gr.expStart")}</Button></div>
      </Panel>
      {!list.data ? <CardSkeletons n={2} className="grid gap-3" h="h-48" /> : list.data.length === 0 ? <Empty icon="chart" title={t("grx.expNone")}>{t("grx.expNoneBody")}</Empty> : list.data.map((e) => (
        <Rise key={e.id}>
          <Panel className="space-y-4 p-5">
            <div className="flex flex-wrap items-center justify-between gap-2"><p className="font-display text-[15px] font-semibold text-ink">{e.name}</p><Badge tone={e.status === "RUNNING" ? "green" : "blue"}>{e.status === "RUNNING" ? t("gr.expRunning") : e.winner ? t("gr.expApplied", { w: e.winner }) : t("gr.expStopped")}</Badge></div>
            <div className="grid gap-3 sm:grid-cols-2">
              {(["A", "B"] as const).map((v) => {
                const views = v === "A" ? e.viewsA : e.viewsB, orders = v === "A" ? e.ordersA : e.ordersB, rate = v === "A" ? e.rateA : e.rateB;
                const best = e.recommendation === v;
                return (
                  <div key={v} className={`rounded-[14px] p-4 ring-1 ${best ? "bg-emerald-50/70 ring-2 ring-emerald-500" : "bg-canvas ring-line"}`}>
                    <p className="flex items-start gap-2 text-[13.5px] font-semibold text-ink"><span className="grid h-6 w-6 shrink-0 place-items-center rounded-full bg-ink text-[11px] text-surface">{v}</span><span className="min-w-0">{v === "A" ? e.variantA : e.variantB}</span></p>
                    <p className="mt-3 font-display text-[1.6rem] font-semibold leading-none tnum text-ink"><span dir="ltr">{rate}%</span></p>
                    <p className="mt-1 text-[12px] text-muted">{t("gr.expNumbers", { views, orders })}</p>
                    {best && <p className="mt-2 flex items-center gap-1 text-[12px] font-bold text-emerald-700"><Icon name="check" className="h-3.5 w-3.5" />{t("gr.expBetter")}</p>}
                  </div>
                );
              })}
            </div>
            {!e.enoughData && e.status === "RUNNING" && <p className="text-[12px] text-muted">{t("gr.expMore")}</p>}
            {e.status === "RUNNING" && (
              <div className="flex flex-wrap gap-2"><Button size="sm" variant="ink" onClick={() => void act.run(e.id, "apply", "A")}>{t("gr.expUse", { v: "A" })}</Button><Button size="sm" variant="ink" onClick={() => void act.run(e.id, "apply", "B")}>{t("gr.expUse", { v: "B" })}</Button><Button size="sm" variant="ghost" onClick={() => setStopId(e.id)}>{t("gr.expStop")}</Button></div>
            )}
          </Panel>
        </Rise>
      ))}
      <ConfirmDialog open={!!stopId} onClose={() => setStopId(null)} danger onConfirm={() => { const id = stopId; setStopId(null); if (id) void act.run(id, "stop"); }}
        title={t("gr.expStop")} body={t("grx.expStopBody")} confirmLabel={t("gr.expStop")} cancelLabel={t("common.cancel")} />
    </div>
  );
}

type Built = { shortDescription: string; about: string; descriptionSaved: boolean; suggestedTemplate: string; products: { id: string; name: string; priceMinor: number }[]; needsPrice?: string[]; withPicture?: number };

export function BuilderTab() {
  const { t, locale, currency } = useI18n();
  const [d, setD] = useState("");
  const [res, setRes] = useState<Built | null>(null);
  const go = useAction(async () => { setRes(await api<Built>("store/builder", { body: { description: d, language: locale } })); });
  // start from the Facebook page / Instagram account the owner already connected
  const sources = useApi<{ platform: "FACEBOOK" | "INSTAGRAM"; name: string }[]>("store/builder/social");
  const [platform, setPlatform] = useState<"FACEBOOK" | "INSTAGRAM">("FACEBOOK");
  const [url, setUrl] = useState("");
  const chosen = sources.data?.find((s) => s.platform === platform) ?? sources.data?.[0];
  const fromSocial = useAction(async () => { setRes(await api<Built>("store/builder/social", { body: { platform: chosen?.platform ?? platform, url: url.trim() || undefined, language: locale } })); });
  return (
    <div className="space-y-5">
      <div className="grid gap-5 lg:grid-cols-2 lg:items-start">
        <Panel className="space-y-4 p-5">
          <PanelHead icon="link" title={t("gr.social")} hint={t("gr.socialHint")} />
          {!sources.data && <div className="skeleton h-24 rounded-[14px]" />}
          {sources.data && sources.data.length === 0 && <Alert tone="blue">{t("gr.socialNone")} <a href="/dashboard/channels?tab=platforms" className="font-semibold underline">{t("gr.socialConnect")}</a></Alert>}
          {sources.data && sources.data.length > 0 && (
            <>
              {sources.data.length > 1 && (
                <Field label={t("gr.socialFrom")}>
                  <Select value={chosen?.platform} onChange={(e) => setPlatform(e.target.value as "FACEBOOK" | "INSTAGRAM")}>
                    {sources.data.map((s) => <option key={s.platform} value={s.platform}>{s.platform === "FACEBOOK" ? "Facebook" : "Instagram"} · {s.name}</option>)}
                  </Select>
                </Field>
              )}
              {sources.data.length === 1 && <p className="rounded-[12px] bg-canvas px-3 py-2 text-[13px] font-semibold ring-1 ring-line">{chosen?.platform === "FACEBOOK" ? "Facebook" : "Instagram"} · {chosen?.name}</p>}
              <Field label={t("gr.socialUrl")} hint={t("gr.socialUrlHint")}><Input dir="ltr" value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://www.facebook.com/your-page" /></Field>
              <ErrorText error={fromSocial.error} />
              <div className="flex justify-end"><Button variant="ink" loading={fromSocial.loading} onClick={() => void fromSocial.run()}><Icon name="sparkles" className="h-4 w-4" />{t("gr.socialGo")}</Button></div>
            </>
          )}
        </Panel>
        <Panel className="space-y-4 p-5">
          <PanelHead icon="sparkles" ai title={t("gr.builder")} hint={t("gr.builderHint")} />
          <Field label={t("gr.builderLabel")}><Textarea rows={6} maxLength={1500} value={d} onChange={(e) => setD(e.target.value)} placeholder={t("gr.builderExample")} /></Field>
          <ErrorText error={go.error} />
          <div className="flex items-center justify-between gap-3"><span className="text-[12px] text-faint tnum">{d.length}/1500</span><Button variant="ink" loading={go.loading} disabled={d.trim().length < 15} onClick={() => void go.run()}><Icon name="sparkles" className="h-4 w-4" />{t("gr.builderGo")}</Button></div>
        </Panel>
      </div>
      {res && (
        <Rise>
          <Panel className="space-y-3 p-5 text-sm">
            <Alert tone="green">{t("gr.builderDone", { n: res.products.length })}</Alert>
            {res.shortDescription && <p className="text-[13.5px]"><b>{res.descriptionSaved ? t("gr.builderSaved") : t("gr.builderKept")}:</b> {res.shortDescription}</p>}
            <p className="text-[13px] text-muted">{t("gr.builderTemplate", { tpl: res.suggestedTemplate })}</p>
            <ul className="divide-y divide-line overflow-hidden rounded-[14px] ring-1 ring-line">{res.products.map((p) => <li key={p.id} className="flex items-center justify-between gap-3 px-4 py-2.5 text-[13px]"><span className="min-w-0 truncate font-medium">{p.name}</span><span className="shrink-0 tnum text-muted">{money(p.priceMinor, currency, locale)}</span></li>)}</ul>
            {!!res.withPicture && <p className="text-[13px] text-muted">{t("gr.socialPictures", { n: res.withPicture })}</p>}
            {!!res.needsPrice?.length && <Alert tone="amber">{t("gr.socialNeedsPrice", { n: res.needsPrice.length })}</Alert>}
            <a href="/dashboard/products" className="inline-flex items-center gap-1 text-[13px] font-semibold text-brand hover:underline">{t("gr.builderReview")}<Icon name="arrow" className="h-3.5 w-3.5 flip-rtl" /></a>
          </Panel>
        </Rise>
      )}
    </div>
  );
}

export default function GrowthView() {
  const [tab] = useSection(["segments", "loyalty", "feeds", "exp", "builder"] as const, "segments");
  return (
    <div className="space-y-5">
      {tab === "segments" ? <SegmentsTab /> : tab === "loyalty" ? <LoyaltyTab /> : tab === "feeds" ? <FeedsTab /> : tab === "exp" ? <ExperimentsTab /> : <BuilderTab />}
    </div>
  );
}
