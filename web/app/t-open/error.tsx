"use client";

import { OEmpty } from "@/components/open/empty";
import { useI18n } from "@/components/i18n-provider";

/** Next 16 hands the boundary `retry` (not `reset`). A failed request shows one calm message and a way to try again. */
export default function OpenError({ retry }: { error: Error; retry?: () => void }) {
  const { t } = useI18n();
  return (
    <div className="o-container pt-12 md:pt-20">
      <OEmpty icon="alert" tone="error" title={t("open.error.title")} body={t("open.error.body")} />
      <div className="mt-4 flex justify-center gap-3">
        {retry && <button type="button" onClick={() => retry()} className="o-btn">{t("open.error.retry")}</button>}
        <a href="/" className="o-btn o-btn-ghost">{t("shop.home")}</a>
      </div>
    </div>
  );
}
