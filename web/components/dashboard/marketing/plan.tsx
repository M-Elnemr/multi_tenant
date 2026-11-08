"use client";

import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "../../hooks";
import { useI18n } from "../../i18n-provider";
import { Icon } from "../../icons";
import { useToast } from "../../toast";
import { Badge, Button, Empty, ErrorText, Textarea } from "../../ui";
import { AI_GRADIENT, AI_SOFT, CardSkeletons, errText } from "./kit";

type Item = { id: string; day: string; kind: string; productName?: string | null; caption: string; status: string; imageUrl?: string | null; hasVideo: boolean; wantVideo: boolean };
type Plan = { items: Item[]; videosEnabled: boolean };
const TONE: Record<string, "green" | "amber" | "blue" | "red"> = { SUGGESTED: "blue", APPROVED: "blue", SCHEDULED: "amber", POSTED: "green", FAILED: "red", SKIPPED: "blue" };

/** The coming week: one suggested post a day. Nothing goes out until the owner approves the item. */
export function PlanView({ canWrite }: { canWrite: boolean }) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const reduce = useReducedMotion();
  const plan = useApi<Plan>("store/marketing/plan");
  const [editing, setEditing] = useState<{ id: string; text: string } | null>(null);
  const gen = useAction(async () => { await api("store/marketing/plan/generate", { body: { language: locale } }); toast(t("mkx.planReady")); await plan.reload(); });
  const act = useAction(async (id: string, what: "approve" | "skip" | "video") => {
    try { await api(`store/marketing/plan/${id}/${what}`, { body: {} }); if (what === "approve") toast(t("mkx.approved")); await plan.reload(); }
    catch (e) { toast(errText(e, t("inbox.saveFailed")), { tone: "error" }); }
  });
  const save = useAction(async () => { if (!editing) return; await api(`store/marketing/plan/${editing.id}`, { method: "PATCH", body: { caption: editing.text } }); setEditing(null); toast(t("common.saved")); await plan.reload(); });
  if (plan.loading && !plan.data) return <CardSkeletons n={4} className="grid gap-3 md:grid-cols-2" h="h-60" />;
  const items = (plan.data?.items ?? []).filter((i) => i.status !== "SKIPPED");
  const day = (d: string) => { const x = new Date(`${d}T12:00:00`); return isNaN(+x) ? d : x.toLocaleDateString(locale === "ar" ? "ar-EG" : "en-GB", { weekday: "long", day: "numeric", month: "short" }); };
  return (
    <div className="space-y-4">
      <div className={`flex items-start gap-3 rounded-[18px] p-4 ring-1 ring-violet-200/60 ${AI_SOFT}`}>
        <span className={`grid h-9 w-9 shrink-0 place-items-center rounded-[11px] text-white ${AI_GRADIENT}`}><Icon name="sparkles" className="h-[18px] w-[18px]" /></span>
        <p className="min-w-0 flex-1 text-[13.5px] leading-relaxed text-ink-2">{t("pl.hint")}</p>
        {items.length > 0 && canWrite && <Button size="sm" variant="ink" loading={gen.loading} onClick={() => void gen.run()}><Icon name="sparkles" className="h-4 w-4" />{t("pl.generate")}</Button>}
      </div>
      <ErrorText error={plan.error ?? gen.error ?? save.error} />
      {items.length === 0 ? (
        <Empty icon="calendar" title={t("pl.empty")} action={canWrite ? <Button variant="ink" loading={gen.loading} onClick={() => void gen.run()}><Icon name="sparkles" className="h-4 w-4" />{t("pl.generate")}</Button> : undefined} />
      ) : (
        <ul className="grid gap-3 md:grid-cols-2">
          <AnimatePresence initial={false}>
            {items.map((i) => (
              <motion.li key={i.id} layout={!reduce} initial={reduce ? false : { opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, scale: 0.98 }} className="flex flex-col overflow-hidden rounded-[18px] bg-surface ring-1 ring-line">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                {i.imageUrl && <img src={i.imageUrl} alt="" className="h-40 w-full object-cover" />}
                <div className="flex flex-1 flex-col gap-3 p-4 sm:p-5">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span className="flex items-center gap-2 text-[13px] font-semibold text-ink"><Icon name="calendar" className="h-4 w-4 text-muted" />{day(i.day)}</span>
                    <span className="flex items-center gap-1.5"><Badge tone="slate">{i.kind === "SHOP" ? t("pl.shop") : i.productName ?? ""}</Badge><Badge tone={TONE[i.status] ?? "blue"}>{t(`pl.status.${i.status}`)}</Badge></span>
                  </div>
                  {editing?.id === i.id ? (
                    <div className="space-y-2"><Textarea rows={6} value={editing.text} onChange={(e) => setEditing({ id: i.id, text: e.target.value })} /><div className="flex gap-2"><Button size="sm" variant="ink" loading={save.loading} onClick={() => void save.run()}>{t("common.save")}</Button><Button size="sm" variant="ghost" onClick={() => setEditing(null)}>{t("pl.cancel")}</Button></div></div>
                  ) : <p className="flex-1 whitespace-pre-wrap text-[13.5px] leading-relaxed text-ink-2">{i.caption}</p>}
                  {i.hasVideo && <a href={`/api/bff/store/marketing/plan/${i.id}/video`} className="inline-flex items-center gap-1.5 text-[13px] font-semibold text-brand hover:underline"><Icon name="video" className="h-4 w-4" />{t("pl.downloadVideo")}</a>}
                  {canWrite && i.status === "SUGGESTED" && editing?.id !== i.id && (
                    <div className="flex flex-wrap items-center gap-2 border-t border-line pt-3">
                      <Button size="sm" variant="ink" loading={act.loading} onClick={() => void act.run(i.id, "approve")}><Icon name="check" className="h-4 w-4" />{t("pl.approve")}</Button>
                      <Button size="sm" variant="secondary" onClick={() => setEditing({ id: i.id, text: i.caption })}><Icon name="edit" className="h-4 w-4" />{t("pl.edit")}</Button>
                      {plan.data?.videosEnabled && i.kind === "PRODUCT" && <Button size="sm" variant="secondary" loading={act.loading} onClick={() => void act.run(i.id, "video")}><Icon name="video" className="h-4 w-4" />{t("pl.video")}</Button>}
                      <Button size="sm" variant="ghost" className="ms-auto" onClick={() => void act.run(i.id, "skip")}>{t("pl.skip")}</Button>
                    </div>
                  )}
                </div>
              </motion.li>
            ))}
          </AnimatePresence>
        </ul>
      )}
    </div>
  );
}
