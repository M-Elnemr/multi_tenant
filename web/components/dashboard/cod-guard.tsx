"use client";

import { api } from "@/lib/client";
import { money } from "@/lib/format";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Alert, Card, ErrorText, Field, Select } from "../ui";

type G = { high: number; medium: number; low: number; highLost: number; highArrived: number; savedMinor: number; waiting: number; settings: { holdHighRisk: boolean; holdMediumRisk: boolean } };

/** Settings card: how the shop treats risky cash-on-delivery orders (it always labels them; holding is the owner's choice) and what the guard did in 30 days. */
export default function CodGuardCard() {
  const { t, locale, currency } = useI18n();
  const g = useApi<G>("store/cod-guard");
  const save = useAction(async (patch: Record<string, boolean>) => { await api("store/cod-guard", { method: "PUT", body: patch }); await g.reload(); });
  const d = g.data;
  if (!d) return null;
  return (
    <Card className="space-y-4">
      <div><h2 className="font-medium">{t("risk.title")}</h2><p className="mt-1 text-sm text-slate-500">{t("risk.hint")}</p></div>
      <div className="grid gap-3 sm:grid-cols-4 text-sm">
        <div className="rounded-xl bg-red-50 p-3"><div className="text-xs text-slate-500">{t("risk.high")}</div><div className="text-xl font-bold text-red-700">{d.high}</div></div>
        <div className="rounded-xl bg-amber-50 p-3"><div className="text-xs text-slate-500">{t("risk.medium")}</div><div className="text-xl font-bold text-amber-700">{d.medium}</div></div>
        <div className="rounded-xl bg-emerald-50 p-3"><div className="text-xs text-slate-500">{t("risk.saved")}</div><div className="text-xl font-bold text-emerald-700">{money(d.savedMinor, currency, locale)}</div></div>
        <div className="rounded-xl bg-slate-50 p-3"><div className="text-xs text-slate-500">{t("risk.waiting")}</div><div className="text-xl font-bold">{d.waiting}</div></div>
      </div>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label={t("risk.holdHigh")} hint={t("risk.holdHint")}><Select value={d.settings.holdHighRisk ? "1" : "0"} onChange={(e) => void save.run({ holdHighRisk: e.target.value === "1" })}><option value="0">{t("wr.off")}</option><option value="1">{t("wr.on")}</option></Select></Field>
        <Field label={t("risk.holdMedium")}><Select value={d.settings.holdMediumRisk ? "1" : "0"} onChange={(e) => void save.run({ holdMediumRisk: e.target.value === "1" })}><option value="0">{t("wr.off")}</option><option value="1">{t("wr.on")}</option></Select></Field>
      </div>
      <Alert tone="blue">{t("risk.waNote")}</Alert>
      <ErrorText error={save.error} />
    </Card>
  );
}
