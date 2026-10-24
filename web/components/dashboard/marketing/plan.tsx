"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "../../hooks";
import { useI18n } from "../../i18n-provider";
import { Alert, Badge, Button, Card, Empty, ErrorText, Loading, Textarea } from "../../ui";

type Item = { id: string; day: string; kind: string; productName?: string | null; caption: string; status: string; imageUrl?: string | null; hasVideo: boolean; wantVideo: boolean };
type Plan = { items: Item[]; videosEnabled: boolean };
const TONE: Record<string, "green" | "amber" | "blue" | "red"> = { SUGGESTED: "blue", APPROVED: "blue", SCHEDULED: "amber", POSTED: "green", FAILED: "red", SKIPPED: "blue" };

/** The coming week: one suggested post a day. Nothing goes out until the owner approves the item. */
export function PlanView({ canWrite }: { canWrite: boolean }) {
  const { t, locale } = useI18n();
  const plan = useApi<Plan>("store/marketing/plan");
  const [editing, setEditing] = useState<{ id: string; text: string } | null>(null);
  const gen = useAction(async () => { await api("store/marketing/plan/generate", { body: { language: locale } }); await plan.reload(); });
  const act = useAction(async (id: string, what: "approve" | "skip" | "video") => { await api(`store/marketing/plan/${id}/${what}`, { body: {} }); await plan.reload(); });
  const save = useAction(async () => { if (!editing) return; await api(`store/marketing/plan/${editing.id}`, { method: "PATCH", body: { caption: editing.text } }); setEditing(null); await plan.reload(); });
  if (plan.loading && !plan.data) return <Loading />;
  const items = (plan.data?.items ?? []).filter((i) => i.status !== "SKIPPED");
  return (
    <div className="space-y-4">
      <Alert tone="blue">{t("pl.hint")}</Alert>
      <ErrorText error={plan.error ?? gen.error ?? act.error ?? save.error} />
      {items.length === 0 ? (
        <Empty><p className="mb-3">{t("pl.empty")}</p>{canWrite && <Button loading={gen.loading} onClick={() => void gen.run()}>{t("pl.generate")}</Button>}</Empty>
      ) : (
        <div className="grid gap-3 md:grid-cols-2">
          {items.map((i) => (
            <Card key={i.id} className="space-y-3">
              <div className="flex items-center justify-between gap-2"><div className="text-sm font-semibold" dir="ltr">{i.day}</div><div className="flex items-center gap-2"><Badge tone="blue">{i.kind === "SHOP" ? t("pl.shop") : i.productName ?? ""}</Badge><Badge tone={TONE[i.status] ?? "blue"}>{t(`pl.status.${i.status}`)}</Badge></div></div>
              {editing?.id === i.id ? (
                <div className="space-y-2"><Textarea rows={6} value={editing.text} onChange={(e) => setEditing({ id: i.id, text: e.target.value })} /><div className="flex gap-2"><Button size="sm" loading={save.loading} onClick={() => void save.run()}>{t("common.save")}</Button><Button size="sm" variant="ghost" onClick={() => setEditing(null)}>{t("pl.cancel")}</Button></div></div>
              ) : <p className="whitespace-pre-wrap text-sm text-slate-700">{i.caption}</p>}
              {i.imageUrl && /* eslint-disable-next-line @next/next/no-img-element */ <img src={i.imageUrl} alt="" className="h-40 w-full rounded-xl object-cover" />}
              {canWrite && i.status === "SUGGESTED" && editing?.id !== i.id && (
                <div className="flex flex-wrap gap-2">
                  <Button size="sm" loading={act.loading} onClick={() => void act.run(i.id, "approve")}>{t("pl.approve")}</Button>
                  <Button size="sm" variant="secondary" onClick={() => setEditing({ id: i.id, text: i.caption })}>{t("pl.edit")}</Button>
                  {plan.data?.videosEnabled && i.kind === "PRODUCT" && <Button size="sm" variant="secondary" loading={act.loading} onClick={() => void act.run(i.id, "video")}>{t("pl.video")}</Button>}
                  <Button size="sm" variant="ghost" onClick={() => void act.run(i.id, "skip")}>{t("pl.skip")}</Button>
                </div>
              )}
              {i.hasVideo && <a href={`/api/bff/store/marketing/plan/${i.id}/video`} className="text-sm font-semibold text-brand hover:underline">{t("pl.downloadVideo")}</a>}
            </Card>
          ))}
        </div>
      )}
      {items.length > 0 && canWrite && <Button variant="secondary" loading={gen.loading} onClick={() => void gen.run()}>{t("pl.generate")}</Button>}
    </div>
  );
}
