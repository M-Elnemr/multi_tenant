import Link from "next/link";
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
      <h1 className="text-center text-3xl font-bold">{t("pricing.title")}</h1>
      <p className="mx-auto mt-3 max-w-xl text-center text-slate-600">{t("pricing.subtitle")}</p>
      {(["STORE", "CLINIC"] as const).map((type) => (
        <section key={type} className="mt-12">
          <h2 className="mb-4 text-xl font-semibold">{type === "STORE" ? t("type.store") : t("type.clinic")}</h2>
          <div className="grid gap-4 md:grid-cols-2">
            {plans.filter((p) => p.tenantType === type).map((p) => (
              <div key={p.code} className="rounded-2xl border border-slate-200 bg-white p-6">
                <h3 className="text-lg font-semibold">{p.name}</h3>
                <p className="my-3"><span className="text-3xl font-bold">{money(p.priceMinor, p.currency, locale)}</span><span className="text-slate-500"> / {t("pricing.month")}</span></p>
                <ul className="space-y-1.5 text-sm text-slate-700">
                  {SHOWN.filter((k) => k in p.features).map((k) => {
                    const v = p.features[k];
                    return (
                      <li key={k} className={v === false ? "text-slate-400 line-through" : ""}>
                        {v === false ? "✕" : "✓"} {t(`feature.${k}`)}{typeof v === "number" ? `: ${v}` : v === true && k.startsWith("max_") ? `: ${t("pricing.unlimited")}` : ""}
                      </li>
                    );
                  })}
                </ul>
                <Link href={`/register?type=${type}`} className="mt-5 block rounded-lg bg-brand py-2.5 text-center text-sm font-medium text-white">{t("pricing.trial")}</Link>
              </div>
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}
