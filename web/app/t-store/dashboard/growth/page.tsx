"use client";

import GrowthView from "@/components/dashboard/growth";
import { useI18n } from "@/components/i18n-provider";
import { PageHeader } from "@/components/ui";
import { useNavTitle } from "@/components/dashboard/section";

export default function GrowthPage() {
  const { t } = useI18n();
  const title = useNavTitle(t("gr.title"));
  return (
    <>
      <PageHeader title={title} subtitle={t("gr.subtitle")} />
      <GrowthView />
    </>
  );
}
