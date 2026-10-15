"use client";

import { useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { api } from "@/lib/client";
import { dateOnly, money } from "@/lib/format";
import { useAction, useApi, useMe } from "../hooks";
import { useI18n } from "../i18n-provider";
import { PhoneInput } from "../inputs";
import { Icon } from "../icons";
import { Alert, Button, Card, ErrorText, Field, Loading, PageHeader, StatusBadge } from "../ui";
import type { BillingStatus } from "./subscription-bar";
import { AppBenefits } from "../app-benefits";
import { type Cycle, CycleToggle } from "../cycle-toggle";

type Plan = { code: string; name: string; priceMinor: number; currency: string; interval?: string; features: Record<string, boolean | number> };
type Req = { id: string; planCode: string; amountMinor: number; currency: string; phone: string; status: string };

export const planLabel = (t: (k: string) => string, code?: string) => {
  const yearly = !!code?.endsWith("_YEARLY");
  const kind = code?.replace("_YEARLY", "").split("_").pop();
  const key = kind ? `plan.${kind}` : "";
  const label = key ? t(key) : "";
  const base = label && label !== key ? label : (code ?? "-");
  return yearly ? `${base} (${t("plan.YEARLY")})` : base;
};

function Inner() {
  const { t, locale } = useI18n();
  const q = useSearchParams();
  const { me } = useMe();
  const status = useApi<BillingStatus>("billing/status");
  const plans = useApi<Plan[]>("billing/plans");
  const reqs = useApi<Req[]>("billing/pay-requests");
  const [picked, setPicked] = useState<string | null>(q.get("plan"));
  const [cycleSet, setCycleSet] = useState<Cycle | null>(null);
  const [phone, setPhone] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);
  const [saved, setSaved] = useState<Req | null>(null);
  const send = useAction(async (planCode: string, phoneNo: string) => {
    const r = await api<Req>("billing/pay-requests", { body: { planCode, phone: phoneNo } });
    setSaved(r); setEditing(false); await reqs.reload();
  });

  const s = status.data;
  const paid = (plans.data ?? []).filter((p) => p.priceMinor > 0);
  const cycle: Cycle = cycleSet ?? (((picked ?? s?.planCode) ?? "").endsWith("_YEARLY") ? "year" : "month");
  const payable = paid.filter((p) => (p.interval === "YEAR") === (cycle === "year"));
  const sameLevel = (code: string | null | undefined) => payable.find((p) => p.code.replace("_YEARLY", "") === (code ?? "").replace("_YEARLY", ""))?.code;
  const choice = sameLevel(picked) ?? sameLevel(s?.planCode) ?? payable[0]?.code ?? "";
  const phoneValue = phone ?? me?.phone ?? "";
  const open = saved ?? (reqs.data ?? []).find((r) => r.status === "NEW" || r.status === "CONTACTED") ?? null;

  if ((status.loading && !status.data) || (plans.loading && !plans.data)) return <Loading />;
  return (
    <div className="mx-auto max-w-3xl">
      <PageHeader title={t("pay.title")} />
      {s?.state === "DISABLED" && <div className="mb-4"><Alert tone="red"><b>{t("sub.disabledTitle")}</b><br />{t("sub.disabledBody")}</Alert></div>}
      {s && s.state !== "DISABLED" && (
        <Card className="mb-4">
          <p className="text-sm text-slate-500">{t("pay.current")}</p>
          <p className="text-lg font-semibold">{planLabel(t, s.planCode)}</p>
          {s.periodEnd && <p className="mt-1 text-sm text-slate-500">{t("admin.endsOn")}: {dateOnly(s.periodEnd, locale)}</p>}
        </Card>
      )}
      <div className="mb-4"><Alert tone="blue">{t("pay.notice")}</Alert></div>
      {s?.tenantType && <div className="mb-4"><AppBenefits kind={s.tenantType as "STORE" | "CLINIC"} compact /></div>}

      {open && !editing ? (
        <Card className="space-y-3 text-center">
          <span className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-emerald-50 text-emerald-600"><Icon name="check" className="h-6 w-6" /></span>
          <h2 className="text-lg font-bold">{t("pay.receivedTitle")}</h2>
          <p className="text-sm text-slate-600">{t("pay.receivedBody", { phone: open.phone })}</p>
          <p className="flex items-center justify-center gap-2 text-sm">{planLabel(t, open.planCode)} · {money(open.amountMinor, open.currency, locale)} <StatusBadge status={open.status} /></p>
          <Button variant="secondary" onClick={() => { setPicked(open.planCode); setPhone(open.phone); setEditing(true); }}>{t("pay.update")}</Button>
        </Card>
      ) : (
        <form onSubmit={(e) => { e.preventDefault(); void send.run(choice, phoneValue); }} className="space-y-4">
          <h2 className="font-semibold">{t("pay.choose")}</h2>
          <CycleToggle value={cycle} onChange={(c) => { setCycleSet(c); setPicked(null); }} />
          <div className="grid gap-3 sm:grid-cols-2">
            {payable.map((p) => {
              const on = p.code === choice;
              const domain = p.features.custom_domain !== false && "custom_domain" in p.features;
              return (
                <button type="button" key={p.code} onClick={() => setPicked(p.code)} aria-pressed={on} className={`rounded-2xl border-2 bg-white p-5 text-start transition ${on ? "border-brand shadow-brand" : "border-slate-200 hover:border-brand/50"}`}>
                  <p className="font-bold">{planLabel(t, p.code)}</p>
                  <p className="my-1 text-2xl font-extrabold text-brand">{money(p.priceMinor, p.currency, locale)}<span className="text-sm font-normal text-slate-500"> / {p.interval === "YEAR" ? t("billing.perYear") : t("pricing.month")}</span></p>
                  {p.interval === "YEAR" && <p className="text-xs font-semibold text-emerald-700">{t("billing.twoFree")} · {t("billing.orMonthly", { amount: money(Math.round(p.priceMinor / 12), p.currency, locale) })}</p>}
                  <p className={`text-sm ${domain ? "text-emerald-700" : "text-slate-500"}`}>{domain ? "✓ " + t("pay.withDomain") : "✕ " + t("pay.noDomain")}</p>
                </button>
              );
            })}
          </div>
          <Field label={t("pay.phone")} hint={t("pay.phoneHint")}><PhoneInput value={phoneValue} onValue={setPhone} required placeholder="01012345678" /></Field>
          <ErrorText error={send.error} />
          <Button type="submit" className="w-full" loading={send.loading} disabled={!choice || phoneValue.trim().length < 8}>{t("pay.proceed")}</Button>
        </form>
      )}
    </div>
  );
}

/** Manual payment for now: the owner presses "Proceed to pay", customer service calls them (online payment is not live yet). */
export default function PayScreen() {
  return <Suspense><Inner /></Suspense>;
}
