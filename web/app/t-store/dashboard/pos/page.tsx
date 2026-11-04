"use client";

import PosHub from "@/components/dashboard/pos-hub";
import { useI18n } from "@/components/i18n-provider";
import { PageHeader } from "@/components/ui";
import { useNavTitle } from "@/components/dashboard/section";

export default function PosPage() {
  const { t } = useI18n();
  const title = useNavTitle(t("pos.title"));
  return (
    <>
      <PageHeader title={title} subtitle={t("ph.subtitle")} />
      <PosHub />
    </>
  );
}
