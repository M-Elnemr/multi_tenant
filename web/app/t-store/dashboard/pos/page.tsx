"use client";

import Link from "next/link";
import Pos from "@/components/dashboard/pos";
import { useI18n } from "@/components/i18n-provider";
import { PageHeader } from "@/components/ui";

export default function PosPage() {
  const { t } = useI18n();
  return (
    <>
      <PageHeader title={t("pos.title")} subtitle={t("pos.subtitle")} actions={<Link href="/dashboard/products/labels" className="inline-flex items-center rounded-xl border bg-white px-4 py-2 text-sm font-semibold hover:bg-slate-50">{t("pos.labels")}</Link>} />
      <Pos />
    </>
  );
}
