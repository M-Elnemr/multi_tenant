"use client";

import Link from "next/link";
import { useState } from "react";
import { AppBenefits } from "@/components/app-benefits";
import { type Cycle, CycleToggle } from "@/components/cycle-toggle";
import { planLabel } from "@/components/dashboard/pay-screen";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { money } from "@/lib/format";

export type Plan = { code: string; name: string; tenantType: "STORE" | "CLINIC"; priceMinor: number; currency: string; interval?: string; features: Record<string, boolean | number> };

// whatsapp_notifications is deliberately not listed: WhatsApp is a manual button, not an automatic service
const SHOWN = ["max_products", "max_branches", "max_staff", "max_monthly_orders", "max_monthly_appointments", "custom_domain", "coupons", "reviews", "advanced_reports", "patient_portal"];

export function PricingPlans({ plans }: { plans: Plan[] }) {
  const { t, locale } = useI18n();
  const [cycle, setCycle] = useState<Cycle>("month");
  return (
    <>
      <div className="mt-8 flex justify-center"><CycleToggle value={cycle} onChange={setCycle} /></div>
      {(["STORE", "CLINIC"] as const).map((type) => {
        const list = plans.filter((x) => x.tenantType === type && (x.priceMinor === 0 || (x.interval === "YEAR") === (cycle === "year")));
        if (list.length === 0) return null;
        return (
          <section key={type} className="mt-12">
            <h2 className="mb-5 flex items-center gap-2 text-xl font-bold"><span className="flex h-10 w-10 items-center justify-center rounded-xl bg-brand-soft text-brand"><Icon name={type === "STORE" ? "bag" : "stethoscope"} className="h-5 w-5" /></span>{type === "STORE" ? t("type.store") : t("type.clinic")}</h2>
            <div className={`grid gap-5 ${list.length === 3 ? "md:grid-cols-3" : "md:grid-cols-2"}`}>
              {list.map((p) => {
                const free = p.priceMinor === 0;
                const monthly = plans.find((x) => x.code === p.code.replace("_YEARLY", ""));
                const saving = p.interval === "YEAR" && monthly ? monthly.priceMinor * 12 - p.priceMinor : 0;
                return (
                  <div key={p.code} className={`flex flex-col rounded-3xl border bg-white p-6 shadow-soft ${p.code.includes("_PRO") ? "border-brand ring-1 ring-brand/30" : "border-slate-200/80"}`}>
                    <p className="text-sm font-bold text-slate-600">{planLabel(t, p.code)}</p>
                    <p className="mt-1">
                      <span className="text-3xl font-extrabold text-brand">{free ? t("pay.free") : money(p.priceMinor, p.currency, locale)}</span>
                      {!free && <span className="text-slate-500"> / {p.interval === "YEAR" ? t("billing.perYear") : t("pricing.month")}</span>}
                    </p>
                    <p className="mb-4 mt-1 min-h-5 text-sm font-semibold text-emerald-700">{saving > 0 ? `${t("billing.twoFree")} · ${t("billing.save", { amount: money(saving, p.currency, locale) })}` : ""}</p>
                    <ul className="mb-6 flex-1 space-y-1.5 text-sm text-slate-700">
                      {SHOWN.filter((k) => k in p.features).map((k) => {
                        const v = p.features[k];
                        const off = v === false;
                        return (
                          <li key={k} className={`flex items-center gap-2 ${off ? "text-slate-400 line-through" : ""}`}>
                            <span className={`flex h-5 w-5 shrink-0 items-center justify-center rounded-full ${off ? "bg-slate-100 text-slate-400" : "bg-emerald-50 text-emerald-600"}`}>{off ? "✕" : <Icon name="check" className="h-3 w-3" />}</span>
                            {t(`feature.${k}`)}{typeof v === "number" ? `: ${v}` : v === true && k.startsWith("max_") ? `: ${t("pricing.unlimited")}` : ""}
                          </li>
                        );
                      })}
                    </ul>
                    <Link href={`/register?type=${type}`} className="block rounded-xl bg-brand-gradient py-3 text-center text-sm font-semibold text-white shadow-brand transition hover:-translate-y-px hover:brightness-110 active:scale-[.97]">{t("pricing.trial")}</Link>
                  </div>
                );
              })}
            </div>
            <div className="mt-5"><AppBenefits kind={type} /></div>
          </section>
        );
      })}
    </>
  );
}
