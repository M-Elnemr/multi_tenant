"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { useAction } from "./hooks";
import { useT } from "./i18n-provider";
import { Badge, ErrorText, Modal } from "./ui";

export type VisitType = "CONSULTATION" | "FOLLOW_UP";

/** "كشف" (blue) or "إعادة" (amber). Renders nothing when the type is unknown. */
export function VisitTypeBadge({ type }: { type?: string | null }) {
  const t = useT();
  if (type !== "CONSULTATION" && type !== "FOLLOW_UP") return null;
  return <Badge tone={type === "FOLLOW_UP" ? "amber" : "blue"}>{t(type === "FOLLOW_UP" ? "visit.followUp" : "visit.consultation")}</Badge>;
}

/** Two big choices; the one in use is highlighted. */
export function VisitTypePicker({ value, onChange }: { value: VisitType; onChange: (v: VisitType) => void }) {
  const t = useT();
  return (
    <div role="radiogroup" aria-label={t("visit.pick")} className="grid grid-cols-2 gap-3">
      {(["CONSULTATION", "FOLLOW_UP"] as const).map((v) => {
        const on = value === v;
        return (
          <button key={v} type="button" role="radio" aria-checked={on} onClick={() => onChange(v)}
            className={`rounded-2xl border-2 px-4 py-4 text-center transition duration-200 active:scale-[.97] ${on ? "border-brand bg-brand-soft text-brand-dark shadow-brand" : "border-slate-200 bg-white text-slate-600 hover:border-brand/40"}`}>
            <span className="block text-lg font-bold">{t(v === "FOLLOW_UP" ? "visit.followUp" : "visit.consultation")}</span>
            <span className="mt-0.5 block text-xs opacity-70">{t(v === "FOLLOW_UP" ? "visit.followUpHint" : "visit.consultationHint")}</span>
          </button>
        );
      })}
    </div>
  );
}

/** The badge as a button: staff or the doctor tap it to correct the type (also for finished visits). */
export function VisitTypeEditable({ appointmentId, type, onChanged }: { appointmentId: string; type?: string | null; onChanged: () => void | Promise<void> }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const save = useAction(async (v: VisitType) => {
    await api(`clinic/appointments/${appointmentId}/visit-type`, { method: "PATCH", body: { visitType: v } });
    setOpen(false);
    await onChanged();
  });
  return (
    <>
      <button type="button" onClick={() => setOpen(true)} title={t("visit.change")} className="rounded-full transition hover:scale-105 hover:shadow-sm active:scale-95"><VisitTypeBadge type={type ?? "CONSULTATION"} /></button>
      <Modal open={open} onClose={() => setOpen(false)} title={t("visit.pick")}>
        <VisitTypePicker value={(type as VisitType) ?? "CONSULTATION"} onChange={(v) => void save.run(v)} />
        <div className="mt-3"><ErrorText error={save.error} /></div>
      </Modal>
    </>
  );
}
