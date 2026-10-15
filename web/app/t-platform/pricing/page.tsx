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
  const label = (code: string) => t(`plan.${code.split("_").pop()}`);
  return (
    <div className="mx-auto max-w-5xl px-4 py-14">
      <h1 className="text-gradient animate-fade-up text-center text-3xl font-extrabold sm:text-4xl">{t("pricing.title")}</h1>
      <p className="mx-auto mt-3 max-w-xl text-center text-slate-600">{t("pricing.subtitle")}</p>
      {(["STORE", "CLINIC"] as const).map((type) => {
        const list = plans.filter((x) => x.tenantType === type);
        if (list.length === 0) return null;
        return (
          <section key={type} className="mt-12">
            <h2 className="mb-5 flex items-center gap-2 text-xl font-bold"><span className="flex h-10 w-10 items-center justify-center rounded-xl bg-brand-soft text-brand"><Icon name={type === "STORE" ? "bag" : "stethoscope"} className="h-5 w-5" /></span>{type === "STORE" ? t("type.store") : t("type.clinic")}</h2>
            <div className={`grid gap-5 ${list.length === 3 ? "md:grid-cols-3" : "md:grid-cols-2"}`}>
              {list.map((p) => {
                const free = p.priceMinor === 0;
                return (
                  <div key={p.code} className={`flex flex-col rounded-3xl border bg-white p-6 shadow-soft ${p.code.endsWith("_PRO") ? "border-brand ring-1 ring-brand/30" : "border-slate-200/80"}`}>
                    <p className="text-sm font-bold text-slate-600">{label(p.code)}</p>
                    <p className="mb-4 mt-1">
                      <span className="text-3xl font-extrabold text-brand">{free ? t("pay.free") : money(p.priceMinor, p.currency, locale)}</span>
                      {!free && <span className="text-slate-500"> / {t("pricing.month")}</span>}
                    </p>
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
          </section>
        );
      })}
    </div>
  );
}
