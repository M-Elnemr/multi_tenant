"use client";

import Link from "next/link";
import { useState } from "react";
import { useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Card } from "@/components/ui";

type Check = { key: string; ok: boolean; weight: number; count?: number };
type Readiness = { score: number; checks: Check[]; products: number };

const WHERE: Record<string, string> = {
  products: "/dashboard/products", prices: "/dashboard/products", photos: "/dashboard/products", descriptions: "/dashboard/products", details: "/dashboard/products",
  categories: "/dashboard/products", delivery: "/dashboard/shipping", returns: "/dashboard/settings", hours: "/dashboard/settings", shippingText: "/dashboard/settings",
  address: "/dashboard/settings", lessons: "/dashboard/ai",
};

/** How ready the shop's data is for the assistant: it only says what the shop wrote, so the gaps that cost the most come first, each with where to fix it. */
export function AiReadiness() {
  const { t } = useI18n();
  const r = useApi<Readiness>("store/ai/readiness");
  const [open, setOpen] = useState(false);
  if (!r.data) return null;
  const { score, checks } = r.data;
  const missing = checks.filter((c) => !c.ok);
  const tone = score >= 85 ? "text-emerald-700" : score >= 60 ? "text-amber-700" : "text-red-700";
  return (
    <Card className="mb-4 space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <p className="text-sm font-semibold text-ink">{t("ready.title")}</p>
          <p className="text-xs text-slate-500">{missing.length === 0 ? t("ready.allGood") : t("ready.hint")}</p>
        </div>
        <p className={`text-3xl font-black ${tone}`}>{score}<span className="text-base font-bold text-slate-400">/100</span></p>
      </div>
      <div className="h-2 overflow-hidden rounded-full bg-slate-100"><div className={`h-full rounded-full ${score >= 85 ? "bg-emerald-500" : score >= 60 ? "bg-amber-500" : "bg-red-500"}`} style={{ width: `${score}%` }} /></div>
      {missing.length > 0 && (
        <ul className="space-y-1.5">
          {(open ? missing : missing.slice(0, 3)).map((c) => (
            <li key={c.key} className="flex items-start justify-between gap-3 text-sm">
              <span className="text-slate-700">• {t(`ready.${c.key}`)}{c.count ? ` (${c.count})` : ""}</span>
              <Link href={WHERE[c.key] ?? "/dashboard/settings"} className="shrink-0 text-xs font-semibold text-brand underline">{t("ready.fix")}</Link>
            </li>
          ))}
        </ul>
      )}
      {missing.length > 3 && <button type="button" onClick={() => setOpen(!open)} className="text-xs font-semibold text-slate-500 underline">{open ? t("ready.less") : t("ready.more")}</button>}
    </Card>
  );
}
