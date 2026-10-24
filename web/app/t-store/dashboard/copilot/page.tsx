"use client";

import Link from "next/link";
import CopilotView from "@/components/dashboard/copilot-view";
import { useI18n } from "@/components/i18n-provider";
import { PageHeader } from "@/components/ui";

export default function CopilotPage() {
  const { t } = useI18n();
  return (
    <>
      <PageHeader title={t("cp.title")} subtitle={t("cp.subtitle")} actions={<Link href="/dashboard/settings#whatsapp-report" className="inline-flex items-center rounded-xl border bg-white px-4 py-2 text-sm font-semibold text-slate-700 hover:bg-slate-50">{t("cp.whatsapp")}</Link>} />
      <CopilotView />
    </>
  );
}
