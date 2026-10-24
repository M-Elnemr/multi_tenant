"use client";

import CartsView from "@/components/dashboard/carts-view";
import { useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { PageHeader } from "@/components/ui";

export default function CartsPage() {
  const { t } = useI18n();
  const { can } = useMe();
  return (
    <>
      <PageHeader title={t("cart.title")} subtitle={t("cart.subtitle")} />
      <CartsView canWrite={can("cart.manage")} />
    </>
  );
}
