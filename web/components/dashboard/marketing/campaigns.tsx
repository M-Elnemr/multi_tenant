"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { fromMinor, money, toMinor } from "@/lib/format";
import { Page, useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Badge, Button, Card, Empty, ErrorText, Field, Input, Loading, Modal, Select } from "@/components/ui";
import { dateTime } from "@/lib/format";
import { fileUrl } from "@/lib/media";
import { COUNTRIES } from "../channels/common";
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
    onCreated();
  });
  const set = (patch: Partial<NonNullable<typeof f>>) => setF((x) => (x ? { ...x, ...patch } : x));
  const ready = !!accountId && (target === "PRODUCT" ? !!product : !!post);
  return (
    <Modal open onClose={onClose} title={t("mk.newCampaign")} wide>
      <div className="mb-5 flex items-center gap-2 text-xs font-semibold text-slate-500">
        {[1, 2].map((n) => <span key={n} className={`flex items-center gap-1.5 ${step === n ? "text-brand-dark" : ""}`}><span className={`grid h-6 w-6 place-items-center rounded-full ${step >= n ? "bg-brand text-white" : "bg-slate-200"}`}>{n}</span>{t(`mk.step${n}`)}{n === 1 && <span className="mx-1 h-px w-8 bg-slate-300" />}</span>)}
      </div>
      {step === 1 && (
        <div className="space-y-4">
          {ov.adAccounts.length > 1 && <Field label={t("mk.adAccount")}><Select value={accountId} onChange={(e) => setAccountId(e.target.value)}>{ov.adAccounts.map((a) => <option key={a.id} value={a.id}>{a.label} ({a.externalId})</option>)}</Select></Field>}
          {pages.length > 1 && <Field label={t("mk.page")}><Select value={pageId} onChange={(e) => setPageId(e.target.value)}>{pages.map((p) => <option key={p.id} value={p.externalId}>{p.name ?? p.externalId}</option>)}</Select></Field>}
          <div className="inline-flex gap-1 rounded-xl bg-slate-100/80 p-1 text-sm font-semibold">
            {(["PRODUCT", "POST"] as const).map((k) => <button key={k} type="button" onClick={() => setTarget(k)} className={`rounded-lg px-4 py-1.5 ${k === target ? "bg-white text-brand-dark shadow-sm" : "text-slate-600"}`}>{t(`mk.target.${k}`)}</button>)}
          </div>
          {target === "PRODUCT" ? (product ? (
            <div className="flex items-center justify-between rounded-xl border border-brand/40 bg-brand-soft/40 px-3 py-2 text-sm"><span className="font-semibold">{product.name}</span><button className="text-xs font-semibold text-brand" onClick={() => setProduct(null)}>{t("mk.change")}</button></div>
          ) : (
            <div><Input placeholder={t("mk.pickProduct")} value={q} onChange={(e) => setQ(e.target.value)} />
              <ul className="mt-2 max-h-48 divide-y divide-slate-100 overflow-y-auto rounded-xl border border-slate-200">{found.data?.data.map((p) => <li key={p.id}><button type="button" className="w-full px-3 py-2 text-start text-sm hover:bg-slate-50" onClick={() => setProduct(p)}>{p.name}</button></li>)}</ul></div>
          )) : (
            <div>
              <p className="mb-2 text-xs text-slate-500">{t("mk.target.postHint")}</p>
              <ul className="grid max-h-64 grid-cols-2 gap-2 overflow-y-auto sm:grid-cols-3">{posts.data?.data.filter((p) => p.imageFileId || p.imageUrl).map((p) => (
                <li key={p.id}><button type="button" onClick={() => setPost(p)} className={`w-full overflow-hidden rounded-xl border-2 text-start ${post?.id === p.id ? "border-brand" : "border-transparent"}`}>
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={p.imageFileId ? fileUrl(p.imageFileId, "thumb") : p.imageUrl!} alt="" className="aspect-square w-full object-cover" /><span className="line-clamp-2 block p-1.5 text-xs">{p.caption}</span></button></li>
              ))}</ul>
              {posts.data && posts.data.data.every((p) => !p.imageFileId && !p.imageUrl) && <p className="text-sm text-slate-500">{t("mk.target.noPosts")}</p>}
            </div>
          )}
          <ErrorText error={suggest.error} />
          <div className="flex justify-end gap-2"><Button variant="secondary" onClick={onClose}>{t("common.cancel")}</Button><Button loading={suggest.loading} disabled={!ready} onClick={() => void suggest.run()}>✨ {t("mk.suggest")}</Button></div>
        </div>
      )}
      {step === 2 && f && (
        <form onSubmit={(e) => { e.preventDefault(); void create.run(); }} className="space-y-4">
          <Alert tone="blue">✨ {f.reasoning} {!f.fromAi && <span className="opacity-70">({t("mk.ruleBased")})</span>}</Alert>
          <Field label={`${t("mk.campaignName")} (${t("ch.optional")})`}><Input value={f.name} onChange={(e) => set({ name: e.target.value })} /></Field>
          <div className="grid gap-4 sm:grid-cols-3">
            <Field label={t("mk.objective")}><Select value={f.objective} onChange={(e) => set({ objective: e.target.value })}>{OBJECTIVES.map((o) => <option key={o} value={o}>{t(`mk.obj.${o}`)}</option>)}</Select></Field>
            <Field label={`${t("mk.dailyBudget")} (${f.currency})`}><Input inputMode="decimal" value={f.budget} onChange={(e) => set({ budget: e.target.value })} required /></Field>
            <Field label={t("mk.duration")}><Input inputMode="numeric" value={String(f.durationDays)} onChange={(e) => set({ durationDays: Number(e.target.value) || 1 })} /></Field>
          </div>
          <p className="-mt-2 text-xs text-slate-500">{t("mk.totalSpend", { total: money(toMinor(f.budget) * f.durationDays, f.currency, locale) })}</p>
          <div>
            <p className="mb-1 text-sm font-medium">{t("mk.countries")}</p>
            <div className="flex flex-wrap gap-2">{COUNTRIES.map((c) => { const on = f.countries.includes(c.code); return <button key={c.code} type="button" onClick={() => set({ countries: on ? f.countries.filter((x) => x !== c.code) : [...f.countries, c.code] })} className={`rounded-xl border px-3 py-1.5 text-sm font-semibold ${on ? "border-brand bg-brand-soft text-brand-dark" : "border-slate-200 text-slate-600"}`}>{c.flag} {t(`country.${c.code}`)}</button>; })}</div>
          </div>
          <div className="grid gap-4 sm:grid-cols-3">
            <Field label={t("mk.ageMin")}><Input inputMode="numeric" value={String(f.ageMin)} onChange={(e) => set({ ageMin: Number(e.target.value) || 18 })} /></Field>
            <Field label={t("mk.ageMax")}><Input inputMode="numeric" value={String(f.ageMax)} onChange={(e) => set({ ageMax: Number(e.target.value) || 65 })} /></Field>
            <Field label={t("mk.gender")}><Select value={f.genders.length === 1 ? f.genders[0] : "all"} onChange={(e) => set({ genders: e.target.value === "all" ? [] : [e.target.value] })}><option value="all">{t("mk.gender.all")}</option><option value="male">{t("mk.gender.male")}</option><option value="female">{t("mk.gender.female")}</option></Select></Field>
          </div>
          <Field label={t("mk.interests")} hint={t("mk.interestsHint")}>
            <div className="flex flex-wrap items-center gap-1.5 rounded-xl border border-slate-300 p-2">
              {f.interests.map((i) => <span key={i} className="inline-flex items-center gap-1 rounded-full bg-brand-soft px-2.5 py-1 text-xs font-semibold text-brand-dark">{i}<button type="button" aria-label="remove" onClick={() => set({ interests: f.interests.filter((x) => x !== i) })}>×</button></span>)}
              <input className="min-w-[8rem] flex-1 bg-transparent px-1 py-1 text-sm outline-none" value={f.interestText} onChange={(e) => set({ interestText: e.target.value })} onKeyDown={(e) => { if ((e.key === "Enter" || e.key === ",") && f.interestText.trim()) { e.preventDefault(); set({ interests: [...f.interests, f.interestText.trim()].slice(0, 8), interestText: "" }); } }} />
            </div>
          </Field>
          <Field label={t("mk.headline")}><Input maxLength={40} value={f.headline} onChange={(e) => set({ headline: e.target.value })} required /></Field>
          <Field label={t("mk.primaryText")}><textarea rows={3} maxLength={220} value={f.primaryText} onChange={(e) => set({ primaryText: e.target.value })} required className="w-full rounded-xl border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20" /></Field>
          <Field label={t("mk.cta")}><Select value={f.cta} onChange={(e) => set({ cta: e.target.value })}>{CTAS.map((c) => <option key={c} value={c}>{t(`mk.cta.${c}`)}</option>)}</Select></Field>
          <label className="flex items-start gap-3 rounded-xl border border-slate-200 p-3 text-sm"><input type="checkbox" className="mt-1" checked={f.launch} onChange={(e) => set({ launch: e.target.checked })} /><span><b>{t("mk.launchNow")}</b><br /><span className="text-slate-500">{t("mk.launchHint")}</span></span></label>
          <ErrorText error={create.error} />
          <div className="flex justify-between gap-2"><Button type="button" variant="ghost" onClick={() => setStep(1)}>{t("common.back")}</Button><Button type="submit" loading={create.loading} disabled={f.countries.length === 0}>{f.launch ? t("mk.createLaunch") : t("mk.create")}</Button></div>
        </form>
      )}
    </Modal>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return <div><p className="text-lg font-bold text-ink">{value}</p><p className="text-xs text-slate-500">{label}</p></div>;
}

