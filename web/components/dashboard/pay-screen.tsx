"use client";

import { useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { api } from "@/lib/client";
import { dateOnly, money } from "@/lib/format";
import { useAction, useApi, useMe } from "../hooks";
import { useI18n } from "../i18n-provider";
import { PhoneInput } from "../inputs";
import { Icon } from "../icons";
import { Alert, Button, ErrorText, Field, Loading, PageHeader, StatusBadge } from "../ui";
import type { BillingStatus } from "./subscription-bar";
import { AppBenefits } from "../app-benefits";
import { type Cycle, CycleToggle } from "../cycle-toggle";

type Plan = { code: string; name: string; priceMinor: number; currency: string; interval?: string; features: Record<string, boolean | number> };
type Quote = { upgrade: boolean; fullMinor: number; creditMinor: number; dueNowMinor: number; remainingDays?: number; periodEnd?: string; currency: string };
type Req = { id: string; planCode: string; amountMinor: number; timing?: string; creditMinor?: number; currency: string; phone: string; status: string };

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
  const [timing, setTiming] = useState<"NOW" | "NEXT_PERIOD">("NOW");
  const send = useAction(async (planCode: string, phoneNo: string) => {
    const r = await api<Req>("billing/pay-requests", { body: { planCode, phone: phoneNo, timing } });
    setSaved(r); setEditing(false); await reqs.reload();
  });

  const s = status.data;
  const paid = (plans.data ?? []).filter((p) => p.priceMinor > 0);
  const cycle: Cycle = cycleSet ?? (((picked ?? s?.planCode) ?? "").endsWith("_YEARLY") ? "year" : "month");
  const payable = paid.filter((p) => (p.interval === "YEAR") === (cycle === "year"));
  const sameLevel = (code: string | null | undefined) => payable.find((p) => p.code.replace("_YEARLY", "") === (code ?? "").replace("_YEARLY", ""))?.code;
  const choice = sameLevel(picked) ?? sameLevel(s?.planCode) ?? payable[0]?.code ?? "";
  const phoneValue = phone ?? me?.phone ?? "";
  const quote = useApi<Quote>(choice ? `billing/upgrade-quote?planCode=${choice}` : null);
  const uq = quote.data?.upgrade ? quote.data : null;
  const open = saved ?? (reqs.data ?? []).find((r) => r.status === "NEW" || r.status === "CONTACTED") ?? null;

  if ((status.loading && !status.data) || (plans.loading && !plans.data)) return <Loading />;
  return (
    <div className="mx-auto max-w-3xl">
      <PageHeader eyebrow={t("billing.title")} title={t("pay.title")} />
      {s?.state === "DISABLED" && <div className="mb-4"><Alert tone="red"><b>{t("sub.disabledTitle")}</b><br />{t("sub.disabledBody")}</Alert></div>}
      {s && s.state !== "DISABLED" && (
        <div className="mb-4 rounded-[18px] bg-ink p-5 text-surface">
          <p className="text-[12.5px] opacity-65">{t("pay.current")}</p>
          <p className="mt-1 font-display text-xl font-semibold">{planLabel(t, s.planCode)}</p>
          {s.periodEnd && <p className="mt-1 text-[13px] opacity-65">{t("admin.endsOn")}: {dateOnly(s.periodEnd, locale)}</p>}
        </div>
      )}
      <div className="mb-4"><Alert tone="blue">{t("pay.notice")}</Alert></div>
      {s?.tenantType && <div className="mb-4"><AppBenefits kind={s.tenantType as "STORE" | "CLINIC" | "LAWYER"} compact /></div>}

      {open && !editing ? (
        <div className="space-y-3 rounded-[18px] bg-surface p-6 text-center ring-1 ring-line">
          <span className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-emerald-50 text-emerald-600"><Icon name="check" className="h-6 w-6" /></span>
          <h2 className="font-display text-lg font-semibold text-ink">{t("pay.receivedTitle")}</h2>
          <p className="text-sm text-muted">{t("pay.receivedBody", { phone: open.phone })}</p>
          {open.timing === "NEXT_PERIOD" && <p className="text-sm text-muted">{t("pay.upgradeNext")}</p>}
          <p className="flex items-center justify-center gap-2 text-sm">{planLabel(t, open.planCode)} · {money(open.amountMinor, open.currency, locale)} <StatusBadge status={open.status} /></p>
          <Button variant="secondary" onClick={() => { setPicked(open.planCode); setPhone(open.phone); setEditing(true); }}>{t("pay.update")}</Button>
        </div>
      ) : (
        <form onSubmit={(e) => { e.preventDefault(); void send.run(choice, phoneValue); }} className="space-y-4">
          <h2 className="font-display text-[15px] font-semibold text-ink">{t("pay.choose")}</h2>
          <CycleToggle value={cycle} onChange={(c) => { setCycleSet(c); setPicked(null); }} />
          <div className="grid gap-3 sm:grid-cols-2">
            {payable.map((p) => {
              const on = p.code === choice;
              const domain = p.features.custom_domain !== false && "custom_domain" in p.features;
              return (
                <button type="button" key={p.code} onClick={() => setPicked(p.code)} aria-pressed={on} className={`rounded-[18px] bg-surface p-5 text-start ring-1 transition ${on ? "ring-2 ring-brand" : "ring-line hover:ring-line-strong"}`}>
                  <p className="font-semibold text-ink">{planLabel(t, p.code)}</p>
                  <p className="my-1 font-display text-2xl font-semibold text-ink tnum">{money(p.priceMinor, p.currency, locale)}<span className="text-sm font-normal text-muted"> / {p.interval === "YEAR" ? t("billing.perYear") : t("pricing.month")}</span></p>
                  {p.interval === "YEAR" && <p className="text-xs font-semibold text-emerald-700">{t("billing.twoFree")} · {t("billing.orMonthly", { amount: money(Math.round(p.priceMinor / 12), p.currency, locale) })}</p>}
                  <p className={`text-sm ${domain ? "text-emerald-700" : "text-muted"}`}><Icon name={domain ? "check" : "close"} className="me-1 inline h-3.5 w-3.5" />{domain ? t("pay.withDomain") : t("pay.noDomain")}</p>
                </button>
              );
            })}
          </div>
          {uq && (
            <div className="space-y-2">
              <h2 className="font-display text-[15px] font-semibold text-ink">{t("pay.when")}</h2>
              {(["NOW", "NEXT_PERIOD"] as const).map((v) => (
                <button type="button" key={v} onClick={() => setTiming(v)} aria-pressed={timing === v} className={`block w-full rounded-[16px] bg-surface p-4 text-start ring-1 transition ${timing === v ? "ring-2 ring-brand" : "ring-line hover:ring-line-strong"}`}>
                  <p className="font-semibold text-ink">{v === "NOW" ? t("pay.upgradeNow") : t("pay.upgradeNext")}</p>
                  <p className="text-sm text-muted">{v === "NOW"
                    ? t("pay.upgradeNowBody", { days: String(uq.remainingDays ?? 0), credit: money(uq.creditMinor, uq.currency, locale), due: money(uq.dueNowMinor, uq.currency, locale) })
                    : t("pay.upgradeNextBody", { date: dateOnly(uq.periodEnd, locale), price: money(uq.fullMinor, uq.currency, locale) })}</p>
                </button>
              ))}
            </div>
          )}
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
