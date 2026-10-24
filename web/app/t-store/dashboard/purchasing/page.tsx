"use client";

import Purchasing from "@/components/dashboard/purchasing";
import { useI18n } from "@/components/i18n-provider";
import { PageHeader } from "@/components/ui";

export default function PurchasingPage() {
  const { t } = useI18n();
  return (<><PageHeader title={t("po.title")} subtitle={t("po.subtitle")} /><Purchasing /></>);
}
