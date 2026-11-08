"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { toMinor } from "@/lib/format";
import { useAction, useApi } from "../../hooks";
import { useI18n } from "../../i18n-provider";
import { Icon } from "../../icons";
import { useToast } from "../../toast";
import { Alert, Badge, Button, Empty, ErrorText, Field, Input } from "../../ui";
import { AI_GRADIENT, Panel, PanelHead, SwitchRow } from "./kit";

type G = { enabled: boolean; dailyCapMinor: number; monthlyCapMinor: number; maxStepPct: number; targetCpcMinor: number };
type A = { id: number; campaign: string; kind: string; reason: string; beforeValue: number | null; afterValue: number | null; createdAt: string; undoneAt: string | null };
type Camp = { id: string; name: string; status: string };
type Variant = { id: string; headline: string; primaryText: string; status: string; insights: { clicks?: string; spend?: string } };

/** Hard limits for the ad optimizer, what it did (each with Undo), and three competing Arabic texts per campaign. */
export function AutopilotView({ canWrite }: { canWrite: boolean }) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const g = useApi<G>("store/marketing/guardrails");
  const log = useApi<A[]>("store/marketing/ad-actions");
  const camps = useApi<Camp[]>("store/marketing/campaigns");
  const [f, setF] = useState<{ enabled: boolean; target: string; daily: string; monthly: string; step: string } | null>(null);
  const cur = f ?? (g.data ? { enabled: g.data.enabled, target: String(g.data.targetCpcMinor / 100 || ""), daily: String(g.data.dailyCapMinor / 100 || ""), monthly: String(g.data.monthlyCapMinor / 100 || ""), step: String(g.data.maxStepPct) } : null);
  const save = useAction(async () => {
    if (!cur) return;
    await api("store/marketing/guardrails", { method: "PUT", body: { enabled: cur.enabled, targetCpcMinor: toMinor(cur.target || "0"), dailyCapMinor: toMinor(cur.daily || "0"), monthlyCapMinor: toMinor(cur.monthly || "0"), maxStepPct: Number(cur.step) } });
    setF(null); toast(t("common.saved")); await g.reload();
  });
  const run = useAction(async () => { await api("store/marketing/ad-optimize", { body: {} }); toast(t("mkx.optimized")); await log.reload(); });
  const undo = useAction(async (id: number) => { await api(`store/marketing/ad-actions/${id}/undo`, { body: {} }); toast(t("mkx.undone")); await log.reload(); });
  const [open, setOpen] = useState<string | null>(null);
  if (!cur) return <div className="space-y-5"><div className="skeleton h-72 rounded-[18px]" /><div className="skeleton h-48 rounded-[18px]" /></div>;
  return (
    <div className="space-y-5">
      <Panel className="space-y-5 p-5">
        <PanelHead icon="sparkles" ai title={t("ap.title")} hint={t("ap.hint")} />
        <SwitchRow title={t("ap.enabled")} checked={cur.enabled} disabled={!canWrite} onChange={(v) => setF({ ...cur, enabled: v })} />
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <Field label={t("ap.target")} hint={t("ap.targetHint")}><Input dir="ltr" inputMode="decimal" disabled={!canWrite} value={cur.target} onChange={(e) => setF({ ...cur, target: e.target.value })} /></Field>
          <Field label={t("ap.daily")}><Input dir="ltr" inputMode="decimal" disabled={!canWrite} value={cur.daily} onChange={(e) => setF({ ...cur, daily: e.target.value })} /></Field>
          <Field label={t("ap.monthly")}><Input dir="ltr" inputMode="decimal" disabled={!canWrite} value={cur.monthly} onChange={(e) => setF({ ...cur, monthly: e.target.value })} /></Field>
          <Field label={t("ap.step")}><Input dir="ltr" inputMode="numeric" disabled={!canWrite} value={cur.step} onChange={(e) => setF({ ...cur, step: e.target.value })} /></Field>
        </div>
        <Alert tone="amber">{t("ap.zeroNote")}</Alert>
        <ErrorText error={save.error ?? run.error ?? g.error} />
        {canWrite && <div className="flex flex-wrap justify-end gap-2"><Button variant="secondary" loading={run.loading} onClick={() => void run.run()}><Icon name="play" className="h-4 w-4" />{t("ap.runNow")}</Button><Button variant="ink" loading={save.loading} onClick={() => void save.run()}>{t("common.save")}</Button></div>}
      </Panel>

      <Panel className="space-y-3 p-5">
        <PanelHead icon="clock" title={t("ap.log")} />
        <ErrorText error={undo.error} />
        {!log.data ? <div className="skeleton h-24 rounded-[14px]" /> : log.data.length === 0 ? <Empty compact icon="clock">{t("ap.none")}</Empty> : (
          <ul className="divide-y divide-line">
            {log.data.map((a) => (
              <li key={a.id} className="flex flex-wrap items-start justify-between gap-3 py-3 first:pt-1">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2"><Badge tone={a.kind.startsWith("PAUSE") ? "amber" : a.kind === "RAISE_BUDGET" ? "green" : "blue"}>{t(`ap.kind.${a.kind}`)}</Badge><span className="text-[13.5px] font-semibold text-ink">{a.campaign}</span></div>
                  <p className="mt-1 text-[12.5px] text-muted">{a.reason}</p>
                </div>
                {a.undoneAt ? <span className="text-[12px] text-faint">{t("ap.undone")}</span> : canWrite && <Button size="sm" variant="secondary" loading={undo.loading} onClick={() => void undo.run(a.id)}><Icon name="return" className="h-4 w-4" />{t("ap.undo")}</Button>}
              </li>
            ))}
          </ul>
        )}
      </Panel>

      <Panel className="space-y-3 p-5">
        <PanelHead icon="edit" title={t("ap.variants")} hint={t("ap.variantsHint")} />
        {(camps.data ?? []).length === 0 && camps.data && <Empty compact icon="trending">{t("ap.noCampaigns")}</Empty>}
        <ul className="space-y-2">
          {(camps.data ?? []).filter((c) => c.status === "ACTIVE" || c.status === "PAUSED").map((c) => <CampaignVariants key={c.id} c={c} canWrite={canWrite} open={open === c.id} toggle={() => setOpen(open === c.id ? null : c.id)} locale={locale} />)}
        </ul>
      </Panel>
    </div>
  );
}

