"use client";

import type { ReactNode } from "react";
import { Badge } from "@/components/ui";
import { useI18n } from "@/components/i18n-provider";

export type Country = "EG" | "SA" | "AE";
export const COUNTRIES: { code: Country; flag: string }[] = [{ code: "EG", flag: "🇪🇬" }, { code: "SA", flag: "🇸🇦" }, { code: "AE", flag: "🇦🇪" }];

export type Account = {
  id: string; provider: string; kind: string; country: Country; label: string; externalId: string; status: "ACTIVE" | "ERROR" | "PAUSED"; lastSyncAt?: string | null; lastError?: string | null; createdAt: string;
  listings: number; orders: number; newOrders: number; name: string; can: string[]; providerStatus: string; adminUrl?: string | null; currency: string;
};

export type CatalogField = { key: string; labelEn: string; labelAr: string; secret: boolean; placeholder: string; multiline: boolean; optional: boolean };
export type CatalogItem = { id: string; name: string; kind: string; countries: Country[]; status: "READY" | "BETA" | "SOON"; can: string[]; fields: CatalogField[]; oauth: boolean; helpEn: string; helpAr: string; docsUrl: string };

const COLORS: Record<string, string> = {
  AMAZON: "bg-[#232f3e] text-[#ff9900]", NOON: "bg-[#feee00] text-black", SHOPIFY: "bg-[#95bf47] text-white", WOOCOMMERCE: "bg-[#7f54b3] text-white", SALLA: "bg-[#004d5a] text-[#bbf3e8]", YOUCAN: "bg-[#1e66f5] text-white",
  ARAMEX: "bg-[#e1251b] text-white", BOSTA: "bg-[#e30613] text-white", MYLERZ: "bg-[#0e2a47] text-white", SMSA: "bg-[#6d2077] text-white", NAQEL: "bg-[#00a859] text-white", JT: "bg-[#d71920] text-white", SPL: "bg-[#007a3d] text-white",
  EMIRATES_POST: "bg-[#c8102e] text-white", TURBO: "bg-[#ff6a00] text-white", SHIPA: "bg-[#2b2d42] text-white", QUIQUP: "bg-[#00b5ad] text-white", ZAJIL: "bg-[#1d3557] text-white", META_ADS: "bg-[#0866ff] text-white",
};

/** The platform's colour and initials as a rounded tile (no external logos). */
export function ProviderMark({ provider, name, className = "h-11 w-11 text-sm" }: { provider: string; name?: string; className?: string }) {
  const label = (name ?? provider).replace(/[^A-Za-z0-9]/g, "");
  return <span aria-hidden className={`inline-flex shrink-0 items-center justify-center rounded-2xl font-extrabold tracking-tight shadow-sm ring-1 ring-black/5 ${COLORS[provider] ?? "bg-slate-700 text-white"} ${className}`}>{label.slice(0, 2).toUpperCase()}</span>;
}

export function CountryFlag({ code }: { code: string }) {
  return <span aria-hidden>{COUNTRIES.find((c) => c.code === code)?.flag ?? "🌍"}</span>;
}

/** Country filter chips (Egypt first and selected by default). */
export function CountryChips({ value, onChange }: { value: Country; onChange: (c: Country) => void }) {
  const { t } = useI18n();
  return (
    <div role="radiogroup" aria-label={t("ch.country")} className="inline-flex gap-1 rounded-2xl bg-slate-100/80 p-1">
      {COUNTRIES.map((c) => (
        <button key={c.code} role="radio" aria-checked={c.code === value} onClick={() => onChange(c.code)} className={`flex items-center gap-1.5 whitespace-nowrap rounded-xl px-3.5 py-2 text-sm font-semibold transition ${c.code === value ? "bg-white text-brand-dark shadow-sm" : "text-slate-600 hover:text-slate-800"}`}>
          <span aria-hidden className="text-base leading-none">{c.flag}</span>{t(`country.${c.code}`)}
        </button>
      ))}
    </div>
  );
}

const STAGE_TONE: Record<string, "amber" | "blue" | "green" | "red" | "slate"> = { NEW: "amber", PROCESSING: "blue", SHIPPED: "blue", DELIVERED: "green", CANCELLED: "red", RETURNED: "slate" };
export const STAGES = ["NEW", "PROCESSING", "SHIPPED", "DELIVERED", "CANCELLED", "RETURNED"] as const;

export function StageBadge({ stage }: { stage: string }) {
  const { t } = useI18n();
  return <Badge tone={STAGE_TONE[stage] ?? "slate"}>{t(`ch.stage.${stage}`)}</Badge>;
}

export function AccountStatus({ status, providerStatus }: { status: string; providerStatus?: string }) {
  const { t } = useI18n();
  return (
    <span className="inline-flex flex-wrap items-center gap-1.5">
      <Badge tone={status === "ACTIVE" ? "green" : status === "ERROR" ? "red" : "slate"}>{t(`ch.status.${status}`)}</Badge>
      {providerStatus === "BETA" && <Badge tone="amber">{t("ch.beta")}</Badge>}
    </span>
  );
}

/** A labelled value row for details panels. */
export function Row({ label, children }: { label: string; children: ReactNode }) {
  return <div className="flex items-start justify-between gap-4 border-b border-slate-100 py-2.5 text-sm last:border-0"><dt className="shrink-0 text-slate-500">{label}</dt><dd className="min-w-0 break-words text-end font-medium text-ink">{children}</dd></div>;
}

export type Meta = { page: number; pageSize: number; total: number; hasNext: boolean };
