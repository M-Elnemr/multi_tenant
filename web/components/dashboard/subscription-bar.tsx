"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Icon } from "../icons";

export type BillingStatus = {
  state: "OK" | "TRIAL_ENDING" | "WAIT_RENEW" | "DISABLED"; tenantStatus: string; planCode?: string; planName?: string; tenantType?: string;
  daysLeft?: number; periodEnd?: string; graceEnd?: string; trial?: boolean;
};

/** "Free plan ends in N days" / "Subscription ended, renew" bar. Shown on every dashboard page to the people who can pay; tapping it opens the pay screen. */
export function SubscriptionBar({ status }: { status: BillingStatus | null }) {
  const { t } = useI18n();
  const path = usePathname();
  if (!status || (status.state !== "TRIAL_ENDING" && status.state !== "WAIT_RENEW")) return null;
  if (path.startsWith("/dashboard/billing/pay")) return null;
  const n = status.daysLeft ?? 0;
  const renew = status.state === "WAIT_RENEW";
  const text = renew ? (n > 0 ? t("sub.renew", { n }) : t("sub.renewToday")) : n > 0 ? t("sub.trialEnding", { n }) : t("sub.trialEndsToday");
  return (
    <Link href="/dashboard/billing/pay" className={`flex items-center justify-center gap-3 px-4 py-2.5 text-center text-sm font-semibold text-white transition hover:brightness-110 print:hidden ${renew ? "bg-red-600" : "bg-amber-600"}`} role="status">
      <Icon name="bell" className="h-4 w-4 shrink-0" />
      <span>{text}</span>
      <span className="shrink-0 rounded-lg bg-white/20 px-2.5 py-1 text-xs font-bold">{t("sub.action")}</span>
    </Link>
  );
}

/** Status of the subscription for the signed-in member (works while the business is disabled). */
export function useBillingStatus(enabled: boolean) {
  return useApi<BillingStatus>(enabled ? "billing/status" : null);
}
