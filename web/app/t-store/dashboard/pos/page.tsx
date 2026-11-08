"use client";

import PosHub from "@/components/dashboard/pos-hub";
import { useI18n } from "@/components/i18n-provider";
import { PageHeader } from "@/components/ui";
import { useNavTitle } from "@/components/dashboard/section";
import { LinkButton } from "@/components/dashboard/sell/kit";

export default function PosPage() {
  const { t } = useI18n();
  const title = useNavTitle(t("pos.title"));
  return (
    <div className="space-y-5">
      <PageHeader eyebrow={t("sell.eyebrow")} title={title} subtitle={t("ph.subtitle")}
        actions={<LinkButton href="/pos" icon="receipt" primary>{t("ph.openTerminal")}</LinkButton>} />
      <PosHub />
    </div>
  );
}
