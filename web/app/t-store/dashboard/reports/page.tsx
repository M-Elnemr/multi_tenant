"use client";

import ReportsPlus from "@/components/dashboard/reports-plus";
import { useI18n } from "@/components/i18n-provider";
import { PageHeader } from "@/components/ui";
import { useNavTitle } from "@/components/dashboard/section";

export default function ReportsPage() {
  const { t } = useI18n();
  const title = useNavTitle(t("rp.title"));
  return (<><PageHeader title={title} subtitle={t("rp.subtitle")} /><ReportsPlus /></>);
}
