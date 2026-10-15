import { backendJson } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";
import { type Plan, PricingPlans } from "./plans";

export default async function Pricing() {
  const { t } = await getT();
  const plans = await backendJson<Plan[]>("/billing/plans").catch(() => [] as Plan[]);
  return (
    <div className="mx-auto max-w-5xl px-4 py-14">
      <h1 className="text-gradient animate-fade-up text-center text-3xl font-extrabold sm:text-4xl">{t("pricing.title")}</h1>
      <p className="mx-auto mt-3 max-w-xl text-center text-slate-600">{t("pricing.subtitle")}</p>
      <PricingPlans plans={plans} />
    </div>
  );
}
