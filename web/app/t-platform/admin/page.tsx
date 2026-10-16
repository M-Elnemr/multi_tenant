"use client";

import Link from "next/link";
import { useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { planLabel } from "@/components/dashboard/pay-screen";
import { Icon, type IconName } from "@/components/icons";
import { Card, ErrorText, Loading, Stat } from "@/components/ui";
import { money } from "@/lib/format";

type Overview = {
  totalTenants: number; totalUsers: number; monthlyRecurringRevenueMinor: number; trialsEndingIn7Days: number;
  newPayRequests: number; waitingForRenew: number; disabled: number; expiringIn7Days: number; newTenantsLast7Days: number;
  tenantsByTypeAndStatus: Record<string, Record<string, number>>;
  activeSubscriptionsByPlan: { plan: string; status: string; c: number }[];
};

const STATUS_BAR: Record<string, string> = { ACTIVE: "bg-emerald-500", TRIAL: "bg-sky-500", PAST_DUE: "bg-amber-500", SUSPENDED: "bg-red-500", CANCELLED: "bg-slate-400", ARCHIVED: "bg-slate-300" };

function Bars({ rows, total }: { rows: { key: string; label: string; value: number; color: string }[]; total: number }) {
  return (
    <div className="space-y-3">
      {rows.map((r) => (
        <div key={r.key}>
          <div className="mb-1 flex justify-between text-sm"><span className="font-medium text-slate-700">{r.label}</span><span className="font-bold text-ink">{r.value}</span></div>
          <div className="h-2.5 overflow-hidden rounded-full bg-slate-100"><div className={`h-full rounded-full ${r.color} transition-all duration-700`} style={{ width: `${total ? Math.max(4, (r.value / total) * 100) : 0}%` }} /></div>
        </div>
      ))}
    </div>
  );
}

export default function AdminOverview() {
  const { t, locale } = useI18n();
  const { me } = useMe();
  const { data, error, loading } = useApi<Overview>("platform/overview");
  if (loading && !data) return <Loading />;
  if (error || !data) return <ErrorText error={error} />;

  const attention: { href: string; icon: IconName; text: string; tone: string }[] = [];
  if (data.newPayRequests > 0) attention.push({ href: "/admin/pay-requests", icon: "wallet", text: t("admin.attn.requests", { n: data.newPayRequests }), tone: "bg-amber-50 text-amber-700" });
  if (data.expiringIn7Days > 0) attention.push({ href: "/admin/tenants?filter=expiring", icon: "clock", text: t("admin.attn.expiring", { n: data.expiringIn7Days }), tone: "bg-sky-50 text-sky-700" });
  if (data.waitingForRenew > 0) attention.push({ href: "/admin/tenants?filter=waiting", icon: "alert", text: t("admin.attn.waiting", { n: data.waitingForRenew }), tone: "bg-red-50 text-red-700" });
  if (data.disabled > 0) attention.push({ href: "/admin/tenants?filter=disabled", icon: "shield", text: t("admin.attn.disabled", { n: data.disabled }), tone: "bg-slate-100 text-slate-700" });

  const byStatus: Record<string, number> = {};
  Object.values(data.tenantsByTypeAndStatus).forEach((m) => Object.entries(m).forEach(([s, c]) => { byStatus[s] = (byStatus[s] ?? 0) + Number(c); }));
  const planRows = data.activeSubscriptionsByPlan.map((p) => ({ key: p.plan + p.status, label: `${planLabel(t, p.plan)} · ${t(`status.${p.status}`)}`, value: Number(p.c), color: p.status === "ACTIVE" ? "bg-emerald-500" : p.status === "TRIALING" ? "bg-sky-500" : "bg-amber-500" }));
  const planTotal = planRows.reduce((a, r) => a + r.value, 0);
  const shops = Object.values(data.tenantsByTypeAndStatus.STORE ?? {}).reduce((a, c) => a + Number(c), 0);
  const clinics = Object.values(data.tenantsByTypeAndStatus.CLINIC ?? {}).reduce((a, c) => a + Number(c), 0);

  return (
    <div className="space-y-6">
      <div className="bg-brand-gradient relative overflow-hidden rounded-3xl p-6 text-white shadow-brand sm:p-8">
        <div className="pointer-events-none absolute -end-10 -top-10 h-48 w-48 rounded-full bg-white/10 blur-2xl" />
        <p className="text-sm font-semibold opacity-90">{t("admin.welcome")}{me ? `${locale === "ar" ? "،" : ","} ${me.firstName}` : ""}</p>
        <h1 className="mt-1 text-2xl font-extrabold sm:text-3xl">{t("admin.console")}</h1>
        <div className="mt-5 flex flex-wrap gap-6 text-sm">
          <div><p className="text-2xl font-extrabold">{data.totalTenants}</p><p className="opacity-85">{t("admin.stat.total")}</p></div>
          <div><p className="text-2xl font-extrabold">{shops}</p><p className="opacity-85">{t("admin.shops")}</p></div>
          <div><p className="text-2xl font-extrabold">{clinics}</p><p className="opacity-85">{t("admin.clinics")}</p></div>
          <div><p className="text-2xl font-extrabold">{money(data.monthlyRecurringRevenueMinor, "EGP", locale)}</p><p className="opacity-85">{t("admin.mrr")}</p></div>
        </div>
      </div>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Link href="/admin/pay-requests"><Stat label={t("admin.newRequests")} value={data.newPayRequests} icon="wallet" tone="amber" /></Link>
        <Link href="/admin/tenants?filter=expiring"><Stat label={t("admin.expiring7")} value={data.expiringIn7Days} icon="clock" tone="brand" /></Link>
        <Link href="/admin/tenants?filter=waiting"><Stat label={t("admin.waiting")} value={data.waitingForRenew} icon="alert" tone="red" /></Link>
        <Link href="/admin/tenants?filter=disabled"><Stat label={t("admin.disabledCount")} value={data.disabled} icon="shield" tone="red" /></Link>
        <Stat label={t("admin.stat.new")} value={data.newTenantsLast7Days} icon="sparkles" tone="green" />
        <Stat label={t("admin.stat.users")} value={data.totalUsers} icon="users" tone="brand" />
        <Stat label={t("admin.trialsEnding")} value={data.trialsEndingIn7Days} icon="trending" tone="amber" />
        <Link href="/admin/tenants"><Stat label={t("admin.tenants")} value={data.totalTenants} icon="home" tone="green" /></Link>
      </div>

      <div className="grid gap-6 lg:grid-cols-3">
        <Card className="lg:col-span-1">
          <h2 className="mb-4 flex items-center gap-2 font-bold"><Icon name="alert" className="h-5 w-5 text-amber-600" />{t("admin.attention")}</h2>
          {attention.length === 0 ? <p className="rounded-xl bg-emerald-50 p-4 text-sm font-medium text-emerald-700">{t("admin.allGood")}</p> : (
            <ul className="space-y-2">
              {attention.map((a) => (
                <li key={a.href}><Link href={a.href} className={`flex items-center gap-3 rounded-xl p-3 text-sm font-semibold transition hover:brightness-95 ${a.tone}`}><Icon name={a.icon} className="h-5 w-5 shrink-0" /><span className="flex-1">{a.text}</span><Icon name="arrow" className="h-4 w-4 flip-rtl" /></Link></li>
              ))}
            </ul>
          )}
        </Card>
        <Card>
          <h2 className="mb-4 font-bold">{t("admin.statusMix")}</h2>
          <Bars total={data.totalTenants} rows={Object.entries(byStatus).map(([s, c]) => ({ key: s, label: t(`status.${s}`), value: c, color: STATUS_BAR[s] ?? "bg-slate-400" }))} />
        </Card>
        <Card>
          <h2 className="mb-4 font-bold">{t("admin.planMix")}</h2>
          {planRows.length === 0 ? <p className="text-sm text-slate-500">-</p> : <Bars total={planTotal} rows={planRows} />}
        </Card>
      </div>
    </div>
  );
}
