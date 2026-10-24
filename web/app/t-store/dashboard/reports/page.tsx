"use client";

import ReportsPlus from "@/components/dashboard/reports-plus";
import { useI18n } from "@/components/i18n-provider";
import { PageHeader } from "@/components/ui";

export default function ReportsPage() {
  const { t } = useI18n();
  return (<><PageHeader title={t("rp.title")} subtitle={t("rp.subtitle")} /><ReportsPlus /></>);
}
