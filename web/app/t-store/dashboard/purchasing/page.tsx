"use client";

import Purchasing from "@/components/dashboard/purchasing";
import { useI18n } from "@/components/i18n-provider";
import { PageHeader } from "@/components/ui";
import { useNavTitle } from "@/components/dashboard/section";

export default function PurchasingPage() {
  const { t } = useI18n();
  const title = useNavTitle(t("po.title"));
  return (<><PageHeader title={title} subtitle={t("po.subtitle")} /><Purchasing /></>);
}
