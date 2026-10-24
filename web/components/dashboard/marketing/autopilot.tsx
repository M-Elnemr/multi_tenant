"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { toMinor } from "@/lib/format";
import { useAction, useApi } from "../../hooks";
import { useI18n } from "../../i18n-provider";
import { Alert, Badge, Button, Card, ErrorText, Field, Input, Loading, Select } from "../../ui";

type G = { enabled: boolean; dailyCapMinor: number; monthlyCapMinor: number; maxStepPct: number; targetCpcMinor: number };
type A = { id: number; campaign: string; kind: string; reason: string; beforeValue: number | null; afterValue: number | null; createdAt: string; undoneAt: string | null };
type Camp = { id: string; name: string; status: string };
type Variant = { id: string; headline: string; primaryText: string; status: string; insights: { clicks?: string; spend?: string } };

/** Hard limits for the ad optimizer, what it did (each with Undo), and three competing Arabic texts per campaign. */
export function AutopilotView({ canWrite }: { canWrite: boolean }) {
  const { t, locale } = useI18n();
  const g = useApi<G>("store/marketing/guardrails");
  const log = useApi<A[]>("store/marketing/ad-actions");
  const camps = useApi<Camp[]>("store/marketing/campaigns");
  const [f, setF] = useState<{ enabled: boolean; target: string; daily: string; monthly: string; step: string } | null>(null);
  const cur = f ?? (g.data ? { enabled: g.data.enabled, target: String(g.data.targetCpcMinor / 100 || ""), daily: String(g.data.dailyCapMinor / 100 || ""), monthly: String(g.data.monthlyCapMinor / 100 || ""), step: String(g.data.maxStepPct) } : null);
  const save = useAction(async () => {
    if (!cur) return;
    await api("store/marketing/guardrails", { method: "PUT", body: { enabled: cur.enabled, targetCpcMinor: toMinor(cur.target || "0"), dailyCapMinor: toMinor(cur.daily || "0"), monthlyCapMinor: toMinor(cur.monthly || "0"), maxStepPct: Number(cur.step) } });
    setF(null); await g.reload();
  });
  const run = useAction(async () => { await api("store/marketing/ad-optimize", { body: {} }); await log.reload(); });
  const undo = useAction(async (id: number) => { await api(`store/marketing/ad-actions/${id}/undo`, { body: {} }); await log.reload(); });
  const [open, setOpen] = useState<string | null>(null);
  if (!cur) return <Loading />;
  return (
    <div className="space-y-5">
      <Card className="space-y-4">
        <div><h2 className="font-medium">{t("ap.title")}</h2><p className="mt-1 text-sm text-slate-500">{t("ap.hint")}</p></div>
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
          <Field label={t("ap.enabled")}><Select value={cur.enabled ? "1" : "0"} disabled={!canWrite} onChange={(e) => setF({ ...cur, enabled: e.target.value === "1" })}><option value="0">{t("wr.off")}</option><option value="1">{t("wr.on")}</option></Select></Field>
          <Field label={t("ap.target")} hint={t("ap.targetHint")}><Input dir="ltr" inputMode="decimal" disabled={!canWrite} value={cur.target} onChange={(e) => setF({ ...cur, target: e.target.value })} /></Field>
          <Field label={t("ap.daily")}><Input dir="ltr" inputMode="decimal" disabled={!canWrite} value={cur.daily} onChange={(e) => setF({ ...cur, daily: e.target.value })} /></Field>
          <Field label={t("ap.monthly")}><Input dir="ltr" inputMode="decimal" disabled={!canWrite} value={cur.monthly} onChange={(e) => setF({ ...cur, monthly: e.target.value })} /></Field>
          <Field label={t("ap.step")}><Input dir="ltr" inputMode="numeric" disabled={!canWrite} value={cur.step} onChange={(e) => setF({ ...cur, step: e.target.value })} /></Field>
        </div>
        <Alert tone="amber">{t("ap.zeroNote")}</Alert>
        <ErrorText error={save.error ?? run.error ?? g.error} />
        {canWrite && <div className="flex flex-wrap gap-2"><Button loading={save.loading} onClick={() => void save.run()}>{t("common.save")}</Button><Button variant="secondary" loading={run.loading} onClick={() => void run.run()}>{t("ap.runNow")}</Button></div>}
      </Card>
      <Card className="space-y-3">
        <h2 className="font-medium">{t("ap.log")}</h2>
        <ErrorText error={undo.error} />
        {(log.data ?? []).length === 0 ? <p className="text-sm text-slate-500">{t("ap.none")}</p> : log.data?.map((a) => (
          <div key={a.id} className="flex flex-wrap items-start justify-between gap-2 border-t pt-2 text-sm first:border-0 first:pt-0">
            <div className="min-w-0"><div className="flex flex-wrap items-center gap-2"><Badge tone={a.kind.startsWith("PAUSE") ? "amber" : a.kind === "RAISE_BUDGET" ? "green" : "blue"}>{t(`ap.kind.${a.kind}`)}</Badge><span className="font-medium">{a.campaign}</span></div><p className="mt-1 text-xs text-slate-500">{a.reason}</p></div>
            {a.undoneAt ? <span className="text-xs text-slate-400">{t("ap.undone")}</span> : canWrite && <Button size="sm" variant="ghost" loading={undo.loading} onClick={() => void undo.run(a.id)}>{t("ap.undo")}</Button>}
          </div>
        ))}
      </Card>
      <Card className="space-y-3">
        <h2 className="font-medium">{t("ap.variants")}</h2>
        <p className="text-sm text-slate-500">{t("ap.variantsHint")}</p>
        {(camps.data ?? []).filter((c) => c.status === "ACTIVE" || c.status === "PAUSED").map((c) => <CampaignVariants key={c.id} c={c} canWrite={canWrite} open={open === c.id} toggle={() => setOpen(open === c.id ? null : c.id)} locale={locale} />)}
        {(camps.data ?? []).length === 0 && <p className="text-sm text-slate-500">{t("ap.noCampaigns")}</p>}
      </Card>
    </div>
  );
}

