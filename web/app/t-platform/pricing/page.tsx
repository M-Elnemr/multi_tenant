import Link from "next/link";
import { Icon } from "@/components/icons";
import { backendJson } from "@/lib/backend";
import { money } from "@/lib/format";
import { getT } from "@/lib/i18n-server";

type Plan = { code: string; name: string; tenantType: "STORE" | "CLINIC"; priceMinor: number; currency: string; features: Record<string, boolean | number> };

// whatsapp_notifications is deliberately not listed: WhatsApp is a manual button, not an automatic service
const SHOWN = ["max_products", "max_branches", "max_staff", "max_monthly_orders", "max_monthly_appointments", "custom_domain", "coupons", "reviews", "advanced_reports", "patient_portal"];

export default async function Pricing() {
  const { t, locale } = await getT();
  const plans = await backendJson<Plan[]>("/billing/plans").catch(() => [] as Plan[]);
  const price = plans[0];
  return (
    <div className="mx-auto max-w-4xl px-4 py-14">
      <h1 className="text-gradient animate-fade-up text-center text-3xl font-extrabold sm:text-4xl">{t("pricing.title")}</h1>
      <p className="mx-auto mt-3 max-w-xl text-center text-slate-600">{t("pricing.subtitle")}</p>
      {price && (
        <div className="animate-fade-up mx-auto mt-10 max-w-3xl overflow-hidden rounded-3xl border border-slate-200/80 bg-white shadow-soft">
          <div className="bg-brand-gradient px-8 py-9 text-center text-white">
            <p className="text-sm font-semibold opacity-90">{t("pricing.onePlan")}</p>
            <p className="mt-2"><span className="text-5xl font-extrabold">{money(price.priceMinor, price.currency, locale)}</span><span className="opacity-90"> / {t("pricing.month")}</span></p>
            <p className="mt-2 text-sm opacity-90">{t("pricing.sameForBoth")}</p>
          </div>
          <div className="grid gap-8 p-8 md:grid-cols-2">
            {(["STORE", "CLINIC"] as const).map((type) => {
              const p = plans.find((x) => x.tenantType === type);
              if (!p) return null;
              return (
                <section key={type}>
                  <h2 className="mb-3 flex items-center gap-2 text-lg font-bold"><span className="flex h-9 w-9 items-center justify-center rounded-xl bg-brand-soft text-brand"><Icon name={type === "STORE" ? "bag" : "stethoscope"} className="h-5 w-5" /></span>{type === "STORE" ? t("type.store") : t("type.clinic")}</h2>
                  <ul className="space-y-1.5 text-sm text-slate-700">
                    {SHOWN.filter((k) => k in p.features && p.features[k] !== false).map((k) => {
                      const v = p.features[k];
                      return (
                        <li key={k} className="flex items-center gap-2">
                          <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-emerald-50 text-emerald-600"><Icon name="check" className="h-3 w-3" /></span>{t(`feature.${k}`)}{typeof v === "number" ? `: ${v}` : v === true && k.startsWith("max_") ? `: ${t("pricing.unlimited")}` : ""}
                        </li>
                      );
                    })}
                  </ul>
                  <Link href={`/register?type=${type}`} className="mt-6 block rounded-xl bg-brand-gradient py-3 text-center text-sm font-semibold text-white shadow-brand transition hover:-translate-y-px hover:brightness-110 active:scale-[.97]">{t("pricing.trial")}</Link>
                </section>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}
