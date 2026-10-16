"use client";

import Link from "next/link";
import { useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Alert, Badge, Button, Card, ErrorText, Loading, PageHeader, StatusBadge, Table, Td } from "../ui";
import { dateOnly, money } from "@/lib/format";
import { planLabel } from "./pay-screen";
import { PlanBenefits } from "../plan-benefits";
import { type Cycle, CycleToggle } from "../cycle-toggle";

type Sub = { status: string; planCode: string; planName: string; priceMinor: number; currency: string; currentPeriodEnd: string; cancelAtPeriodEnd: boolean };
type Plan = { code: string; name: string; priceMinor: number; currency: string; interval?: string; features: Record<string, boolean | number> };
type Invoice = { id: string; invoiceNumber: string; status: string; totalMinor: number; currency: string; issuedAt: string; paidAt?: string };

export default function BillingPage() {
  const { t, locale } = useI18n();
  const sub = useApi<Sub>("billing/subscription");
  const plans = useApi<Plan[]>("billing/plans");
  const invoices = useApi<Invoice[]>("billing/invoices");
  const [cycle, setCycle] = useState<Cycle>("month");
  const cancel = useAction(async () => { await api("billing/subscription/cancel", { body: {} }); await sub.reload(); });

  if (sub.loading && !sub.data) return <Loading />;
  const s = sub.data;
  return (
    <>
      <PageHeader title={t("billing.title")} />
      <ErrorText error={sub.error ?? cancel.error} />
      {s && (
        <Card className="mb-6">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <p className="text-sm text-slate-500">{t("billing.currentPlan")}</p>
              <p className="text-xl font-semibold">{planLabel(t, s.planCode)} <StatusBadge status={s.status} /></p>
              <p className="mt-1 text-sm text-slate-500">{s.status === "TRIALING" ? t("billing.trialEnds", { date: dateOnly(s.currentPeriodEnd, locale) }) : t("billing.renews", { date: dateOnly(s.currentPeriodEnd, locale) })}</p>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <Link href="/dashboard/billing/pay" className="rounded-xl bg-brand-gradient px-4 py-2 text-sm font-semibold text-white shadow-brand transition hover:brightness-110 active:scale-[.97]">{t("sub.action")}</Link>
              {!s.cancelAtPeriodEnd ? <Button variant="ghost" loading={cancel.loading} onClick={() => cancel.run()}>{t("billing.cancel")}</Button> : <Badge tone="amber">{t("billing.cancelScheduled")}</Badge>}
            </div>
          </div>
          {s.status === "PAST_DUE" && <div className="mt-4"><Alert tone="amber">{t("billing.pastDue")}</Alert></div>}
        </Card>
      )}
      <div className="mb-3 flex flex-wrap items-center justify-between gap-3"><h2 className="font-medium">{t("billing.plans")}</h2><CycleToggle value={cycle} onChange={setCycle} /></div>
      <div className="mb-8 grid gap-4 md:grid-cols-2">
        {plans.data?.filter((p) => p.priceMinor > 0 && (p.interval === "YEAR") === (cycle === "year")).map((p) => (
          <Card key={p.code}>
            <p className="font-semibold">{planLabel(t, p.code)}</p>
            <p className="my-2 text-2xl font-bold">{money(p.priceMinor, p.currency, locale)}<span className="text-sm font-normal text-slate-500"> / {p.interval === "YEAR" ? t("billing.perYear") : t("pricing.month")}</span></p>
            {p.interval === "YEAR" && <p className="-mt-1 mb-2 text-sm font-semibold text-emerald-700">{t("billing.twoFree")} · {t("billing.orMonthly", { amount: money(Math.round(p.priceMinor / 12), p.currency, locale) })}</p>}
            <div className="mb-4"><PlanBenefits type={p.code.startsWith("STORE") ? "STORE" : "CLINIC"} domain={p.features.custom_domain !== false && "custom_domain" in p.features} /></div>
            <Link href={`/dashboard/billing/pay?plan=${p.code}`} className={`block rounded-xl py-2.5 text-center text-sm font-semibold transition active:scale-[.97] ${s?.planCode === p.code && s.status === "ACTIVE" ? "bg-slate-100 text-slate-700" : "bg-brand-gradient text-white shadow-brand hover:brightness-110"}`}>
              {s?.planCode === p.code && s.status === "ACTIVE" ? t("billing.current") : t("billing.choose")}
            </Link>
          </Card>
        ))}
      </div>
      <h2 className="mb-3 font-medium">{t("billing.invoices")}</h2>
      <Table head={[t("billing.number"), t("admin.status"), t("billing.amount"), t("admin.created")]}>
        {invoices.data?.map((i) => (<tr key={i.id}><Td className="font-mono text-xs">{i.invoiceNumber}</Td><Td><StatusBadge status={i.status} /></Td><Td>{money(i.totalMinor, i.currency, locale)}</Td><Td>{dateOnly(i.issuedAt, locale)}</Td></tr>))}
      </Table>
    </>
  );
}
