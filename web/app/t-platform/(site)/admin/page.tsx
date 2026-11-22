"use client";

import Link from "next/link";
import { useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { planLabel } from "@/components/dashboard/pay-screen";
import { useTypeLabel } from "@/components/admin/kit";
import { Alert, AttentionRow, Button, DonutChart, KpiStrip, Loading, PageHeader, Panel, ShareBars, StatusBadge, TONE_HEX } from "@/components/ui";
import { Icon } from "@/components/icons";
import { money } from "@/lib/format";

type Overview = {
  totalTenants: number; totalUsers: number; monthlyRecurringRevenueMinor: number; trialsEndingIn7Days: number;
  newPayRequests: number; waitingForRenew: number; disabled: number; expiringIn7Days: number; newTenantsLast7Days: number;
  tenantsByTypeAndStatus: Record<string, Record<string, number>>;
  activeSubscriptionsByPlan: { plan: string; status: string; c: number }[];
};

const STATUS_COLOR: Record<string, string> = { ACTIVE: TONE_HEX.green, TRIAL: TONE_HEX.blue, PAST_DUE: TONE_HEX.amber, SUSPENDED: TONE_HEX.red, CANCELLED: TONE_HEX.slate, ARCHIVED: "#cbd5e1" };
const TYPES = ["STORE", "CLINIC", "LAWYER", "SERVICE"];

export default function AdminOverview() {
  const { t, locale } = useI18n();
  const typeLabel = useTypeLabel();
  const { me } = useMe();
  const { data, error, loading, reload } = useApi<Overview>("platform/overview");
  if (loading && !data) return <Loading />;
  if (error || !data) return (
    <div className="space-y-3">
      <Alert>{t("admin.loadFailed")}</Alert>
      <Button variant="secondary" onClick={() => void reload()}>{t("admin.retry")}</Button>
    </div>
  );

  const byStatus: Record<string, number> = {};
  Object.values(data.tenantsByTypeAndStatus).forEach((m) => Object.entries(m).forEach(([s, c]) => { byStatus[s] = (byStatus[s] ?? 0) + Number(c); }));
  const statuses = Object.keys(byStatus).sort((a, b) => byStatus[b] - byStatus[a]);
  const typeRows = TYPES.concat(Object.keys(data.tenantsByTypeAndStatus).filter((k) => !TYPES.includes(k)))
    .map((k) => ({ type: k, counts: data.tenantsByTypeAndStatus[k] ?? {}, total: Object.values(data.tenantsByTypeAndStatus[k] ?? {}).reduce((a, c) => a + Number(c), 0) }))
    .filter((r) => r.total > 0);
  const plans = data.activeSubscriptionsByPlan.map((p) => ({ label: <span className="flex items-center gap-2">{planLabel(t, p.plan)}<StatusBadge status={p.status} /></span>, value: Number(p.c) }));

  const attention = [
    { href: "/admin/pay-requests", count: data.newPayRequests, label: t("admin.newRequests"), tone: "amber" as const, icon: "receipt" as const },
    { href: "/admin/tenants?filter=waiting", count: data.waitingForRenew, label: t("admin.waiting"), tone: "red" as const, icon: "alert" as const },
    { href: "/admin/tenants?filter=expiring", count: data.expiringIn7Days, label: t("admin.expiring7"), tone: "blue" as const, icon: "clock" as const },
    { href: "/admin/tenants?filter=disabled", count: data.disabled, label: t("admin.disabledCount"), tone: "red" as const, icon: "shield" as const },
  ].filter((a) => a.count > 0);

  return (
    <div className="space-y-4">
      <PageHeader eyebrow={t("admin.nav.operate")} title={t("admin.overview")}
        subtitle={`${t("admin.welcome")}${me ? `${locale === "ar" ? "،" : ","} ${me.firstName}` : ""}`}
        actions={<><Button variant="secondary" size="sm" onClick={() => void reload()}><Icon name="return" className="h-4 w-4" />{t("admin.refresh")}</Button><Link href="/admin/tenants"><Button size="sm">{t("admin.tenants")}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Button></Link></>} />

      <KpiStrip items={[
        { label: t("admin.tenants"), value: data.totalTenants, icon: "home", tone: "brand", hint: t("admin.newLast7", { n: data.newTenantsLast7Days }), href: "/admin/tenants" },
        { label: t("admin.mrr"), value: money(data.monthlyRecurringRevenueMinor, "EGP", locale), icon: "billing", tone: "green" },
        { label: t("admin.stat.users"), value: data.totalUsers, icon: "users", tone: "slate" },
        { label: t("admin.trialsEnding"), value: data.trialsEndingIn7Days, icon: "clock", tone: "amber", href: "/admin/tenants?filter=expiring" },
      ]} />

      <div className="grid gap-4 lg:grid-cols-5">
        <Panel className="lg:col-span-2" title={t("admin.attention")} icon="alert" subtitle={attention.length ? t("admin.attnCount", { n: attention.length }) : undefined}>
          {attention.length === 0
            ? <div className="flex items-center gap-3 p-5 text-sm font-medium text-emerald-700"><span className="grid h-9 w-9 place-items-center rounded-full bg-emerald-50"><Icon name="check" className="h-[18px] w-[18px]" /></span>{t("admin.allGood")}</div>
            : <div className="p-3"><AttentionRow items={attention} /></div>}
        </Panel>

        <Panel className="lg:col-span-3" title={t("admin.statusMix")} icon="chart">
          <div className="flex flex-wrap items-center gap-x-8 gap-y-4 p-5">
            <DonutChart size={132} center={<span className="tnum font-display text-xl font-semibold">{data.totalTenants}</span>}
              segments={statuses.map((s) => ({ label: t(`status.${s}`), value: byStatus[s], color: STATUS_COLOR[s] ?? TONE_HEX.slate }))} />
            <ul className="grid min-w-[12rem] flex-1 grid-cols-2 gap-x-6 gap-y-2.5 text-[13px]">
              {statuses.map((s) => (
                <li key={s} className="flex items-center justify-between gap-2">
                  <span className="flex min-w-0 items-center gap-2"><span className="h-2.5 w-2.5 shrink-0 rounded-full" style={{ background: STATUS_COLOR[s] ?? TONE_HEX.slate }} /><span className="truncate text-muted">{t(`status.${s}`)}</span></span>
                  <span className="tnum font-semibold text-ink">{byStatus[s]}</span>
                </li>
              ))}
            </ul>
          </div>
        </Panel>
      </div>

      <div className="grid gap-4 lg:grid-cols-5">
        <Panel className="lg:col-span-3" title={t("admin.typeByStatus")} icon="grid">
          <div className="overflow-x-auto">
            <table className="w-full text-start text-[13px]">
              <thead className="text-[11.5px] text-muted"><tr className="hairline-b">
                <th className="bg-surface-2 px-4 py-2.5 text-start font-semibold first:ps-5">{t("admin.type")}</th>
                {statuses.map((s) => <th key={s} className="whitespace-nowrap bg-surface-2 px-3 py-2.5 text-end font-semibold">{t(`status.${s}`)}</th>)}
                <th className="bg-surface-2 px-4 py-2.5 text-end font-semibold last:pe-5">{t("admin.stat.total")}</th>
              </tr></thead>
              <tbody className="divide-y divide-line">
                {typeRows.map((r) => (
                  <tr key={r.type} className="transition-colors hover:bg-surface-2">
                    <td className="px-4 py-2.5 font-semibold text-ink first:ps-5"><Link href={`/admin/tenants?type=${r.type}`} className="hover:text-brand">{typeLabel(r.type)}</Link></td>
                    {statuses.map((s) => <td key={s} className={`tnum px-3 py-2.5 text-end ${r.counts[s] ? "text-ink" : "text-faint"}`}>{r.counts[s] ?? 0}</td>)}
                    <td className="tnum px-4 py-2.5 text-end font-semibold text-ink last:pe-5">{r.total}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Panel>
        <Panel className="lg:col-span-2" title={t("admin.planMix")} icon="billing">
          <div className="p-5">{plans.length === 0 ? <p className="text-sm text-muted">{t("admin.noPlans")}</p> : <ShareBars rows={plans} />}</div>
        </Panel>
      </div>
    </div>
  );
}