export function CampaignsView({ ov, canWrite }: { ov: Overview; canWrite: boolean }) {
  const { t, locale } = useI18n();
  const { data, loading, reload } = useApi<Campaign[]>("store/marketing/campaigns");
  const [wizard, setWizard] = useState(false);
  const setStatus = useAction(async (id: string, to: "launch" | "pause") => { await api(`store/marketing/campaigns/${id}/${to}`, { body: {} }); await reload(); });
  const refresh = useAction(async (id: string) => { await api(`store/marketing/campaigns/${id}/refresh`, { body: {} }); await reload(); });
  const n = (v?: string) => (v ? Number(v).toLocaleString(locale === "ar" ? "ar-EG" : "en-GB") : "—");
  if (ov.adAccounts.length === 0) {
    return <Empty><p className="mb-1 text-base font-bold text-ink">{t("mk.noAdAccount.title")}</p><p className="mx-auto max-w-md">{t("mk.noAdAccount.body")}</p></Empty>;
  }
  return (
    <div className="space-y-5">
      <div className="flex items-center justify-between gap-3"><h2 className="font-bold">{t("mk.campaigns")}</h2>{canWrite && <Button onClick={() => setWizard(true)}>＋ {t("mk.newCampaign")}</Button>}</div>
      <ErrorText error={setStatus.error ?? refresh.error} />
      {loading && !data ? <Loading /> : data && data.length === 0 ? <Empty>{t("mk.noCampaigns")}</Empty> : (
        <div className="grid gap-4 lg:grid-cols-2">
          {data?.map((c) => (
            <Card key={c.id} className="space-y-4">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0"><p className="truncate font-bold">{c.name}</p><p className="text-xs text-slate-500">{c.productName ?? t("mk.target.POST")} · {t(`mk.obj.${c.objective}`)}</p></div>
                <Badge tone={TONE[c.status] ?? "slate"}>{t(`mk.cstatus.${c.status}`)}</Badge>
              </div>
              <div className="grid grid-cols-4 gap-2 text-center">
                <Metric label={t("mk.m.spend")} value={c.insights.spend ? money(Math.round(Number(c.insights.spend) * 100), c.currency, locale) : "—"} />
                <Metric label={t("mk.m.reach")} value={n(c.insights.reach)} />
                <Metric label={t("mk.m.clicks")} value={n(c.insights.clicks)} />
                <Metric label={t("mk.m.impressions")} value={n(c.insights.impressions)} />
              </div>
              <p className="text-xs text-slate-500">{money(c.dailyBudgetMinor, c.currency, locale)} {t("mk.perDay")} · {c.startsAt ? dateTime(c.startsAt, locale) : ""} → {c.endsAt ? dateTime(c.endsAt, locale) : ""}</p>
              {c.lastError && <Alert tone="red">{c.lastError}</Alert>}
              {canWrite && (
                <div className="flex flex-wrap gap-2">
                  {(c.status === "PAUSED" || c.status === "DRAFT" || c.status === "FAILED") && <Button size="sm" loading={setStatus.loading} onClick={() => void setStatus.run(c.id, "launch")}>{t("mk.launch")}</Button>}
                  {c.status === "ACTIVE" && <Button size="sm" variant="secondary" loading={setStatus.loading} onClick={() => void setStatus.run(c.id, "pause")}>{t("mk.pause")}</Button>}
                  <Button size="sm" variant="ghost" loading={refresh.loading} onClick={() => void refresh.run(c.id)}>↻ {t("mk.refresh")}</Button>
                </div>
              )}
            </Card>
          ))}
        </div>
      )}
      {wizard && <Wizard ov={ov} onClose={() => setWizard(false)} onCreated={() => { setWizard(false); void reload(); }} />}
    </div>
  );
}
