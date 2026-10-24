"use client";

import GrowthView from "@/components/dashboard/growth";
import { useI18n } from "@/components/i18n-provider";
import { PageHeader } from "@/components/ui";

export default function GrowthPage() {
  const { t } = useI18n();
  return (
    <>
      <PageHeader title={t("gr.title")} subtitle={t("gr.subtitle")} />
      <GrowthView />
    </>
  );
}
