"use client";

import { use, useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Button, Card, ErrorText, Input, Loading, PageHeader, StatusBadge } from "@/components/ui";
import { dateOnly, money } from "@/lib/format";

type Detail = {
  id: string; slug: string; name: string; tenantType: string; status: string; createdAt: string; staffCount: number;
  domains: { host: string; kind: string; primary: boolean; verified: boolean; sslStatus: string }[];
  subscription: { status?: string; planCode?: string; priceMinor?: number; currentPeriodEnd?: string };
  usage: { metric: string; period: string; value: number }[];
};

export default function TenantDetail({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { t, locale } = useI18n();
  const { data, error, loading, reload } = useApi<Detail>(`platform/tenants/${id}`);
  const [reason, setReason] = useState("");
  const setStatus = useAction(async (status: string) => { await api(`platform/tenants/${id}/status`, { body: { status, reason } }); await reload(); });
  if (loading && !data) return <Loading />;
  if (!data) return <ErrorText error={error} />;
  return (
    <>
      <PageHeader title={data.name} subtitle={`${data.tenantType} · ${data.slug}`} actions={<StatusBadge status={data.status} />} />
      <div className="grid gap-4 md:grid-cols-2">
        <Card>
          <h2 className="mb-2 font-medium">{t("admin.subscription")}</h2>
          <p className="text-sm">{data.subscription.planCode ?? "-"} · {data.subscription.status && <StatusBadge status={data.subscription.status} />}</p>
          <p className="mt-1 text-sm text-slate-500">{money(data.subscription.priceMinor, "EGP", locale)} · {t("admin.periodEnd")}: {dateOnly(data.subscription.currentPeriodEnd, locale)}</p>
          <p className="mt-3 text-sm">{t("admin.staff")}: <b>{data.staffCount}</b></p>
        </Card>
        <Card>
          <h2 className="mb-2 font-medium">{t("admin.domains")}</h2>
          {data.domains.map((d) => <p key={d.host} className="py-0.5 text-sm" dir="ltr">{d.host} {d.primary && "★"} {d.verified ? "✓" : "…"}</p>)}
        </Card>
        <Card>
          <h2 className="mb-2 font-medium">{t("admin.usage")}</h2>
          {data.usage.length === 0 ? <p className="text-sm text-slate-500">-</p> : data.usage.map((u) => <p key={u.metric + u.period} className="text-sm">{u.metric} ({u.period}): <b>{u.value}</b></p>)}
        </Card>
        <Card>
          <h2 className="mb-2 font-medium">{t("admin.controls")}</h2>
          <Alert tone="blue">{t("admin.noContentAccess")}</Alert>
          <div className="mt-3 space-y-2">
            <Input placeholder={t("admin.reason")} value={reason} onChange={(e) => setReason(e.target.value)} />
            <ErrorText error={setStatus.error} />
            <div className="flex gap-2">
              {data.status !== "SUSPENDED" && <Button variant="danger" loading={setStatus.loading} onClick={() => setStatus.run("SUSPENDED")}>{t("admin.suspend")}</Button>}
              {data.status === "SUSPENDED" && <Button loading={setStatus.loading} onClick={() => setStatus.run("ACTIVE")}>{t("admin.activate")}</Button>}
            </div>
          </div>
        </Card>
      </div>
    </>
  );
}
