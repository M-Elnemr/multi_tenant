"use client";

import { api } from "@/lib/client";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Alert, Card, ErrorText, Field, Select } from "../ui";

type M = { currency: string; timezone: string; vatPercent?: number | null; country?: string | null; productVariants: number; presets: { country: string; currency: string; vatPercent: number; nameAr: string; nameEn: string }[] };

/** Country preset: currency, VAT and time zone in one step (Egypt, Saudi Arabia, UAE). Existing prices are not converted. */
export default function MarketCard() {
  const { t, locale } = useI18n();
  const m = useApi<M>("store/market");
  const apply = useAction(async (country: string) => { await api("store/market", { method: "PUT", body: { country } }); await m.reload(); window.location.reload(); });
  if (!m.data) return null;
  return (
    <Card className="space-y-3">
      <div><h2 className="font-medium">{t("mkt.title")}</h2><p className="mt-1 text-sm text-slate-500">{t("mkt.hint")}</p></div>
      <Field label={t("mkt.country")}><Select value={m.data.country ?? ""} onChange={(e) => { if (e.target.value) void apply.run(e.target.value); }}><option value="">-</option>{m.data.presets.map((p) => <option key={p.country} value={p.country}>{locale === "ar" ? p.nameAr : p.nameEn} · {p.currency} · VAT {p.vatPercent}%</option>)}</Select></Field>
      <p className="text-xs text-slate-500">{m.data.currency} · {m.data.timezone} · VAT {m.data.vatPercent ?? 0}%</p>
      {m.data.productVariants > 0 && <Alert tone="amber">{t("mkt.warn")}</Alert>}
      <ErrorText error={apply.error} />
    </Card>
  );
}
