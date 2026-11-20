"use client";

import { api } from "@/lib/client";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, ErrorText } from "@/components/ui";

const MODES = ["BOTH", "DELIVERY", "PICKUP"] as const;

/** How products reach the customer: delivery only, pickup only, or both. It is separate from appointments (a service booking is not an order), and it switches the shipping methods that checkout, the shop page and the assistant all read. */
export function FulfilmentCard() {
  const { t } = useI18n();
  const { can } = useMe();
  const { data, reload } = useApi<{ mode: string }>("store/fulfilment");
  const set = useAction(async (mode: string) => { await api("store/fulfilment", { method: "PUT", body: { mode } }); await reload(); });
  const canManage = can("shipping.manage");
  return (
    <section className="space-y-3 rounded-[18px] bg-surface p-4 ring-1 ring-line sm:p-5">
      <div><h3 className="font-display text-[15px] font-semibold text-ink">{t("ful.title")}</h3><p className="text-[13px] text-muted">{t("ful.sub")}</p></div>
      <div className="grid gap-2 sm:grid-cols-3" role="radiogroup" aria-label={t("ful.title")}>
        {MODES.map((m) => (
          <button key={m} type="button" role="radio" aria-checked={data?.mode === m} disabled={!canManage || set.loading} onClick={() => void set.run(m)}
            className={`min-h-14 rounded-[14px] border p-3 text-start transition ${data?.mode === m ? "border-[var(--brand)] bg-brand-soft ring-2 ring-[var(--brand)]/25" : "border-line hover:border-[var(--brand)]/50"}`}>
            <span className="block text-sm font-semibold text-ink">{t(`ful.${m}`)}</span><span className="mt-0.5 block text-[12.5px] text-muted">{t(`ful.${m}.hint`)}</span>
          </button>
        ))}
      </div>
      {data?.mode === "NONE" && <Alert tone="amber">{t("ful.none")}</Alert>}
      <ErrorText error={set.error} />
    </section>
  );
}
