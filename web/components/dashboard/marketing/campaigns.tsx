"use client";

import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { useState } from "react";
import { api } from "@/lib/client";
import { fromMinor, money, toMinor } from "@/lib/format";
import { Page, useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { Sheet } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { Alert, Badge, Button, Empty, ErrorText, Field, Input, KpiStrip, Select, Textarea } from "@/components/ui";
import { SearchBox } from "@/components/dashboard/filters";
import { dateTime } from "@/lib/format";
import { fileUrl } from "@/lib/media";
import { COUNTRIES } from "../channels/common";
import { AI_GRADIENT, AI_SOFT, CardSkeletons, Figure, PillTabs, SwitchRow, errText } from "./kit";
import type { Overview, Post } from "./posts";

type Suggestion = { objective: string; dailyBudgetMinor: number; currency: string; durationDays: number; countries: string[]; ageMin: number; ageMax: number; genders: string[]; interests: string[]; cta: string; headline: string; primaryText: string; reasoning: string; fromAi: boolean };
type Campaign = {
  id: string; accountId: string; productId?: string; productName?: string; externalId?: string; name: string; objective: string; status: string; dailyBudgetMinor: number; currency: string; startsAt?: string; endsAt?: string;
  params: Record<string, unknown>; insights: Record<string, string>; lastError?: string; lastSyncedAt?: string; createdAt: string;
};
type ProductRow = { id: string; name: string };

const OBJECTIVES = ["OUTCOME_TRAFFIC", "OUTCOME_ENGAGEMENT", "OUTCOME_AWARENESS"];
const CTAS = ["SHOP_NOW", "ORDER_NOW", "LEARN_MORE", "CONTACT_US", "SIGN_UP"];
const TONE: Record<string, "green" | "red" | "amber" | "blue" | "slate"> = { ACTIVE: "green", PAUSED: "amber", DRAFT: "slate", FAILED: "red", ENDED: "slate" };

function Wizard({ ov, onClose, onCreated }: { ov: Overview; onClose: () => void; onCreated: () => void }) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const [step, setStep] = useState<1 | 2>(1);
  const [accountId, setAccountId] = useState(ov.adAccounts[0]?.id ?? "");
  const pages = ov.channels.filter((c) => c.platform === "FACEBOOK");
  const [pageId, setPageId] = useState(pages[0]?.externalId ?? "");
  const [target, setTarget] = useState<"PRODUCT" | "POST">("PRODUCT");
  const [q, setQ] = useState("");
  const [product, setProduct] = useState<ProductRow | null>(null);
  const [post, setPost] = useState<Post | null>(null);
  const found = useApi<Page<ProductRow>>(target === "PRODUCT" && !product ? `store/products?q=${encodeURIComponent(q)}&page=1` : null);
  const posts = useApi<Page<Post>>(target === "POST" ? "store/marketing/posts?page=1" : null);
  const [f, setF] = useState<(Omit<Suggestion, "dailyBudgetMinor"> & { budget: string; name: string; launch: boolean; interestText: string }) | null>(null);
  const suggest = useAction(async () => {
    const s = await api<Suggestion>("store/marketing/campaigns/suggest", { body: { accountId, productId: target === "PRODUCT" ? product?.id : undefined, language: locale } });
    setF({ ...s, budget: fromMinor(s.dailyBudgetMinor), name: "", launch: false, interestText: "" });
    setStep(2);
  });
  const create = useAction(async () => {
    if (!f) return;
    await api("store/marketing/campaigns", { body: {
      accountId, pageId: pageId || undefined, productId: target === "PRODUCT" ? product?.id : undefined, postId: target === "POST" ? post?.id : undefined, name: f.name || undefined, objective: f.objective, dailyBudgetMinor: toMinor(f.budget),
      durationDays: f.durationDays, countries: f.countries, ageMin: f.ageMin, ageMax: f.ageMax, genders: f.genders, interests: f.interests, headline: f.headline, primaryText: f.primaryText, cta: f.cta, launch: f.launch,
    } });
    toast(f.launch ? t("mkx.campaignLaunched") : t("mkx.campaignCreated"));
    onCreated();
  });
  const set = (patch: Partial<NonNullable<typeof f>>) => setF((x) => (x ? { ...x, ...patch } : x));
  const ready = !!accountId && (target === "PRODUCT" ? !!product : !!post);
  return (
    <Sheet open onClose={onClose} width="40rem" title={t("mk.newCampaign")}
      footer={step === 1 ? (
        <div className="flex justify-end gap-2"><Button variant="secondary" onClick={onClose}>{t("common.cancel")}</Button><Button variant="ink" loading={suggest.loading} disabled={!ready} onClick={() => void suggest.run()}><Icon name="sparkles" className="h-4 w-4" />{t("mk.suggest")}</Button></div>
      ) : f ? (
        <div className="flex justify-between gap-2"><Button type="button" variant="ghost" onClick={() => setStep(1)}>{t("common.back")}</Button><Button variant="ink" loading={create.loading} disabled={f.countries.length === 0} onClick={() => void create.run()}>{f.launch ? t("mk.createLaunch") : t("mk.create")}</Button></div>
      ) : undefined}>
      <div className="mb-5 flex items-center gap-2 text-[12px] font-semibold text-muted">
        {[1, 2].map((n) => <span key={n} className={`flex items-center gap-1.5 ${step === n ? "text-ink" : ""}`}><span className={`grid h-6 w-6 place-items-center rounded-full text-[11px] ${step >= n ? "bg-ink text-surface" : "bg-[color-mix(in_srgb,var(--ink)_10%,transparent)]"}`}>{n}</span>{t(`mk.step${n}`)}{n === 1 && <span className="mx-1 h-px w-8 bg-line-strong" />}</span>)}
      </div>
      {step === 1 && (
        <div className="space-y-4">
          {ov.adAccounts.length > 1 && <Field label={t("mk.adAccount")}><Select value={accountId} onChange={(e) => setAccountId(e.target.value)}>{ov.adAccounts.map((a) => <option key={a.id} value={a.id}>{a.label} ({a.externalId})</option>)}</Select></Field>}
          {pages.length > 1 && <Field label={t("mk.page")}><Select value={pageId} onChange={(e) => setPageId(e.target.value)}>{pages.map((p) => <option key={p.id} value={p.externalId}>{p.name ?? p.externalId}</option>)}</Select></Field>}
          <PillTabs id="wz-target" value={target} onChange={setTarget} tabs={(["PRODUCT", "POST"] as const).map((k) => ({ key: k, label: t(`mk.target.${k}`) }))} />
          {target === "PRODUCT" ? (product ? (
            <div className="flex items-center justify-between gap-3 rounded-[12px] bg-surface px-3.5 py-2.5 text-[13.5px] ring-2 ring-brand"><span className="min-w-0 truncate font-semibold">{product.name}</span><button type="button" className="shrink-0 text-[12.5px] font-semibold text-brand hover:underline" onClick={() => setProduct(null)}>{t("mk.change")}</button></div>
          ) : (
            <div className="space-y-2"><SearchBox value={q} onChange={setQ} placeholder={t("mk.pickProduct")} />
              <ul className="max-h-60 divide-y divide-line overflow-y-auto rounded-[12px] ring-1 ring-line">{found.data?.data.map((p) => <li key={p.id}><button type="button" className="w-full px-3.5 py-2.5 text-start text-[13.5px] hover:bg-surface-2" onClick={() => setProduct(p)}>{p.name}</button></li>)}</ul></div>
          )) : (
            <div>
              <p className="mb-2 text-[12.5px] text-muted">{t("mk.target.postHint")}</p>
              <ul className="grid max-h-80 grid-cols-2 gap-2 overflow-y-auto sm:grid-cols-3">{posts.data?.data.filter((p) => p.imageFileId || p.imageUrl).map((p) => (
                <li key={p.id}><button type="button" onClick={() => setPost(p)} className={`w-full overflow-hidden rounded-[12px] text-start ring-1 transition ${post?.id === p.id ? "ring-2 ring-brand" : "ring-line hover:ring-line-strong"}`}>
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={p.imageFileId ? fileUrl(p.imageFileId, "thumb") : p.imageUrl!} alt="" className="aspect-square w-full object-cover" /><span className="line-clamp-2 block p-2 text-[12px]">{p.caption}</span></button></li>
              ))}</ul>
              {posts.data && posts.data.data.every((p) => !p.imageFileId && !p.imageUrl) && <p className="text-sm text-muted">{t("mk.target.noPosts")}</p>}
            </div>
          )}
          <ErrorText error={suggest.error} />
        </div>
      )}
      {step === 2 && f && (
        <form onSubmit={(e) => { e.preventDefault(); void create.run(); }} className="space-y-5">
          <div className={`flex items-start gap-2.5 rounded-[14px] p-3.5 text-[13px] leading-relaxed ring-1 ring-violet-200/60 ${AI_SOFT}`}>
            <span className={`grid h-7 w-7 shrink-0 place-items-center rounded-[9px] text-white ${AI_GRADIENT}`}><Icon name="sparkles" className="h-4 w-4" /></span>
            <p className="min-w-0 flex-1 text-ink-2">{f.reasoning} {!f.fromAi && <span className="opacity-70">({t("mk.ruleBased")})</span>}</p>
          </div>
          <Field label={`${t("mk.campaignName")} (${t("ch.optional")})`}><Input value={f.name} onChange={(e) => set({ name: e.target.value })} /></Field>
          <div className="grid gap-4 sm:grid-cols-3">
            <Field label={t("mk.objective")}><Select value={f.objective} onChange={(e) => set({ objective: e.target.value })}>{OBJECTIVES.map((o) => <option key={o} value={o}>{t(`mk.obj.${o}`)}</option>)}</Select></Field>
            <Field label={`${t("mk.dailyBudget")} (${f.currency})`}><Input inputMode="decimal" value={f.budget} onChange={(e) => set({ budget: e.target.value })} required /></Field>
            <Field label={t("mk.duration")}><Input inputMode="numeric" value={String(f.durationDays)} onChange={(e) => set({ durationDays: Number(e.target.value) || 1 })} /></Field>
          </div>
          <p className="-mt-3 rounded-[10px] bg-canvas px-3 py-2 text-[12.5px] font-semibold text-ink-2 ring-1 ring-line">{t("mk.totalSpend", { total: money(toMinor(f.budget) * f.durationDays, f.currency, locale) })}</p>
          <div>
            <p className="mb-1.5 text-[13px] font-semibold text-ink">{t("mk.countries")}</p>
            <div className="flex flex-wrap gap-2">{COUNTRIES.map((c) => { const on = f.countries.includes(c.code); return <button key={c.code} type="button" aria-pressed={on} onClick={() => set({ countries: on ? f.countries.filter((x) => x !== c.code) : [...f.countries, c.code] })} className={`chip ${on ? "chip-on" : ""}`}>{c.flag} {t(`country.${c.code}`)}</button>; })}</div>
          </div>
          <div className="grid gap-4 sm:grid-cols-3">
            <Field label={t("mk.ageMin")}><Input inputMode="numeric" value={String(f.ageMin)} onChange={(e) => set({ ageMin: Number(e.target.value) || 18 })} /></Field>
            <Field label={t("mk.ageMax")}><Input inputMode="numeric" value={String(f.ageMax)} onChange={(e) => set({ ageMax: Number(e.target.value) || 65 })} /></Field>
            <Field label={t("mk.gender")}><Select value={f.genders.length === 1 ? f.genders[0] : "all"} onChange={(e) => set({ genders: e.target.value === "all" ? [] : [e.target.value] })}><option value="all">{t("mk.gender.all")}</option><option value="male">{t("mk.gender.male")}</option><option value="female">{t("mk.gender.female")}</option></Select></Field>
          </div>
          <Field label={t("mk.interests")} hint={t("mk.interestsHint")}>
            <div className="flex flex-wrap items-center gap-1.5 rounded-[11px] bg-surface p-2 ring-1 ring-line-strong focus-within:ring-2 focus-within:ring-brand/40">
              {f.interests.map((i) => <span key={i} className="inline-flex items-center gap-1 rounded-full bg-brand-soft py-1 pe-1 ps-2.5 text-[12px] font-semibold text-brand">{i}<button type="button" aria-label={t("common.remove")} className="grid h-5 w-5 place-items-center rounded-full hover:bg-brand/15" onClick={() => set({ interests: f.interests.filter((x) => x !== i) })}><Icon name="close" className="h-3 w-3" /></button></span>)}
              <input className="min-w-[8rem] flex-1 bg-transparent px-1 py-1 text-sm outline-none" value={f.interestText} onChange={(e) => set({ interestText: e.target.value })} onKeyDown={(e) => { if ((e.key === "Enter" || e.key === ",") && f.interestText.trim()) { e.preventDefault(); set({ interests: [...f.interests, f.interestText.trim()].slice(0, 8), interestText: "" }); } }} />
            </div>
          </Field>
          <Field label={t("mk.headline")}><Input maxLength={40} value={f.headline} onChange={(e) => set({ headline: e.target.value })} required /></Field>
          <Field label={t("mk.primaryText")}><Textarea rows={3} maxLength={220} value={f.primaryText} onChange={(e) => set({ primaryText: e.target.value })} required /></Field>
          <Field label={t("mk.cta")}><Select value={f.cta} onChange={(e) => set({ cta: e.target.value })}>{CTAS.map((c) => <option key={c} value={c}>{t(`mk.cta.${c}`)}</option>)}</Select></Field>
          <SwitchRow title={t("mk.launchNow")} hint={t("mk.launchHint")} checked={f.launch} onChange={(v) => set({ launch: v })} />
          <ErrorText error={create.error} />
        </form>
      )}
    </Sheet>
  );
}

