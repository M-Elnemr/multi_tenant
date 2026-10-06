"use client";

import { useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Card, ErrorText, Loading, PageHeader } from "@/components/ui";
import { money } from "@/lib/format";

type Overview = {
  totalTenants: number; totalUsers: number; monthlyRecurringRevenueMinor: number; trialsEndingIn7Days: number;
  tenantsByTypeAndStatus: Record<string, Record<string, number>>;
  activeSubscriptionsByPlan: { plan: string; status: string; c: number }[];
};

export default function AdminOverview() {
  const { t, locale } = useI18n();
  const { data, error, loading } = useApi<Overview>("platform/overview");
  if (loading) return <Loading />;
  if (error || !data) return <ErrorText error={error} />;
  const stats = [
    [t("admin.totalTenants"), data.totalTenants], [t("admin.totalUsers"), data.totalUsers],
    [t("admin.mrr"), money(data.monthlyRecurringRevenueMinor, "EGP", locale)], [t("admin.trialsEnding"), data.trialsEndingIn7Days],
  ] as const;
  return (
    <>
      <PageHeader title={t("admin.overview")} />
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {stats.map(([label, value]) => (
          <Card key={label}><p className="text-sm text-slate-500">{label}</p><p className="mt-1 text-2xl font-semibold">{value}</p></Card>
        ))}
      </div>
      <div className="mt-6 grid gap-4 md:grid-cols-2">
        <Card>
          <h2 className="mb-3 font-medium">{t("admin.byType")}</h2>
          {Object.entries(data.tenantsByTypeAndStatus).map(([type, statuses]) => (
            <p key={type} className="py-1 text-sm"><b>{type}</b>: {Object.entries(statuses).map(([s, c]) => `${s} ${c}`).join(" · ")}</p>
          ))}
        </Card>
        <Card>
          <h2 className="mb-3 font-medium">{t("admin.byPlan")}</h2>
          {data.activeSubscriptionsByPlan.map((p) => <p key={p.plan + p.status} className="py-1 text-sm">{p.plan} · {p.status}: <b>{p.c}</b></p>)}
        </Card>
      </div>
    </>
  );
}
