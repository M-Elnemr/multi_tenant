"use client";

import { useRouter } from "next/navigation";
import { use, useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { planLabel } from "@/components/dashboard/pay-screen";
import { Alert, Button, Card, ErrorText, Field, Input, Loading, Modal, PageHeader, Select, StatusBadge } from "@/components/ui";
import { dateOnly, dateTime, money } from "@/lib/format";
import { whatsappLink } from "@/lib/whatsapp";

type Detail = {
  id: string; slug: string; name: string; tenantType: string; status: string; createdAt: string; staffCount: number;
  domains: { host: string; kind: string; primary: boolean; verified: boolean; sslStatus: string }[];
  subscription: { status?: string; planCode?: string; planName?: string; priceMinor?: number; currentPeriodStart?: string; currentPeriodEnd?: string; createdAt?: string };
  owner: { firstName?: string; lastName?: string; phone?: string; email?: string };
  billingState: { state: string; daysLeft?: number };
  payRequests: { id: string; planCode: string; amountMinor: number; currency: string; phone: string; status: string; createdAt: string }[];
  events: { eventType: string; createdAt: string }[];
  usage: { metric: string; period: string; value: number }[];
};

export default function TenantDetail({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { t, locale } = useI18n();
  const router = useRouter();
  const { can } = useMe();
  const { data, error, loading, reload } = useApi<Detail>(`platform/tenants/${id}`);
  const [reason, setReason] = useState("");
  const [planCode, setPlanCode] = useState("");
  const [days, setDays] = useState("30");
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [typed, setTyped] = useState("");
  const setStatus = useAction(async (status: string) => { await api(`platform/tenants/${id}/status`, { body: { status, reason } }); await reload(); });
  const changePlan = useAction(async () => { await api(`platform/tenants/${id}/plan`, { body: { planCode, days: Number(days) || 30 } }); await reload(); });
  const purge = useAction(async () => { await api(`platform/tenants/${id}`, { method: "DELETE", body: { confirmSlug: typed } }); router.replace("/admin/tenants"); });
  if (loading && !data) return <Loading />;
  if (!data) return <ErrorText error={error} />;
  const store = data.tenantType === "STORE";
  const plans = store ? ["STORE_STANDARD", "STORE_PRO"] : ["CLINIC_PRO"];
  const chosen = planCode || data.subscription.planCode?.replace("_FREE", store ? "_STANDARD" : "_PRO") || plans[0];
  const o = data.owner;
  return (
    <>
      <PageHeader title={data.name} subtitle={`${data.tenantType} · ${data.slug}`} actions={<StatusBadge status={data.status} />} />
      <div className="grid gap-4 md:grid-cols-2">
        <Card>
          <h2 className="mb-2 font-medium">{t("admin.owner")}</h2>
          <p className="text-sm">{`${o.firstName ?? ""} ${o.lastName ?? ""}`.trim() || "-"}</p>
          {o.phone && <p className="mt-1 flex flex-wrap gap-3 text-sm" dir="ltr"><a className="font-semibold text-brand" href={`tel:${o.phone}`}>{o.phone}</a><a className="text-emerald-700 underline" target="_blank" rel="noopener noreferrer" href={whatsappLink(o.phone)}>{t("admin.whatsapp")}</a></p>}
          {o.email && <p className="mt-1 text-sm" dir="ltr"><a className="text-brand" href={`mailto:${o.email}`}>{o.email}</a></p>}
          <p className="mt-3 text-sm text-slate-500">{t("admin.signedUp")}: {dateOnly(data.createdAt, locale)} · {t("admin.staff")}: <b>{data.staffCount}</b></p>
        </Card>
        <Card>
          <h2 className="mb-2 font-medium">{t("admin.subscription")}</h2>
          <p className="text-sm">{planLabel(t, data.subscription.planCode)} · {data.subscription.status && <StatusBadge status={data.subscription.status} />}</p>
          <p className="mt-1 text-sm text-slate-500">{money(data.subscription.priceMinor, "EGP", locale)}</p>
          <p className="mt-1 text-sm text-slate-500">{t("admin.subscribedSince")}: {dateOnly(data.subscription.currentPeriodStart ?? data.subscription.createdAt, locale)}</p>
          <p className="text-sm text-slate-500">{t("admin.endsOn")}: {dateOnly(data.subscription.currentPeriodEnd, locale)}{data.billingState.daysLeft !== undefined && data.billingState.state !== "DISABLED" ? ` · ${t("admin.daysLeft", { n: data.billingState.daysLeft })}` : ""}</p>
        </Card>
        <Card>
          <h2 className="mb-2 font-medium">{t("admin.payRequests")}</h2>
          {data.payRequests.length === 0 ? <p className="text-sm text-slate-500">{t("admin.noRequests")}</p> : data.payRequests.map((r) => (
            <p key={r.id} className="flex flex-wrap items-center gap-2 py-0.5 text-sm">{planLabel(t, r.planCode)} · {money(r.amountMinor, r.currency, locale)} <StatusBadge status={r.status} /> <span className="text-xs text-slate-500">{dateTime(r.createdAt, locale)}</span></p>
          ))}
        </Card>
        <Card>
          <h2 className="mb-2 font-medium">{t("admin.domains")}</h2>
          {data.domains.map((d) => <p key={d.host} className="py-0.5 text-sm" dir="ltr">{d.host} {d.primary && "★"} {d.verified ? "✓" : "…"}</p>)}
          <h2 className="mb-1 mt-4 font-medium">{t("admin.usage")}</h2>
          {data.usage.length === 0 ? <p className="text-sm text-slate-500">-</p> : data.usage.map((u) => <p key={u.metric + u.period} className="text-sm">{u.metric} ({u.period}): <b>{u.value}</b></p>)}
        </Card>
        <Card>
          <h2 className="mb-2 font-medium">{t("admin.changePlan")}</h2>
          <div className="grid gap-2 sm:grid-cols-2">
            <Select value={chosen} onChange={(e) => setPlanCode(e.target.value)}>{plans.map((p) => <option key={p} value={p}>{planLabel(t, p)}</option>)}</Select>
            <Input type="number" min={1} max={3650} value={days} onChange={(e) => setDays(e.target.value)} placeholder={t("admin.days")} dir="ltr" />
          </div>
          <ErrorText error={changePlan.error} />
          <Button className="mt-3" loading={changePlan.loading} onClick={() => changePlan.run()}>{t("admin.apply")}</Button>
        </Card>
        <Card>
          <h2 className="mb-2 font-medium">{t("admin.controls")}</h2>
          <Alert tone="blue">{t("admin.noContentAccess")}</Alert>
          <div className="mt-3 space-y-2">
            <Input placeholder={t("admin.reason")} value={reason} onChange={(e) => setReason(e.target.value)} />
            <ErrorText error={setStatus.error} />
            <div className="flex gap-2">
              {data.status !== "SUSPENDED" && <Button variant="danger" loading={setStatus.loading} onClick={() => setStatus.run("SUSPENDED")}>{t("admin.disable")}</Button>}
              {data.status === "SUSPENDED" && <Button loading={setStatus.loading} onClick={() => setStatus.run("ACTIVE")}>{t("admin.enable")}</Button>}
            </div>
          </div>
        </Card>
        <Card>
          <h2 className="mb-2 font-medium">{t("admin.history")}</h2>
          {data.events.map((e, i) => <p key={i} className="text-sm text-slate-600"><span className="font-mono text-xs">{e.eventType}</span> · {dateTime(e.createdAt, locale)}</p>)}
        </Card>
        {can("platform.tenant.purge") && (
          <Card className="border border-red-200">
            <h2 className="mb-2 font-medium text-red-700">{t("admin.dangerZone")}</h2>
            <p className="mb-3 text-sm text-slate-600">{t("admin.dangerBody")}</p>
            <Button variant="danger" onClick={() => { setTyped(""); setConfirmOpen(true); }}>{t("admin.deleteNow")}</Button>
          </Card>
        )}
      </div>
      <Modal open={confirmOpen} onClose={() => setConfirmOpen(false)} title={t("admin.dangerZone")}>
        <div className="space-y-3">
          <Alert tone="red">{t("admin.dangerBody")}</Alert>
          <Field label={t("admin.typeSlug", { slug: data.slug })}><Input value={typed} onChange={(e) => setTyped(e.target.value)} dir="ltr" autoComplete="off" /></Field>
          <ErrorText error={purge.error} />
          <Button variant="danger" className="w-full" loading={purge.loading} disabled={typed.trim().toLowerCase() !== data.slug} onClick={() => purge.run()}>{t("admin.deleteNow")}</Button>
        </div>
      </Modal>
    </>
  );
}
