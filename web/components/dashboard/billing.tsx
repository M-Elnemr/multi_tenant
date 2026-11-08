"use client";

import Link from "next/link";
import { useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Alert, Badge, Empty, ErrorText, Loading, PageHeader, StatusBadge, Table, Td } from "../ui";
import { ConfirmDialog } from "../overlay";
import { useToast } from "../toast";
import { SettingsCard } from "./kit";
import { dateOnly, money } from "@/lib/format";
import { planLabel } from "./pay-screen";
import { PlanBenefits } from "../plan-benefits";
import { type Cycle, CycleToggle } from "../cycle-toggle";
import { PointsWallet } from "./points-wallet";

type Sub = { status: string; planCode: string; planName: string; priceMinor: number; currency: string; currentPeriodEnd: string; cancelAtPeriodEnd: boolean };
type Plan = { code: string; name: string; priceMinor: number; currency: string; interval?: string; features: Record<string, boolean | number> };
type Invoice = { id: string; invoiceNumber: string; status: string; totalMinor: number; currency: string; issuedAt: string; paidAt?: string };

export default function BillingPage() {
  const { t, locale } = useI18n();
  const sub = useApi<Sub>("billing/subscription");
  const plans = useApi<Plan[]>("billing/plans");
  const invoices = useApi<Invoice[]>("billing/invoices");
  const [cycle, setCycle] = useState<Cycle>("month");
  const toast = useToast();
  const [confirmCancel, setConfirmCancel] = useState(false);
  const cancel = useAction(async () => { await api("billing/subscription/cancel", { body: {} }); await sub.reload(); setConfirmCancel(false); toast(t("billing.cancelScheduled")); });

  if (sub.loading && !sub.data) return <Loading />;
  const s = sub.data;
  // shops have no subscription: free forever, they pay AI conversations and WhatsApp messages with points
  if (s?.planCode.startsWith("STORE")) return (
    <>
      <PageHeader eyebrow={t("nav.settings")} title={t("billing.title")} subtitle={t("pts.freeShopHint")} />
      <div className="max-w-3xl space-y-5">
        <SettingsCard tone="ai" icon="sparkles" title={t("pts.freeShop")} description={t("billing.currentPlan")}>
          <p className="text-[13px] leading-relaxed text-muted">{t("pts.freeShopHint")}</p>
        </SettingsCard>
        <PointsWallet write />
      </div>
    </>
  );
  return (
    <>
      <PageHeader eyebrow={t("nav.settings")} title={t("billing.title")} />
      <ErrorText error={sub.error ?? cancel.error} />
      <div className="space-y-6">
        {s && (
          <section className="relative overflow-hidden rounded-[18px] bg-ink p-5 text-surface sm:p-6">
            <div className="flex flex-wrap items-center justify-between gap-4">
              <div>
                <p className="text-[12.5px] opacity-65">{t("billing.currentPlan")}</p>
                <p className="mt-1.5 flex flex-wrap items-center gap-3 font-display text-[1.5rem] font-semibold leading-tight">{planLabel(t, s.planCode)} <StatusBadge status={s.status} /></p>
                <p className="mt-1.5 text-[13px] opacity-65">{s.status === "TRIALING" ? t("billing.trialEnds", { date: dateOnly(s.currentPeriodEnd, locale) }) : t("billing.renews", { date: dateOnly(s.currentPeriodEnd, locale) })}</p>
              </div>
              <div className="flex flex-wrap items-center gap-2">
                <Link href="/dashboard/billing/pay" className="inline-flex h-10 items-center rounded-[10px] bg-surface px-4 text-sm font-semibold text-ink transition active:scale-[.97]">{t("sub.action")}</Link>
                {!s.cancelAtPeriodEnd ? <button type="button" onClick={() => setConfirmCancel(true)} className="h-10 rounded-[10px] px-3 text-sm font-semibold opacity-75 transition hover:opacity-100">{t("billing.cancel")}</button> : <Badge tone="amber">{t("billing.cancelScheduled")}</Badge>}
              </div>
            </div>
            {s.status === "PAST_DUE" && <div className="mt-4"><Alert tone="amber">{t("billing.pastDue")}</Alert></div>}
          </section>
        )}
        <section>
          <div className="mb-3 flex flex-wrap items-center justify-between gap-3"><h2 className="font-display text-[15px] font-semibold text-ink">{t("billing.plans")}</h2><CycleToggle value={cycle} onChange={setCycle} /></div>
          <div className="grid gap-4 md:grid-cols-2">
            {plans.data?.filter((p) => p.priceMinor > 0 && (p.interval === "YEAR") === (cycle === "year")).map((p) => {
              const current = s?.planCode === p.code && s.status === "ACTIVE";
              return (
                <div key={p.code} className={`flex flex-col rounded-[18px] bg-surface p-5 ring-1 ${current ? "ring-2 ring-brand" : "ring-line"}`}>
                  <p className="text-[13px] font-semibold text-muted">{planLabel(t, p.code)}</p>
                  <p className="my-2 font-display text-[1.9rem] font-semibold leading-none text-ink tnum">{money(p.priceMinor, p.currency, locale)}<span className="ms-1.5 text-sm font-normal text-muted"> / {p.interval === "YEAR" ? t("billing.perYear") : t("pricing.month")}</span></p>
                  {p.interval === "YEAR" && <p className="mb-2 text-sm font-semibold text-emerald-700">{t("billing.twoFree")} · {t("billing.orMonthly", { amount: money(Math.round(p.priceMinor / 12), p.currency, locale) })}</p>}
                  <div className="mb-5 flex-1"><PlanBenefits type={p.code.startsWith("STORE") ? "STORE" : "CLINIC"} domain={p.features.custom_domain !== false && "custom_domain" in p.features} /></div>
                  <Link href={`/dashboard/billing/pay?plan=${p.code}`} className={`block rounded-[11px] py-2.5 text-center text-sm font-semibold transition active:scale-[.97] ${current ? "bg-surface-2 text-ink-2 ring-1 ring-line" : "bg-brand text-white hover:bg-brand-dark"}`}>
                    {current ? t("billing.current") : t("billing.choose")}
                  </Link>
                </div>
              );
            })}
          </div>
        </section>
        <section>
          <h2 className="mb-3 font-display text-[15px] font-semibold text-ink">{t("billing.invoices")}</h2>
          {invoices.data && invoices.data.length === 0 ? <Empty compact icon="receipt">{t("billing.invoices")}</Empty> : (
            <Table head={[t("billing.number"), t("admin.status"), t("billing.amount"), t("admin.created")]}>
              {invoices.data?.map((i) => (<tr key={i.id}><Td className="font-mono text-xs">{i.invoiceNumber}</Td><Td><StatusBadge status={i.status} /></Td><Td className="tnum">{money(i.totalMinor, i.currency, locale)}</Td><Td>{dateOnly(i.issuedAt, locale)}</Td></tr>))}
            </Table>
          )}
        </section>
      </div>
      <ConfirmDialog open={confirmCancel} onClose={() => setConfirmCancel(false)} onConfirm={() => void cancel.run()} loading={cancel.loading} danger title={t("billing.cancel")} confirmLabel={t("billing.cancel")} cancelLabel={t("common.cancel")} />
    </>
  );
}
