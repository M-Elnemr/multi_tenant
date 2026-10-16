"use client";

import { useT } from "./i18n-provider";
import { Icon } from "./icons";

const COUNT = { STORE: 10, CLINIC: 9 } as const;

/** The real benefits of a plan, in plain words (not feature flags). The last line says whether the plan includes a custom domain. */
export function PlanBenefits({ type, domain }: { type: "STORE" | "CLINIC"; domain: boolean }) {
  const t = useT();
  const k = type === "STORE" ? "store" : "clinic";
  const rows = [...Array.from({ length: COUNT[type] }, (_, i) => ({ text: t(`benefit.${k}.${i + 1}`), on: true })), { text: t(domain ? "benefit.domain.yes" : "benefit.domain.no"), on: domain }];
  return (
    <ul className="space-y-2 text-sm text-slate-700">
      {rows.map((r, i) => (
        <li key={i} className={`flex items-start gap-2 ${r.on ? "" : "text-slate-400 line-through"}`}>
          <span className={`mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full ${r.on ? "bg-emerald-50 text-emerald-600" : "bg-slate-100 text-slate-400"}`}>{r.on ? <Icon name="check" className="h-3 w-3" /> : "✕"}</span>
          <span>{r.text}</span>
        </li>
      ))}
    </ul>
  );
}
