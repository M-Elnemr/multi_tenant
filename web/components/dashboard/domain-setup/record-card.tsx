"use client";

import { useState } from "react";
import { useI18n } from "../../i18n-provider";
import { Badge } from "../../ui";

export type DnsRecord = { purpose: "VERIFY" | "ROOT" | "WWW"; type: string; name: string; shortName: string; value: string; status: "OK" | "MISSING" | "WRONG"; found: string[]; hint?: string | null };

function CopyField({ label, value, sub }: { label: string; value: string; sub?: string }) {
  const { t } = useI18n();
  const [done, setDone] = useState(false);
  const copy = async () => {
    try { await navigator.clipboard.writeText(value); } catch {
      const el = document.createElement("textarea"); el.value = value; document.body.appendChild(el); el.select(); try { document.execCommand("copy"); } catch { /* ignore */ } el.remove();
    }
    setDone(true); setTimeout(() => setDone(false), 1500);
  };
  return (
    <div className="min-w-0">
      <p className="mb-1 text-xs font-semibold text-slate-500">{label}</p>
      <div className="flex items-center gap-2 rounded-xl border border-slate-200 bg-white px-3 py-2">
        <code className="min-w-0 flex-1 break-all text-sm font-semibold text-ink" dir="ltr">{value}</code>
        <button type="button" onClick={copy} className={`shrink-0 rounded-lg px-2.5 py-1 text-xs font-bold transition active:scale-95 ${done ? "bg-emerald-100 text-emerald-700" : "bg-brand-soft text-brand hover:brightness-95"}`}>{done ? t("domain.copied") : t("domain.copy")}</button>
      </div>
      {sub && <p className="mt-1 text-[11px] text-slate-400" dir="ltr">{sub}</p>}
    </div>
  );
}

const TONE = { OK: "green", MISSING: "amber", WRONG: "red" } as const;
const ICON = { OK: "✓", MISSING: "…", WRONG: "!" } as const;

/** One DNS record to create: what it is for, exactly what to type, and whether it is already right. */
export function RecordCard({ rec, host }: { rec: DnsRecord; host: string }) {
  const { t } = useI18n();
  const target = rec.purpose === "VERIFY" ? host : rec.name;
  return (
    <div className={`rounded-2xl border p-4 ${rec.status === "OK" ? "border-emerald-200 bg-emerald-50/40" : rec.status === "WRONG" ? "border-red-200 bg-red-50/40" : "border-slate-200 bg-slate-50/60"}`}>
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <span className="rounded-lg bg-ink px-2 py-0.5 font-mono text-xs font-bold text-white">{rec.type}</span>
          <p className="font-bold text-ink">{t(`domain.purpose.${rec.purpose}`, { host: target })}</p>
          {rec.purpose === "WWW" && <Badge tone="blue">{t("domain.recommended")}</Badge>}
        </div>
        <Badge tone={TONE[rec.status]}>{ICON[rec.status]} {t(`domain.status.${rec.status}`)}</Badge>
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        <CopyField label={t("domain.field.name")} value={rec.shortName} sub={rec.shortName !== rec.name ? t("domain.fullName", { name: rec.name }) : undefined} />
        <CopyField label={t("domain.field.value")} value={rec.value} />
      </div>
      <p className="mt-2 text-[11px] text-slate-400">{t("domain.ttl")}</p>
      {rec.status === "WRONG" && rec.hint && <p className="mt-2 rounded-lg bg-red-100 px-3 py-2 text-sm font-medium text-red-800">{t(`domain.hint.${rec.hint}`, { found: rec.found.join(", ") })}</p>}
    </div>
  );
}
