"use client";

import "@/components/lawyer/templates/shared/states.css"; // DR-B: neutral error state
import { useI18n } from "@/components/i18n-provider";

export default function SiteError({ reset }: { error: Error; reset: () => void }) {
  const { t } = useI18n();
  return (
    <div className="lnx-state" role="alert">
      <h2>{t("lnx.err.title")}</h2>
      <p>{t("lnx.err.text")}</p>
      <button type="button" onClick={() => reset()}>{t("lnx.err.retry")}</button>
    </div>
  );
}