export function CampaignsView({ ov, canWrite }: { ov: Overview; canWrite: boolean }) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const reduce = useReducedMotion();
  const { data, loading, reload } = useApi<Campaign[]>("store/marketing/campaigns");
  const [wizard, setWizard] = useState(false);
  const setStatus = useAction(async (id: string, to: "launch" | "pause") => {
    try { await api(`store/marketing/campaigns/${id}/${to}`, { body: {} }); toast(to === "launch" ? t("mkx.campaignLaunched") : t("mkx.campaignPaused")); await reload(); }
    catch (e) { toast(errText(e, t("inbox.saveFailed")), { tone: "error" }); }
  });
  const refresh = useAction(async (id: string) => { await api(`store/marketing/campaigns/${id}/refresh`, { body: {} }); await reload(); });
  const n = (v?: string) => (v ? Number(v).toLocaleString(locale === "ar" ? "ar-EG" : "en-GB") : "—");
  if (ov.adAccounts.length === 0) {
    return <Empty icon="trending" title={t("mk.noAdAccount.title")}>{t("mk.noAdAccount.body")}</Empty>;
  }
  const active = (data ?? []).filter((c) => c.status === "ACTIVE");
  const spend = (data ?? []).reduce((a, c) => a + (c.insights.spend ? Math.round(Number(c.insights.spend) * 100) : 0), 0);
  const clicks = (data ?? []).reduce((a, c) => a + (Number(c.insights.clicks) || 0), 0);
  const cur = data?.[0]?.currency ?? "EGP";
  return (
    <div className="space-y-5">
      {data && data.length > 0 && (
        <KpiStrip items={[
          { label: t("mkx.activeCampaigns"), value: active.length, icon: "trending", tone: "green" },
          { label: t("mk.m.spend"), value: money(spend, cur, locale), icon: "wallet" },
          { label: t("mk.m.clicks"), value: clicks, icon: "chart", tone: "violet" },
        ]} />
      )}
      <div className="flex items-center justify-between gap-3"><h2 className="font-display text-[16px] font-semibold text-ink">{t("mk.campaigns")}</h2>{canWrite && <Button variant="ink" onClick={() => setWizard(true)}><Icon name="plus" className="h-4 w-4" />{t("mk.newCampaign")}</Button>}</div>
      <ErrorText error={refresh.error} />
      {loading && !data ? <CardSkeletons n={4} className="grid gap-4 lg:grid-cols-2" h="h-56" /> : data && data.length === 0 ? (
        <Empty icon="trending" title={t("mk.noCampaigns")} action={canWrite ? <Button variant="ink" onClick={() => setWizard(true)}>{t("mk.newCampaign")}</Button> : undefined} />
      ) : (
        <ul className="grid gap-4 lg:grid-cols-2">
          <AnimatePresence initial={false}>
            {data?.map((c) => (
              <motion.li key={c.id} layout={!reduce} initial={reduce ? false : { opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} className="space-y-4 rounded-[18px] bg-surface p-4 ring-1 ring-line sm:p-5">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0"><p className="truncate font-display text-[15px] font-semibold text-ink">{c.name}</p><p className="mt-0.5 truncate text-[12px] text-muted">{c.productName ?? t("mk.target.POST")} · {t(`mk.obj.${c.objective}`)}</p></div>
                  <Badge tone={TONE[c.status] ?? "slate"}>{t(`mk.cstatus.${c.status}`)}</Badge>
                </div>
                <div className="grid grid-cols-4 gap-2 rounded-[14px] bg-canvas p-3 ring-1 ring-line">
                  <Figure label={t("mk.m.spend")} value={c.insights.spend ? money(Math.round(Number(c.insights.spend) * 100), c.currency, locale) : "—"} />
                  <Figure label={t("mk.m.reach")} value={n(c.insights.reach)} />
                  <Figure label={t("mk.m.clicks")} value={n(c.insights.clicks)} />
                  <Figure label={t("mk.m.impressions")} value={n(c.insights.impressions)} />
                </div>
                <p className="flex flex-wrap items-center gap-x-2 text-[12px] text-muted"><Icon name="clock" className="h-3.5 w-3.5" /><span className="tnum">{money(c.dailyBudgetMinor, c.currency, locale)} {t("mk.perDay")}</span><span>·</span><span className="tnum">{c.startsAt ? dateTime(c.startsAt, locale) : ""} → {c.endsAt ? dateTime(c.endsAt, locale) : ""}</span></p>
                {c.lastError && <Alert tone="red">{c.lastError}</Alert>}
                {canWrite && (
                  <div className="flex flex-wrap gap-2">
                    {(c.status === "PAUSED" || c.status === "DRAFT" || c.status === "FAILED") && <Button size="sm" variant="ink" loading={setStatus.loading} onClick={() => void setStatus.run(c.id, "launch")}><Icon name="play" className="h-4 w-4" />{t("mk.launch")}</Button>}
                    {c.status === "ACTIVE" && <Button size="sm" variant="secondary" loading={setStatus.loading} onClick={() => void setStatus.run(c.id, "pause")}>{t("mk.pause")}</Button>}
                    <Button size="sm" variant="ghost" loading={refresh.loading} onClick={() => void refresh.run(c.id)}><Icon name="return" className="h-4 w-4" />{t("mk.refresh")}</Button>
                  </div>
                )}
              </motion.li>
            ))}
          </AnimatePresence>
        </ul>
      )}
      {wizard && <Wizard ov={ov} onClose={() => setWizard(false)} onCreated={() => { setWizard(false); void reload(); }} />}
    </div>
  );
}
