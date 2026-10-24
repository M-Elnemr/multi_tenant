"use client";

import { useRouter } from "next/navigation";
import { useApi } from "../hooks";
import { useI18n } from "../i18n-provider";

export type SendMode = "PAID" | "FREE";
type Evo = { available: boolean; enabled: boolean; status: string };

/** Whether the shop's own linked WhatsApp number can be used right now. Errors (no permission to read it) count as "not linked". */
export function useOwnNumber() {
  const evo = useApi<Evo>("store/ai/evolution");
  return { linked: !!evo.data?.enabled && evo.data.status === "CONNECTED", available: evo.data?.available !== false, loading: evo.loading && !evo.data };
}

/**
 * Free (the shop's own number) or paid (official WhatsApp) for one WhatsApp service. When the own number is not linked yet, choosing Free sends the owner to the
 * Settings tab where it is switched on, so no service silently falls back to paid messages.
 */
export function SendModePicker({ value, onChange, disabled, paidHint }: { value: SendMode; onChange: (m: SendMode) => void; disabled?: boolean; paidHint?: string }) {
  const { t } = useI18n();
  const router = useRouter();
  const own = useOwnNumber();
  const goSettings = () => router.push("/dashboard/settings#whatsapp-own");
  const base = "flex-1 rounded-2xl border p-3 text-start transition disabled:opacity-60";
  return (
    <div className="grid gap-2 sm:grid-cols-2" role="radiogroup" aria-label={t("sm.title")}>
      <button type="button" role="radio" aria-checked={value === "FREE" && own.linked} disabled={disabled} className={`${base} ${value === "FREE" && own.linked ? "border-emerald-500 bg-emerald-50 ring-2 ring-emerald-200" : "bg-white hover:bg-slate-50"}`}
        onClick={() => (own.linked ? onChange("FREE") : goSettings())}>
        <div className="flex items-center justify-between gap-2"><span className="font-semibold">🆓 {t("sm.free")}</span>{!own.linked && !own.loading && <span className="rounded-full bg-amber-100 px-2 py-0.5 text-[11px] font-semibold text-amber-800">{t("sm.linkFirst")}</span>}</div>
        <p className="mt-1 text-xs text-slate-600">{own.linked ? t("sm.freeHint") : t("sm.freeOff")}</p>
      </button>
      <button type="button" role="radio" aria-checked={value === "PAID" || !own.linked} disabled={disabled} className={`${base} ${value === "PAID" || !own.linked ? "border-brand bg-brand/5 ring-2 ring-brand/20" : "bg-white hover:bg-slate-50"}`} onClick={() => onChange("PAID")}>
        <div className="font-semibold">💳 {t("sm.paid")}</div>
        <p className="mt-1 text-xs text-slate-600">{paidHint ?? t("sm.paidHint")}</p>
      </button>
    </div>
  );
}
