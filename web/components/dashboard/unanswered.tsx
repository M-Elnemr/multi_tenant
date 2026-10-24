"use client";

import { api } from "@/lib/client";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Button, Card, ErrorText } from "../ui";

type Q = { id: number; question: string; resolved: boolean };
type Usage = { usedThisMonth: number; monthlyCap: number; unanswered: number };

/** What the AI seller could not answer (so the owner can teach it) and how many automatic replies were used this month. */
export function Unanswered({ canWrite }: { canWrite: boolean }) {
  const { t } = useI18n();
  const q = useApi<Q[]>("store/ai/unanswered");
  const u = useApi<Usage>("store/ai/usage");
  const done = useAction(async (id: number) => { await api(`store/ai/unanswered/${id}/resolve`, { body: {} }); await q.reload(); await u.reload(); });
  const open = (q.data ?? []).filter((x) => !x.resolved);
  return (
    <Card className="mt-5 space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2"><h2 className="font-medium">{t("un.title")}</h2>{u.data && <span className="text-xs text-slate-500">{t("un.usage")}: {u.data.usedThisMonth} / {u.data.monthlyCap}</span>}</div>
      <p className="text-sm text-slate-500">{t("un.hint")}</p>
      <ErrorText error={q.error ?? done.error} />
      {open.length === 0 ? <p className="text-sm text-slate-500">{t("un.none")}</p> : open.map((x) => (
        <div key={x.id} className="flex items-start justify-between gap-3 border-t pt-2 text-sm first:border-0 first:pt-0"><span>{x.question}</span>{canWrite && <Button size="sm" variant="ghost" loading={done.loading} onClick={() => void done.run(x.id)}>{t("un.resolve")}</Button>}</div>
      ))}
    </Card>
  );
}
