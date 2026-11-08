"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useSection } from "./section";
import { api } from "@/lib/client";
import { dateOnly, money } from "@/lib/format";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Icon, type IconName } from "../icons";
import { ConfirmDialog } from "../overlay";
import { useToast } from "../toast";
import MessageComposer from "./message-composer";
import { useOwnNumber } from "./send-mode";
import { Alert, Avatar, Badge, Button, Empty, ErrorText, Field, Input, KpiStrip, Select, Textarea } from "../ui";
import { AI_GRADIENT, AI_SOFT, Bar, CardSkeletons, ChoiceCard, CopyField, Panel, PanelHead, Rise, SwitchRow } from "./marketing/kit";

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

// ---- segments and broadcasts -------------------------------------------------------------------------------------------------

type Seg = { segments: Record<string, number>; withWhatsappConsent: number; messagePriceMinor: number; balanceMinor: number };
type Preview = { audience: number; withConsent: number; reachableNow: number; freeReachable: number; appUsers: number };
type SegDetail = { customers: { phone: string; name?: string; orders: number; spent: number; lastOrder?: string; whatsappConsent: boolean; hasAccount: boolean }[]; preview: Preview };
type Hist = { id: string; segment: string; message: string; mode: string; push: boolean; pushSent: number; audience: number; sentCount: number; failedCount: number; pendingCount: number; chargedMinor: number; status: string; pauseReason?: string | null; createdAt: string }[];
const KEYS = ["ALL", "NEW", "REPEAT", "VIP", "AT_RISK", "LAPSED"] as const;
const SEG_ICON: Record<(typeof KEYS)[number], IconName> = { ALL: "users", NEW: "sparkles", REPEAT: "return", VIP: "award", AT_RISK: "alert", LAPSED: "clock" };
type Way = "NONE" | "FREE" | "PAID";

function Step({ n, title, hint, children }: { n: number; title: string; hint?: string; children: React.ReactNode }) {
  return (
    <section className="space-y-3">
      <div className="flex items-center gap-2.5">
        <span className="grid h-7 w-7 shrink-0 place-items-center rounded-full bg-ink text-[12px] font-bold text-surface tnum">{n}</span>
        <div className="min-w-0"><h3 className="font-display text-[15px] font-semibold text-ink">{title}</h3>{hint && <p className="text-[12.5px] text-muted">{hint}</p>}</div>
      </div>
      {children}
    </section>
  );
}

/** A phone-shaped preview of the message as the customer would read it. */
function Bubble({ text, image, footer }: { text: string; image: string | null; footer?: string }) {
  const { t } = useI18n();
  return (
    <div className="rounded-[18px] bg-[#efeae2] p-3 ring-1 ring-line dark:bg-[#0b141a]">
      <div className="ms-auto w-fit max-w-[92%] rounded-[12px] rounded-te-[4px] bg-[#d9fdd3] px-3 py-2 text-[13px] leading-relaxed text-[#111b21] shadow-sm">
        {image && <span className="mb-1.5 grid h-24 w-44 place-items-center rounded-[8px] bg-black/10 text-[#111b21]/50"><Icon name="camera" className="h-6 w-6" /></span>}
        <p className="whitespace-pre-wrap break-words">{text.trim() || <span className="opacity-50">{t("grx.previewEmpty")}</span>}</p>
        {footer && <p className="mt-1.5 border-t border-black/10 pt-1 text-[11px] opacity-70">{footer}</p>}
      </div>
    </div>
  );
}

