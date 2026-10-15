"use client";

import { useT } from "./i18n-provider";

export type Cycle = "month" | "year";

/** Monthly / Yearly switch; the yearly side carries the "2 months free" offer. */
export function CycleToggle({ value, onChange }: { value: Cycle; onChange: (c: Cycle) => void }) {
  const t = useT();
  const cls = (on: boolean) => `rounded-lg px-4 py-2 text-sm font-semibold transition ${on ? "bg-white text-brand-dark shadow-sm" : "text-slate-600 hover:text-slate-800"}`;
  return (
    <div role="tablist" className="inline-flex items-center gap-1 rounded-xl bg-slate-100/80 p-1">
      <button role="tab" aria-selected={value === "month"} onClick={() => onChange("month")} className={cls(value === "month")}>{t("billing.cycle.month")}</button>
      <button role="tab" aria-selected={value === "year"} onClick={() => onChange("year")} className={cls(value === "year")}>{t("billing.cycle.year")} <span className="ms-1 rounded-full bg-emerald-100 px-2 py-0.5 text-xs font-bold text-emerald-700">{t("billing.twoFree")}</span></button>
    </div>
  );
}
