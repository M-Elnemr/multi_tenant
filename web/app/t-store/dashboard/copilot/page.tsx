"use client";

import CopilotView from "@/components/dashboard/copilot-view";
import { HeaderLink } from "@/components/dashboard/kit";
import { useI18n } from "@/components/i18n-provider";
import { PageHeader } from "@/components/ui";

export default function CopilotPage() {
  const { t } = useI18n();
  return (
    <>
      <PageHeader eyebrow={t("nav.ai")} title={t("cp.title")} subtitle={t("cp.subtitle")} actions={<HeaderLink href="/dashboard/settings?tab=marketing#whatsapp-report" icon="chat">{t("cp.whatsapp")}</HeaderLink>} />
      <CopilotView />
    </>
  );
}
