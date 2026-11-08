"use client";

import Link from "next/link";
import { api } from "@/lib/client";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Icon } from "../icons";
import { useToast } from "../toast";
import { Button, ErrorText, Skeleton } from "../ui";

type Q = { id: number; question: string; resolved: boolean };
type Usage = { usedThisMonth: number; monthlyCap: number; unanswered: number };

/** What the AI seller could not answer (so the owner can teach it) and how many automatic replies were used this month. */
export function Unanswered({ canWrite }: { canWrite: boolean }) {
  const { t } = useI18n();
  const toast = useToast();
  const q = useApi<Q[]>("store/ai/unanswered");
  const u = useApi<Usage>("store/ai/usage");
  const done = useAction(async (id: number) => { await api(`store/ai/unanswered/${id}/resolve`, { body: {} }); await q.reload(); await u.reload(); toast(t("kit.resolved")); });
  const open = (q.data ?? []).filter((x) => !x.resolved);
  return (
    <section className="mt-5 overflow-hidden rounded-[18px] bg-surface ring-1 ring-line">
      <header className="flex flex-wrap items-start justify-between gap-3 p-5">
        <div className="flex min-w-0 items-start gap-3">
          <span className="grid h-9 w-9 shrink-0 place-items-center rounded-[11px] bg-amber-50 text-amber-700 ring-1 ring-inset ring-amber-600/15"><Icon name="alert" className="h-[18px] w-[18px]" /></span>
          <div className="min-w-0">
            <h2 className="font-display text-[15px] font-semibold text-ink">{t("un.title")}</h2>
            <p className="mt-0.5 text-[13px] leading-relaxed text-muted">{t("un.hint")}</p>
          </div>
        </div>
        {u.data && <span className="rounded-full bg-surface-2 px-2.5 py-1 text-[11.5px] font-semibold text-muted ring-1 ring-inset ring-line tnum">{t("un.usage")}: {u.data.usedThisMonth} / {u.data.monthlyCap}</span>}
      </header>
      <div className="px-5 pb-3"><ErrorText error={q.error ?? done.error} /></div>
      {q.loading && !q.data ? (
        <div className="space-y-2 px-5 pb-5"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></div>
      ) : open.length === 0 ? (
        <p className="flex items-center gap-2 border-t border-line px-5 py-4 text-[13px] text-muted"><Icon name="check" className="h-4 w-4 text-emerald-600" />{t("un.none")}</p>
      ) : (
        <ul className="divide-y divide-line border-t border-line">
          {open.map((x) => (
            <li key={x.id} className="flex items-start justify-between gap-3 px-5 py-3 text-[13.5px]">
              <span className="min-w-0 text-ink-2">{x.question}</span>
              {canWrite && <Button size="sm" variant="secondary" loading={done.loading} onClick={() => void done.run(x.id)}><Icon name="check" className="h-3.5 w-3.5" />{t("un.resolve")}</Button>}
            </li>
          ))}
        </ul>
      )}
      <Link href="/dashboard/questions" className="group flex items-center justify-between gap-2 border-t border-line px-5 py-3 text-[13px] font-semibold text-brand transition hover:bg-surface-2">
        {t("un.answerLink")}<Icon name="chevron" className="h-4 w-4 transition-transform group-hover:translate-x-0.5 flip-rtl" />
      </Link>
    </section>
  );
}
