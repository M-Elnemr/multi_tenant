"use client";

import Developers from "@/components/dashboard/developers";
import { useI18n } from "@/components/i18n-provider";
import { PageHeader } from "@/components/ui";

export default function DevelopersPage() {
  const { t } = useI18n();
  return (<><PageHeader title={t("dev.title")} subtitle={t("dev.subtitle")} /><Developers /></>);
}
