"use client";

import { useT } from "./i18n-provider";
import { Icon } from "./icons";

export const BUSINESS_APP_URL = "https://play.google.com/store/apps/details?id=com.elmanassa.business";

/** Why to use the mobile app: shown to shop and clinic owners on the pricing, sign-up and pay pages. */
export function AppBenefits({ kind, compact }: { kind: "STORE" | "CLINIC"; compact?: boolean }) {
  const t = useT();
  const p = kind === "STORE" ? "store" : "clinic";
  return (
    <div className="rounded-2xl border border-brand/20 bg-brand-soft/60 p-5">
      <div className="flex items-start gap-3">
        <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-brand-gradient text-white"><Icon name="phone" className="h-5 w-5" /></span>
        <div className="min-w-0 flex-1">
          <p className="font-bold text-ink">{t(`app.${p}.title`)}</p>
          <ul className={`mt-2 space-y-1.5 text-sm text-slate-700 ${compact ? "" : "sm:columns-2"}`}>
            {[1, 2, 3, 4].map((i) => (
              <li key={i} className="flex items-start gap-2"><span className="mt-0.5 flex h-4 w-4 shrink-0 items-center justify-center rounded-full bg-emerald-100 text-emerald-700"><Icon name="check" className="h-2.5 w-2.5" /></span>{t(`app.${p}.b${i}`)}</li>
            ))}
          </ul>
          <a href={BUSINESS_APP_URL} target="_blank" rel="noopener noreferrer" className="mt-3 inline-block rounded-xl bg-brand-gradient px-4 py-2 text-sm font-semibold text-white shadow-brand transition hover:brightness-110 active:scale-[.97]">{t("app.get")}</a>
        </div>
      </div>
    </div>
  );
}
