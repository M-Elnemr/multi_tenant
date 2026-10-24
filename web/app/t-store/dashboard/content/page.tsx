"use client";

import Content from "@/components/dashboard/content";
import { useI18n } from "@/components/i18n-provider";
import { PageHeader } from "@/components/ui";

export default function ContentPage() {
  const { t } = useI18n();
  return (<><PageHeader title={t("blog.title")} subtitle={t("blog.subtitle")} /><Content /></>);
}
