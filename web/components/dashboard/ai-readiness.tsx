"use client";

import Link from "next/link";
import { useState } from "react";
import { useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { Skeleton } from "@/components/ui";

type Check = { key: string; ok: boolean; weight: number; count?: number };
type Readiness = { score: number; checks: Check[]; products: number };

const WHERE: Record<string, string> = {
  products: "/dashboard/products", prices: "/dashboard/products", photos: "/dashboard/products", descriptions: "/dashboard/products", details: "/dashboard/products",
  categories: "/dashboard/products", delivery: "/dashboard/settings?tab=sales#shipping", returns: "/dashboard/settings?tab=profile", hours: "/dashboard/settings?tab=profile#contact", shippingText: "/dashboard/settings?tab=profile",
  address: "/dashboard/settings?tab=profile#contact", lessons: "/dashboard/ai?tab=lessons",
};

/** A small ring that shows a 0–100 score. */
export function ScoreRing({ score, size = 64 }: { score: number; size?: number }) {
  const R = 26, C = 2 * Math.PI * R;
  const color = score >= 85 ? "#10b981" : score >= 60 ? "#f59e0b" : "#ef4444";
  return (
    <span className="relative grid shrink-0 place-items-center" style={{ width: size, height: size }}>
      <svg viewBox="0 0 64 64" className="absolute inset-0 -rotate-90" aria-hidden>
        <circle cx="32" cy="32" r={R} fill="none" stroke="var(--line)" strokeWidth="6" />
        <circle cx="32" cy="32" r={R} fill="none" stroke={color} strokeWidth="6" strokeLinecap="round" strokeDasharray={`${(score / 100) * C} ${C}`} className="transition-[stroke-dasharray] duration-700" />
      </svg>
      <span className="font-display text-[17px] font-semibold text-ink tnum">{score}</span>
    </span>
  );
}

/** How ready the shop's data is for the assistant: it only says what the shop wrote, so the gaps that cost the most come first, each with where to fix it. */
export function AiReadiness() {
  const { t } = useI18n();
  const r = useApi<Readiness>("store/ai/readiness");
  const [open, setOpen] = useState(false);
  if (!r.data) return r.loading ? <Skeleton className="mb-5 h-28 w-full !rounded-[18px]" /> : null;
  const { score, checks } = r.data;
  const missing = checks.filter((c) => !c.ok);
  return (
    <section className="mb-5 overflow-hidden rounded-[18px] bg-surface ring-1 ring-line">
      <div className="flex flex-wrap items-center gap-4 p-5">
        <ScoreRing score={score} />
        <div className="min-w-0 flex-1">
          <p className="font-display text-[15px] font-semibold text-ink">{t("ready.title")}</p>
          <p className="mt-0.5 text-[13px] leading-relaxed text-muted">{missing.length === 0 ? t("ready.allGood") : t("ready.hint")}</p>
        </div>
        {missing.length === 0 && <span className="inline-flex items-center gap-1.5 rounded-full bg-emerald-50 px-2.5 py-1 text-[12px] font-semibold text-emerald-700 ring-1 ring-inset ring-emerald-600/15"><Icon name="check" className="h-3.5 w-3.5" />{t("kit.ready")}</span>}
      </div>
      {missing.length > 0 && (
        <ul className="divide-y divide-line border-t border-line">
          {(open ? missing : missing.slice(0, 3)).map((c) => (
            <li key={c.key}>
              <Link href={WHERE[c.key] ?? "/dashboard/settings?tab=profile"} className="group flex items-center gap-3 px-5 py-2.5 text-[13px] transition hover:bg-surface-2">
                <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-amber-500" />
                <span className="min-w-0 flex-1 text-ink-2">{t(`ready.${c.key}`)}{c.count ? <span className="ms-1.5 rounded-full bg-surface-2 px-1.5 text-[11px] font-semibold text-muted tnum">{c.count}</span> : null}</span>
                <span className="flex shrink-0 items-center gap-1 text-[12px] font-semibold text-brand">{t("ready.fix")}<Icon name="chevron" className="h-3.5 w-3.5 transition-transform group-hover:translate-x-0.5 flip-rtl" /></span>
              </Link>
            </li>
          ))}
        </ul>
      )}
      {missing.length > 3 && (
        <button type="button" onClick={() => setOpen(!open)} className="w-full border-t border-line px-5 py-2.5 text-[12.5px] font-semibold text-muted transition hover:bg-surface-2 hover:text-ink">{open ? t("ready.less") : t("ready.more")}</button>
      )}
    </section>
  );
}
