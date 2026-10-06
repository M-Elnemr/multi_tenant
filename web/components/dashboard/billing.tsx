"use client";

import { useRouter } from "next/navigation";
import { api } from "@/lib/client";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Alert, Badge, Button, Card, ErrorText, Loading, PageHeader, StatusBadge, Table, Td } from "../ui";
import { dateOnly, money } from "@/lib/format";

type Sub = { status: string; planCode: string; planName: string; priceMinor: number; currency: string; currentPeriodEnd: string; cancelAtPeriodEnd: boolean };
type Plan = { code: string; name: string; priceMinor: number; currency: string; features: Record<string, boolean | number> };
type Invoice = { id: string; invoiceNumber: string; status: string; totalMinor: number; currency: string; issuedAt: string; paidAt?: string };
type Checkout = { invoiceId: string; status: string; checkoutUrl?: string };

export default function BillingPage() {
  const { t, locale } = useI18n();
  const router = useRouter();
  const sub = useApi<Sub>("billing/subscription");
  const plans = useApi<Plan[]>("billing/plans");
  const invoices = useApi<Invoice[]>("billing/invoices");
  const change = useAction(async (planCode: string) => {
    const r = await api<Checkout>("billing/subscription/change", { body: { planCode } });
    if (r.status === "PAID") { await Promise.all([sub.reload(), invoices.reload()]); return; }
    // A real gateway returns a hosted checkout URL to redirect to; in development we offer the mock gateway.
    router.push(`/dashboard/billing/pay?invoice=${r.invoiceId}&plan=${planCode}`);
  });
  const cancel = useAction(async () => { await api("billing/subscription/cancel", { body: {} }); await sub.reload(); });

  if (sub.loading && !sub.data) return <Loading />;
  const s = sub.data;
  return (
    <>
      <PageHeader title={t("billing.title")} />
      <ErrorText error={sub.error ?? change.error ?? cancel.error} />
      {s && (
        <Card className="mb-6">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <p className="text-sm text-slate-500">{t("billing.currentPlan")}</p>
              <p className="text-xl font-semibold">{s.planName} <StatusBadge status={s.status} /></p>
              <p className="mt-1 text-sm text-slate-500">{s.status === "TRIALING" ? t("billing.trialEnds", { date: dateOnly(s.currentPeriodEnd, locale) }) : t("billing.renews", { date: dateOnly(s.currentPeriodEnd, locale) })}</p>
            </div>
            {!s.cancelAtPeriodEnd ? <Button variant="ghost" loading={cancel.loading} onClick={() => cancel.run()}>{t("billing.cancel")}</Button> : <Badge tone="amber">{t("billing.cancelScheduled")}</Badge>}
          </div>
          {s.status === "PAST_DUE" && <div className="mt-4"><Alert tone="amber">{t("billing.pastDue")}</Alert></div>}
        </Card>
      )}
      <h2 className="mb-3 font-medium">{t("billing.plans")}</h2>
      <div className="mb-8 grid gap-4 md:grid-cols-2">
        {plans.data?.map((p) => (
          <Card key={p.code}>
            <p className="font-semibold">{p.name}</p>
            <p className="my-2 text-2xl font-bold">{money(p.priceMinor, p.currency, locale)}<span className="text-sm font-normal text-slate-500"> / {t("pricing.month")}</span></p>
            <ul className="mb-4 space-y-1 text-sm">
              {Object.entries(p.features).map(([k, v]) => <li key={k} className={v === false ? "text-slate-400 line-through" : ""}>{v === false ? "✕" : "✓"} {t(`feature.${k}`)}{typeof v === "number" ? `: ${v}` : ""}</li>)}
            </ul>
            <Button className="w-full" variant={s?.planCode === p.code && s.status === "ACTIVE" ? "secondary" : "primary"} disabled={s?.planCode === p.code && s.status === "ACTIVE"} loading={change.loading} onClick={() => change.run(p.code)}>
              {s?.planCode === p.code && s.status === "ACTIVE" ? t("billing.current") : t("billing.choose")}
            </Button>
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
