"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Button, ErrorText, Field, Textarea } from "@/components/ui";

type Lesson = { id: string; instruction: string; exampleQuestion?: string; exampleAnswer?: string; createdAt: string };

/** Teach the assistant: a rule and/or an example reply. Used by a shop (its own lessons) and by the platform admin (lessons for every shop). */
export function AiLessons({ base, title, canWrite, prefill }: { base: string; title: string; canWrite: boolean; prefill?: { question: string; answer: string; n: number } | null }) {
  const { t } = useI18n();
  const { data, reload } = useApi<Lesson[]>(base);
  // The parent remounts this with a new key when the owner clicks "teach a better reply", so the example arrives pre-filled.
  const [f, setF] = useState({ instruction: "", exampleQuestion: prefill?.question ?? "", exampleAnswer: prefill?.answer ?? "" });
  const [saved, setSaved] = useState(false);

  const save = useAction(async () => {
    await api(base, { body: f });
    setF({ instruction: "", exampleQuestion: "", exampleAnswer: "" });
    setSaved(true);
    await reload();
  });
  const remove = useAction(async (id: string) => { await api(`${base}/${id}`, { method: "DELETE" }); await reload(); });

  return (
    <div className="space-y-6">
      {canWrite && (
        <form onSubmit={(e) => { e.preventDefault(); setSaved(false); void save.run(); }} className="space-y-3 rounded-xl border bg-white p-4">
          <h3 className="font-semibold">{title}</h3>
          <Field label={t("ai.instruction")} hint={t("ai.instructionHint")}><Textarea rows={2} maxLength={1000} value={f.instruction} onChange={(e) => setF({ ...f, instruction: e.target.value })} /></Field>
          <Field label={t("ai.exQuestion")}><Textarea rows={2} maxLength={1000} value={f.exampleQuestion} onChange={(e) => setF({ ...f, exampleQuestion: e.target.value })} /></Field>
          <Field label={t("ai.exAnswer")}><Textarea rows={3} maxLength={2000} value={f.exampleAnswer} onChange={(e) => setF({ ...f, exampleAnswer: e.target.value })} /></Field>
          <ErrorText error={save.error ?? remove.error} />
          {saved && <Alert tone="green">{t("ai.lessonSaved")}</Alert>}
          <Button type="submit" loading={save.loading} disabled={!f.instruction.trim() && !f.exampleAnswer.trim()}>{t("ai.saveLesson")}</Button>
        </form>
      )}
      <div>
        <h3 className="mb-2 font-semibold">{t("ai.lessons")}</h3>
        {data && data.length === 0 && <p className="text-sm text-slate-500">{t("ai.noLessons")}</p>}
        <ul className="space-y-2">
          {data?.map((l) => (
            <li key={l.id} className="flex items-start justify-between gap-3 rounded-xl border bg-white p-3 text-sm">
              <div className="min-w-0">
                <p className="font-medium">{l.instruction}</p>
                {l.exampleQuestion && <p className="mt-1 text-slate-500">👤 {l.exampleQuestion}</p>}
                {l.exampleAnswer && <p className="mt-0.5 text-slate-700">🤖 {l.exampleAnswer}</p>}
              </div>
              {canWrite && <Button size="sm" variant="ghost" onClick={() => void remove.run(l.id)}>✕</Button>}
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