function CampaignVariants({ c, canWrite, open, toggle, locale }: { c: Camp; canWrite: boolean; open: boolean; toggle: () => void; locale: string }) {
  const { t } = useI18n();
  const v = useApi<Variant[]>(open ? `store/marketing/campaigns/${c.id}/variants` : null);
  const start = useAction(async () => { await api(`store/marketing/campaigns/${c.id}/variants`, { body: { language: locale } }); await v.reload(); });
  return (
    <li className="overflow-hidden rounded-[14px] ring-1 ring-line">
      <button type="button" onClick={toggle} aria-expanded={open} className="flex w-full items-center gap-3 px-4 py-3 text-start transition hover:bg-surface-2">
        <span className={`grid h-7 w-7 shrink-0 place-items-center rounded-[9px] text-white ${AI_GRADIENT}`}><Icon name="sparkles" className="h-3.5 w-3.5" /></span>
        <span className="min-w-0 flex-1 truncate text-[13.5px] font-semibold text-ink">{c.name}</span>
        <Icon name="chevron" className={`h-4 w-4 shrink-0 text-muted transition-transform ${open ? "rotate-180" : ""}`} />
      </button>
      {open && (
        <div className="space-y-2 border-t border-line bg-canvas/50 p-3">
          <ErrorText error={v.error ?? start.error} />
          {!v.data ? <div className="skeleton h-16 rounded-[12px]" /> : v.data.length === 0 ? (canWrite && <Button size="sm" variant="ink" loading={start.loading} onClick={() => void start.run()}><Icon name="sparkles" className="h-4 w-4" />{t("ap.variants.start")}</Button>) : v.data.map((x) => (
            <div key={x.id} className={`rounded-[12px] bg-surface p-3 text-[13px] ring-1 ${x.status === "WINNER" ? "ring-2 ring-emerald-500" : "ring-line"}`}>
              <div className="flex items-center justify-between gap-2"><span className="font-semibold text-ink">{x.headline}</span><Badge tone={x.status === "WINNER" ? "green" : x.status === "PAUSED" ? "amber" : "blue"}>{t(`ap.vstatus.${x.status}`)}</Badge></div>
              <p className="mt-1 text-ink-2">{x.primaryText}</p>
              {x.insights.clicks && <p className="mt-1.5 text-[12px] text-muted tnum">{x.insights.clicks} · {x.insights.spend}</p>}
            </div>
          ))}
        </div>
      )}
    </li>
  );
}
