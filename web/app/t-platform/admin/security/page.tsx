"use client";

import SecurityCard from "@/components/dashboard/security-card";
import { useI18n } from "@/components/i18n-provider";
import { PageHeader } from "@/components/ui";

export default function AdminSecurity() {
  const { t } = useI18n();
  return (
    <>
      <PageHeader title={t("sec.title")} subtitle={t("sec.adminSubtitle")} />
      <div className="max-w-2xl"><SecurityCard /></div>
    </>
  );
}
