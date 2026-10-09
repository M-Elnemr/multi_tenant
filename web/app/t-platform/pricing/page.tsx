import Link from "next/link";
import { Icon } from "@/components/icons";
import { backendJson } from "@/lib/backend";
import { money } from "@/lib/format";
import { getT } from "@/lib/i18n-server";

type Plan = { code: string; name: string; tenantType: "STORE" | "CLINIC"; priceMinor: number; currency: string; features: Record<string, boolean | number> };

const SHOWN = ["max_products", "max_branches", "max_staff", "max_monthly_orders", "max_monthly_appointments", "custom_domain", "coupons", "reviews", "advanced_reports", "patient_portal", "whatsapp_notifications"];

export default async function Pricing() {
  const { t, locale } = await getT();
  const plans = await backendJson<Plan[]>("/billing/plans").catch(() => [] as Plan[]);
  return (
    <div className="mx-auto max-w-6xl px-4 py-14">
      <h1 className="text-gradient animate-fade-up text-center text-3xl font-extrabold sm:text-4xl">{t("pricing.title")}</h1>
      <p className="mx-auto mt-3 max-w-xl text-center text-slate-600">{t("pricing.subtitle")}</p>
      {(["STORE", "CLINIC"] as const).map((type) => (
        <section key={type} className="mt-12">
          <h2 className="mb-4 flex items-center gap-2 text-xl font-bold"><span className="flex h-9 w-9 items-center justify-center rounded-xl bg-brand-soft text-brand"><Icon name={type === "STORE" ? "bag" : "stethoscope"} className="h-5 w-5" /></span>{type === "STORE" ? t("type.store") : t("type.clinic")}</h2>
          <div className="stagger grid gap-5 md:grid-cols-2">
            {plans.filter((p) => p.tenantType === type).map((p, i) => (
              <div key={p.code} style={{ "--i": i } as React.CSSProperties} className="hover-lift rounded-3xl border border-slate-200/80 bg-white p-7 shadow-soft">
                <h3 className="text-lg font-bold">{p.name}</h3>
                <p className="my-3"><span className="text-gradient text-4xl font-extrabold">{money(p.priceMinor, p.currency, locale)}</span><span className="text-slate-500"> / {t("pricing.month")}</span></p>
                <ul className="space-y-1.5 text-sm text-slate-700">
                  {SHOWN.filter((k) => k in p.features).map((k) => {
                    const v = p.features[k];
                    return (
                      <li key={k} className={`flex items-center gap-2 ${v === false ? "text-slate-400 line-through" : ""}`}>
                        <span className={`flex h-5 w-5 shrink-0 items-center justify-center rounded-full ${v === false ? "bg-slate-100" : "bg-emerald-50 text-emerald-600"}`}>{v === false ? "✕" : <Icon name="check" className="h-3 w-3" />}</span>{t(`feature.${k}`)}{typeof v === "number" ? `: ${v}` : v === true && k.startsWith("max_") ? `: ${t("pricing.unlimited")}` : ""}
                      </li>
                    );
                  })}
                </ul>
                <Link href={`/register?type=${type}`} className="mt-6 block rounded-xl bg-brand-gradient py-3 text-center text-sm font-semibold text-white shadow-brand transition hover:-translate-y-px hover:brightness-110 active:scale-[.97]">{t("pricing.trial")}</Link>
              </div>
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}
