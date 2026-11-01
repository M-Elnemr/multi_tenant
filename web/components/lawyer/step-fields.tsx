"use client";

import { STEP_KINDS } from "@/lib/lawyer";
import { useI18n } from "../i18n-provider";
import { Field, Input, Select } from "../ui";

/** Add or edit a dated step: it shows on the schedule and reminds the lawyer. */
export function StepFields({ f, set, err, p = "" }: { f: { kind: string; startsAt: string; durationMinutes: string; description: string; location: string; result: string; done: boolean }; set: (x: Partial<typeof f>) => void; err: (k: string) => string | undefined; p?: string }) {
  const { t } = useI18n();
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap gap-1.5">{STEP_KINDS.map((k) => <button key={k} type="button" onClick={() => set({ kind: k })} className={`chip ${f.kind === k ? "chip-on" : ""}`}>{t(`lw.cal.${k}`)}</button>)}</div>
      <Field name={`${p}description`} error={err(`${p}description`)} label={t("lw.step.description")}><Input value={f.description} onChange={(e) => set({ description: e.target.value })} placeholder={t("lw.step.descPh")} /></Field>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field name={`${p}startsAt`} error={err(`${p}startsAt`)} label={t("lw.step.when")}><Input type="datetime-local" value={f.startsAt} onChange={(e) => set({ startsAt: e.target.value })} /></Field>
        <Field name={`${p}durationMinutes`} error={err(`${p}durationMinutes`)} label={t("lw.step.duration")}><Select value={f.durationMinutes} onChange={(e) => set({ durationMinutes: e.target.value })}>{["30", "60", "90", "120", "180", "240"].map((m) => <option key={m} value={m}>{t("lw.minutes", { n: m })}</option>)}</Select></Field>
      </div>
      <Field label={t("lw.step.location")}><Input value={f.location} onChange={(e) => set({ location: e.target.value })} placeholder={t("lw.case.courtPh")} /></Field>
    </div>
  );
}

