"use client";

import { useState } from "react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { api } from "@/lib/client";
import { useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { ConfirmDialog } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { Button, Empty, ErrorText, Field, Skeleton, Textarea } from "@/components/ui";
import { AI_GRADIENT } from "./kit";

type Lesson = { id: string; instruction: string; exampleQuestion?: string; exampleAnswer?: string; createdAt: string };

/** Teach the assistant: a rule and/or an example reply. Used by a shop (its own lessons) and by the platform admin (lessons for every shop). */
export function AiLessons({ base, title, canWrite, prefill }: { base: string; title: string; canWrite: boolean; prefill?: { question: string; answer: string; n: number } | null }) {
  const { t } = useI18n();
  const toast = useToast();
  const reduce = useReducedMotion();
  const { data, reload, loading } = useApi<Lesson[]>(base);
  // The parent remounts this with a new key when the owner clicks "teach a better reply", so the example arrives pre-filled.
  const [f, setF] = useState({ instruction: "", exampleQuestion: prefill?.question ?? "", exampleAnswer: prefill?.answer ?? "" });
  const [confirm, setConfirm] = useState<string | null>(null);

  const save = useAction(async () => {
    await api(base, { body: f });
    setF({ instruction: "", exampleQuestion: "", exampleAnswer: "" });
    toast(t("ai.lessonSaved"));
    await reload();
  });
  const remove = useAction(async (id: string) => { await api(`${base}/${id}`, { method: "DELETE" }); setConfirm(null); toast(t("kit.lessonRemoved")); await reload(); });

  return (
    <div className="space-y-5">
      {canWrite && (
        <form onSubmit={(e) => { e.preventDefault(); void save.run(); }} className="overflow-hidden rounded-[18px] bg-surface ring-1 ring-line">
          <header className="flex items-start gap-3 p-5 pb-0">
            <span className="grid h-9 w-9 shrink-0 place-items-center rounded-[11px] text-white" style={{ background: AI_GRADIENT }}><Icon name="sparkles" className="h-[18px] w-[18px]" /></span>
            <div className="min-w-0">
              <h3 className="font-display text-[15px] font-semibold text-ink">{title}</h3>
              <p className="mt-0.5 text-[13px] text-muted">{t("ai.instructionHint")}</p>
            </div>
          </header>
          <div className="space-y-4 p-5">
            <Field label={t("ai.instruction")}><Textarea rows={2} maxLength={1000} value={f.instruction} onChange={(e) => setF({ ...f, instruction: e.target.value })} /></Field>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label={t("ai.exQuestion")}><Textarea rows={3} maxLength={1000} value={f.exampleQuestion} onChange={(e) => setF({ ...f, exampleQuestion: e.target.value })} /></Field>
              <Field label={t("ai.exAnswer")}><Textarea rows={3} maxLength={2000} value={f.exampleAnswer} onChange={(e) => setF({ ...f, exampleAnswer: e.target.value })} /></Field>
            </div>
            <ErrorText error={save.error ?? remove.error} />
          </div>
          <div className="flex justify-end border-t border-line bg-surface-2/60 px-5 py-3">
            <Button type="submit" loading={save.loading} disabled={!f.instruction.trim() && !f.exampleAnswer.trim()}><Icon name="check" className="h-4 w-4" />{t("ai.saveLesson")}</Button>
          </div>
        </form>
      )}
      <div>
        <h3 className="mb-3 flex items-center gap-2 text-[13px] font-semibold text-ink">{t("ai.lessons")}{data && <span className="rounded-full bg-surface-2 px-2 py-0.5 text-[11px] text-muted ring-1 ring-inset ring-line tnum">{data.length}</span>}</h3>
        {loading && !data && <div className="space-y-2"><Skeleton className="h-16 w-full !rounded-[14px]" /><Skeleton className="h-16 w-full !rounded-[14px]" /></div>}
        {data && data.length === 0 && <Empty compact icon="sparkles">{t("ai.noLessons")}</Empty>}
        <ul className="space-y-2.5">
          <AnimatePresence initial={false}>
            {data?.map((l) => (
              <motion.li key={l.id} layout={!reduce} initial={reduce ? false : { opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} transition={{ duration: 0.2 }}
                className="group flex items-start justify-between gap-3 rounded-[14px] bg-surface p-4 text-[13.5px] ring-1 ring-line">
                <div className="min-w-0 space-y-2">
                  {l.instruction && <p className="font-semibold text-ink">{l.instruction}</p>}
                  {l.exampleQuestion && (
                    <p className="flex items-start gap-2 text-muted"><Icon name="users" className="mt-0.5 h-3.5 w-3.5 shrink-0" /><span className="min-w-0">{l.exampleQuestion}</span></p>
                  )}
                  {l.exampleAnswer && (
                    <p className="flex items-start gap-2 rounded-[10px] bg-[linear-gradient(135deg,rgb(124_92_255/.08),rgb(34_211_238/.08))] px-2.5 py-1.5 text-ink-2"><Icon name="sparkles" className="mt-0.5 h-3.5 w-3.5 shrink-0 text-violet-600" /><span className="min-w-0">{l.exampleAnswer}</span></p>
                  )}
                </div>
                {canWrite && (
                  <button type="button" onClick={() => setConfirm(l.id)} aria-label={t("common.delete")} title={t("common.delete")}
                    className="grid h-8 w-8 shrink-0 place-items-center rounded-[9px] text-faint transition hover:bg-red-50 hover:text-red-600 pointer-coarse:h-11 pointer-coarse:w-11"><Icon name="close" className="h-4 w-4" /></button>
                )}
              </motion.li>
            ))}
          </AnimatePresence>
        </ul>
      </div>
      <ConfirmDialog open={!!confirm} onClose={() => setConfirm(null)} onConfirm={() => confirm && void remove.run(confirm)} loading={remove.loading} danger
        title={t("kit.removeLessonTitle")} body={t("kit.removeLessonBody")} confirmLabel={t("common.delete")} cancelLabel={t("common.cancel")} />
    </div>
  );
}
