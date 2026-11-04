"use client";

import PosHub from "@/components/dashboard/pos-hub";
import { useI18n } from "@/components/i18n-provider";
import { PageHeader } from "@/components/ui";

export default function PosPage() {
  const { t } = useI18n();
  return (
    <>
      <PageHeader title={t("pos.title")} subtitle={t("ph.subtitle")} />
      <PosHub />
    </>
  );
}
