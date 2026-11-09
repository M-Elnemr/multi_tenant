"use client";

import { useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import { PageHeader, Tabs } from "@/components/ui";
import { DomainAdminOrders } from "@/components/admin/domain-admin-orders";
import { DomainAdminPricing } from "@/components/admin/domain-admin-pricing";
import { DomainAdminFailed } from "@/components/admin/domain-admin-failed";
import { DomainAdminErrors } from "@/components/admin/domain-admin-errors";
import { DomainAdminExpiring } from "@/components/admin/domain-admin-expiring";

type Tab = "orders" | "pricing" | "failed" | "errors" | "expiring";

/**
 * Cross-tenant console for the domain-purchase feature: every tenant's purchase/registration orders, the per-TLD
 * pricing rules (no deploy needed to change them), failed orders with a retry action, the provider error log for
 * troubleshooting, and an expiring-soon monitor. Mirrors the pay-requests/wallet admin screens: explicit
 * tenantId/orderId per row, never the caller's own tenant context.
 */
export default function AdminDomains() {
  const { t } = useI18n();
  const [tab, setTab] = useState<Tab>("orders");
  return (
    <>
      <PageHeader title={t("admin.domains")} />
      <div className="mb-4">
        <Tabs value={tab} onChange={setTab} tabs={[
          { key: "orders", label: t("domainBuy.tabOrders") },
          { key: "pricing", label: t("domainBuy.tabPricing") },
          { key: "failed", label: t("domainBuy.tabFailed") },
          { key: "errors", label: t("domainBuy.tabErrors") },
          { key: "expiring", label: t("domainBuy.tabExpiring") },
        ]} />
      </div>
      {tab === "orders" && <DomainAdminOrders />}
      {tab === "pricing" && <DomainAdminPricing />}
      {tab === "failed" && <DomainAdminFailed />}
      {tab === "errors" && <DomainAdminErrors />}
      {tab === "expiring" && <DomainAdminExpiring />}
    </>
  );
}