export function SegmentsTab() {
  const { t, locale, currency } = useI18n();
  const toast = useToast();
  const seg = useApi<Seg>("store/segments");
  const hist = useApi<Hist>("store/broadcasts");
  const own = useOwnNumber();
  const router = useRouter();
  const [key, setKey] = useState<(typeof KEYS)[number]>("ALL");
  const det = useApi<SegDetail>(`store/segments/${key}`);
  const [msg, setMsg] = useState("");
  const [image, setImage] = useState<string | null>(null);
  const [way, setWay] = useState<Way>("PAID");
  const [push, setPush] = useState(false);
  const [pushTitle, setPushTitle] = useState("");
  const [onlyConsented, setOnlyConsented] = useState(false);
  const [versions, setVersions] = useState<string[]>([]);
  const [done, setDone] = useState<string | null>(null);
  const [confirmSend, setConfirmSend] = useState(false);
  const [cancelId, setCancelId] = useState<string | null>(null);
  const [showAll, setShowAll] = useState(false);
  const pv = det.data?.preview;
  const price = seg.data?.messagePriceMinor ?? 50;
  const paidCost = (pv?.reachableNow ?? 0) * price;
  const lowCredit = way === "PAID" && !!seg.data && paidCost > seg.data.balanceMinor;
  const freeN = pv ? (onlyConsented ? pv.withConsent : pv.freeReachable) : 0;
  const send = useAction(async () => {
    const r = await api<{ queued: number; pushSent: number; costMinor: number; skippedNoWindow: number; skippedNoConsent: number }>("store/broadcasts", { body: { segment: key, message: msg, imageFileId: image ?? undefined, variants: versions.map((v) => v.trim()).filter((v) => v.length >= 3), mode: way, push, pushTitle: pushTitle || undefined, onlyConsented } });
    const text = t("gr.sentResult2", { queued: r.queued, push: r.pushSent, cost: money(r.costMinor, currency, locale) });
    setDone(text); toast(text);
    setMsg(""); setImage(null); setVersions([]); await Promise.all([hist.reload(), seg.reload()]);
  });
  const chooseWay = (w: Way) => { if (w === "FREE" && !own.linked) { router.push("/dashboard/settings?tab=whatsapp#whatsapp-own"); return; } setWay(w); };
  const suggest = useAction(async () => { const r = await api<{ variants: string[] }>("store/broadcasts/variants", { body: { message: msg, count: 3 } }); setVersions((cur) => [...cur, ...r.variants].slice(0, 5)); });
  const resume = useAction(async (id: string) => { await api(`store/broadcasts/${id}/resume`, { body: {} }); toast(t("grx.resumed")); await hist.reload(); });
  const cancel = useAction(async (id: string) => { await api(`store/broadcasts/${id}/cancel`, { body: {} }); toast(t("grx.canceled")); await hist.reload(); });
  const modeLabel = (m: string) => (m === "FREE" ? t("sm.freeShort") : m === "PAID" ? t("sm.paidShort") : t("gr.appOnly"));
  const n = (x: number) => x.toLocaleString(locale === "ar" ? "ar-EG" : "en-US");
  const canSend = msg.trim().length >= 3 && !(way === "NONE" && !push) && !lowCredit;
  const customers = det.data?.customers ?? [];
  const shown = showAll ? customers : customers.slice(0, 8);
  return (
    <div className="space-y-5">
      <KpiStrip items={[
        { label: t("gr.seg.ALL"), value: seg.data?.segments.ALL ?? 0, icon: "users" },
        { label: t("gr.seg.VIP"), value: seg.data?.segments.VIP ?? 0, icon: "award", tone: "violet" },
        { label: t("gr.seg.AT_RISK"), value: seg.data?.segments.AT_RISK ?? 0, icon: "alert", tone: "amber" },
        { label: t("grx.consent"), value: seg.data?.withWhatsappConsent ?? 0, icon: "phone", tone: "green" },
      ]} />

      <div className="grid gap-5 lg:grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)] lg:items-start">
        {/* the builder */}
        <Panel className="space-y-7 p-4 sm:p-6">
          <div className="flex items-center gap-3">
            <span className={`grid h-10 w-10 shrink-0 place-items-center rounded-[12px] text-white ${AI_GRADIENT}`}><Icon name="send" className="h-5 w-5 flip-rtl" /></span>
            <div className="min-w-0"><h2 className="font-display text-[17px] font-semibold text-ink">{t("gr.compose", { segment: t(`gr.seg.${key}`) })}</h2><p className="text-[12.5px] text-muted">{t(`gr.segHint.${key}`)}</p></div>
          </div>

          <Step n={1} title={t("grx.step1")} hint={t("grx.step1Hint")}>
            <div role="radiogroup" className="grid grid-cols-2 gap-2 sm:grid-cols-3">
              {KEYS.map((k) => {
                const on = k === key;
                return (
                  <button key={k} type="button" role="radio" aria-checked={on} onClick={() => { setKey(k); setDone(null); }}
                    className={`flex items-center gap-2.5 rounded-[14px] p-3 text-start ring-1 transition ${on ? "bg-ink text-surface ring-ink" : "bg-surface ring-line hover:ring-line-strong"}`}>
                    <Icon name={SEG_ICON[k]} className={`h-4 w-4 shrink-0 ${on ? "" : "text-muted"}`} />
                    <span className="min-w-0"><span className={`block truncate text-[12px] ${on ? "opacity-80" : "text-muted"}`}>{t(`gr.seg.${k}`)}</span><span className="block font-display text-[18px] font-semibold leading-none tnum">{n(seg.data?.segments[k] ?? 0)}</span></span>
                  </button>
                );
              })}
            </div>
          </Step>

          <Step n={2} title={t("grx.step2")} hint={t("grx.step2Hint")}>
            <MessageComposer text={msg} onText={setMsg} imageId={image} onImage={setImage} footer={way === "FREE" ? t("gr.optOutLine") : undefined} />
            <div className={`space-y-3 rounded-[14px] p-4 ring-1 ring-violet-200/60 ${AI_SOFT}`}>
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div><p className="flex items-center gap-1.5 text-[13.5px] font-semibold text-ink"><Icon name="sparkles" className="h-4 w-4 text-violet-500" />{t("gr.versions")}</p><p className="mt-0.5 text-[12px] text-muted">{t("gr.versionsHint")}</p></div>
                <div className="flex flex-wrap gap-2">
                  <Button size="sm" variant="secondary" disabled={versions.length >= 5} onClick={() => setVersions([...versions, ""])}><Icon name="plus" className="h-4 w-4" />{t("gr.addVersion")}</Button>
                  <Button size="sm" variant="ink" loading={suggest.loading} disabled={msg.trim().length < 3 || versions.length >= 5} onClick={() => void suggest.run()}><Icon name="sparkles" className="h-4 w-4" />{t("gr.suggestVersions")}</Button>
                </div>
              </div>
              {versions.map((v, i) => (
                <div key={i} className="flex items-start gap-2">
                  <span className="mt-2 grid h-6 w-6 shrink-0 place-items-center rounded-full bg-surface text-[11px] font-bold text-muted ring-1 ring-line">{i + 2}</span>
                  <Textarea rows={2} maxLength={1000} value={v} onChange={(e) => setVersions(versions.map((x, k) => (k === i ? e.target.value : x)))} />
                  <button type="button" onClick={() => setVersions(versions.filter((_, k) => k !== i))} className="mt-1 grid h-9 w-9 shrink-0 place-items-center rounded-[10px] text-faint hover:bg-red-50 hover:text-red-600" aria-label={t("common.delete")}><Icon name="close" className="h-4 w-4" /></button>
                </div>
              ))}
              <ErrorText error={suggest.error} />
            </div>
          </Step>

          <Step n={3} title={t("gr.sendVia")} hint={t("grx.step3Hint")}>
            <div role="radiogroup" className="grid gap-2 sm:grid-cols-3">
              <ChoiceCard active={way === "FREE"} onClick={() => chooseWay("FREE")} icon="phone" tone="green" title={t("sm.free")} hint={t(own.linked ? "gr.wayFreeHint" : "sm.freeOff", { price: money(price, currency, locale) })}
                badge={!own.linked ? <span className="rounded-full bg-amber-100 px-2 py-0.5 text-[11px] font-semibold text-amber-800">{t("sm.linkFirst")}</span> : undefined} />
              <ChoiceCard active={way === "PAID"} onClick={() => chooseWay("PAID")} icon="billing" title={t("sm.paid")} hint={t("gr.wayPaidHint", { price: money(price, currency, locale) })} />
              <ChoiceCard active={way === "NONE"} onClick={() => chooseWay("NONE")} icon="xcircle" tone="slate" title={t("gr.noWhatsapp")} hint={t("gr.wayNoneHint", { price: money(price, currency, locale) })} />
            </div>
            <SwitchRow title={<span className="inline-flex items-center gap-1.5"><Icon name="mobile" className="h-4 w-4" />{t("gr.alsoPush")}{pv && <span className="text-[12px] font-normal text-muted">({t("gr.appUsers", { n: pv.appUsers })})</span>}</span>} checked={push} onChange={setPush} />
            {push && <Field label={t("gr.pushTitle")} hint={t("gr.pushTitleHint")}><Input maxLength={80} value={pushTitle} onChange={(e) => setPushTitle(e.target.value)} /></Field>}
            {way === "FREE" && <SwitchRow title={t("gr.onlyConsented")} checked={onlyConsented} onChange={setOnlyConsented} />}
            {way === "FREE" && <Alert tone="amber">{t("gr.freeWarn")}</Alert>}
            {way === "PAID" && <Alert tone="blue">{t("gr.paidInfo", { price: money(price, currency, locale), balance: money(seg.data?.balanceMinor ?? 0, currency, locale) })}</Alert>}
            {lowCredit && <Alert tone="red">{t("gr.lowCredit", { need: money(paidCost, currency, locale) })} <Link href="/dashboard/ai" className="font-semibold underline">{t("gr.topUp")}</Link></Alert>}
          </Step>
        </Panel>

        {/* the live summary */}
        <div className="space-y-4 lg:sticky lg:top-4">
          <Panel className="space-y-4 p-5">
            <PanelHead icon="eye" title={t("grx.summary")} />
            <Bubble text={msg} image={image} footer={way === "FREE" ? t("gr.optOutLine") : undefined} />
            {pv ? (
              <div className="space-y-2 rounded-[14px] bg-canvas p-3.5 text-[13px] leading-relaxed text-ink-2 ring-1 ring-line">
                {way === "FREE" && <p>{t("gr.reachFree", { n: freeN, min: Math.max(1, Math.ceil((freeN * 16.5) / 60)), days: Math.max(1, Math.ceil(freeN / 500)) })}</p>}
                {way === "PAID" && <p>{t("gr.reachPaid", { n: pv.reachableNow, consent: pv.withConsent, cost: money(paidCost, currency, locale), price: money(price, currency, locale) })}</p>}
                {way === "NONE" && <p>{t("gr.reachNone")}</p>}
                {push && <p>{t("gr.reachPush", { n: pv.appUsers })}</p>}
              </div>
            ) : <div className="skeleton h-20 rounded-[14px]" />}
            <ErrorText error={send.error} />
            {done && <Alert tone="green">{done}</Alert>}
            <Button variant="ink" size="lg" className="w-full" loading={send.loading} disabled={!canSend} onClick={() => { setDone(null); setConfirmSend(true); }}><Icon name="send" className="h-4 w-4 flip-rtl" />{t("gr.send")}</Button>
          </Panel>
        </div>
      </div>

      <Panel className="overflow-hidden">
        <div className="px-5 pt-5"><PanelHead icon="users" title={t("gr.customers")} hint={t(`gr.seg.${key}`)} /></div>
        {det.loading && !det.data ? <div className="p-5"><CardSkeletons n={3} className="space-y-2" h="h-12" /></div> : customers.length === 0 ? <div className="p-5"><Empty compact icon="users">{t("gr.noCustomers")}</Empty></div> : (
          <>
            <ul className="mt-3 divide-y divide-line">
              {shown.map((c) => (
                <li key={c.phone} className="flex flex-wrap items-center gap-3 px-5 py-3">
                  <Avatar name={c.name || c.phone} className="h-9 w-9 text-[13px]" />
                  <div className="min-w-0 flex-1">
                    <p className="flex flex-wrap items-center gap-1.5 text-[13.5px] font-semibold text-ink">{c.name || <span dir="ltr">{c.phone}</span>}{c.whatsappConsent && <Badge tone="green">WhatsApp</Badge>}{c.hasAccount && <Badge tone="blue">App</Badge>}</p>
                    {c.name && <p dir="ltr" className="text-start text-[12px] tnum text-muted">{c.phone}</p>}
                  </div>
                  <p className="text-[12px] text-muted tnum">{c.orders} · {money(c.spent, currency, locale)}{c.lastOrder ? ` · ${dateOnly(c.lastOrder, locale)}` : ""}</p>
                </li>
              ))}
            </ul>
            {customers.length > 8 && <button type="button" onClick={() => setShowAll((v) => !v)} className="w-full border-t border-line py-3 text-[13px] font-semibold text-muted transition hover:bg-surface-2 hover:text-ink">{showAll ? t("grx.less") : t("grx.more", { n: customers.length - 8 })}</button>}
          </>
        )}
      </Panel>

      {(hist.data ?? []).length > 0 && (
        <Panel className="space-y-3 p-5">
          <PanelHead icon="clock" title={t("gr.history")} />
          <ErrorText error={resume.error ?? cancel.error} />
          <ul className="divide-y divide-line">
            {hist.data?.map((h) => {
              const total = Math.max(1, h.audience);
              return (
                <li key={h.id} className="space-y-2 py-3 first:pt-1">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span className="flex flex-wrap gap-1.5">
                      <Badge tone="blue">{t(`gr.seg.${h.segment}`)}</Badge><Badge tone={h.mode === "FREE" ? "green" : h.mode === "PAID" ? "amber" : "slate"}>{modeLabel(h.mode)}</Badge>
                      {h.push && <Badge tone="slate">App · {h.pushSent}</Badge>}
                      {h.status === "SENDING" && <Badge tone="amber">{t("gr.sending", { n: h.pendingCount })}</Badge>}
                      {h.pauseReason && <Badge tone="slate">{t(`gr.pause.${h.pauseReason}`)}</Badge>}
                      {h.status === "PAUSED" && <Badge tone="red">{t("gr.state.PAUSED")}</Badge>}
                      {h.status === "CANCELED" && <Badge tone="slate">{t("gr.state.CANCELED")}</Badge>}
                    </span>
                    <span className="text-[12px] text-muted tnum">{dateOnly(h.createdAt, locale)}</span>
                  </div>
                  <p className="line-clamp-2 text-[13px] text-ink-2">{h.message}</p>
                  <div className="flex items-center gap-3">
                    <div className="min-w-0 flex-1"><Bar value={h.sentCount} max={total} tone="green" /></div>
                    <span className="shrink-0 text-[12px] text-muted tnum">{h.sentCount}/{h.audience}{h.failedCount > 0 ? ` · ${h.failedCount} ✗` : ""}{h.chargedMinor > 0 ? ` · ${money(h.chargedMinor, currency, locale)}` : ""}</span>
                  </div>
                  {(h.status === "PAUSED" || h.status === "SENDING") && h.mode === "FREE" && (
                    <div className="flex flex-wrap gap-2">
                      {h.status === "PAUSED" && <Button size="sm" variant="ink" loading={resume.loading} onClick={() => void resume.run(h.id)}>{t("gr.resume")}</Button>}
                      <Button size="sm" variant="ghost" onClick={() => setCancelId(h.id)}>{t("gr.cancel")}</Button>
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
        </Panel>
      )}

      <ConfirmDialog open={confirmSend} onClose={() => setConfirmSend(false)} loading={send.loading}
        onConfirm={() => { setConfirmSend(false); void send.run(); }}
        title={t("grx.confirmTitle")} body={<>{t("grx.confirmBody", { segment: t(`gr.seg.${key}`) })}{way === "PAID" && pv ? <span className="mt-1 block font-semibold text-ink">{money(paidCost, currency, locale)}</span> : null}</>}
        confirmLabel={t("gr.send")} cancelLabel={t("common.cancel")} />
      <ConfirmDialog open={!!cancelId} onClose={() => setCancelId(null)} danger onConfirm={() => { const id = cancelId; setCancelId(null); if (id) void cancel.run(id); }}
        title={t("gr.cancel")} body={t("gr.confirmCancel")} confirmLabel={t("gr.cancel")} cancelLabel={t("common.cancel")} />
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