function CampaignVariants({ c, canWrite, open, toggle, locale }: { c: Camp; canWrite: boolean; open: boolean; toggle: () => void; locale: string }) {
  const { t } = useI18n();
  const v = useApi<Variant[]>(open ? `store/marketing/campaigns/${c.id}/variants` : null);
  const start = useAction(async () => { await api(`store/marketing/campaigns/${c.id}/variants`, { body: { language: locale } }); await v.reload(); });
  return (
    <div className="border-t pt-2 first:border-0 first:pt-0">
      <button type="button" onClick={toggle} className="flex w-full items-center justify-between text-start text-sm font-medium">{c.name}<span className="text-xs text-slate-400">{open ? "−" : "+"}</span></button>
      {open && (
        <div className="mt-2 space-y-2">
          <ErrorText error={v.error ?? start.error} />
          {(v.data ?? []).length === 0 ? (canWrite && <Button size="sm" loading={start.loading} onClick={() => void start.run()}>{t("ap.variants.start")}</Button>) : v.data?.map((x) => (
            <div key={x.id} className="rounded-xl bg-slate-50 p-3 text-sm"><div className="flex items-center justify-between gap-2"><span className="font-semibold">{x.headline}</span><Badge tone={x.status === "WINNER" ? "green" : x.status === "PAUSED" ? "amber" : "blue"}>{t(`ap.vstatus.${x.status}`)}</Badge></div><p className="text-slate-600">{x.primaryText}</p>{x.insights.clicks && <p className="mt-1 text-xs text-slate-500">{x.insights.clicks} · {x.insights.spend}</p>}</div>
          ))}
        </div>
      )}
    </div>
  );
}
