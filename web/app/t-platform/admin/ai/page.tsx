"use client";

import { useState } from "react";
import { AiLessons } from "@/components/dashboard/ai-lessons";
import { TrainRoom } from "@/components/admin/train-room";
import { useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, PageHeader } from "@/components/ui";

type MetaInfo = { configured: boolean; loginReady?: boolean; redirectUri?: string; webhookUrl?: string; verifyToken?: string };

export default function AdminAi() {
  const { t } = useI18n();
  const meta = useApi<MetaInfo>("platform/chat/meta");
  const [tab, setTab] = useState<"lessons" | "train">("lessons");
  return (
    <>
      <PageHeader title={t("admin.aiTitle")} />
      <div className="mb-4 flex gap-1 overflow-x-auto border-b [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
        {([["lessons", "admin.tabLessons"], ["train", "train.tab"]] as const).map(([k, label]) => (
          <button key={k} type="button" onClick={() => setTab(k)} className={`-mb-px shrink-0 whitespace-nowrap border-b-2 px-4 py-2 text-sm font-semibold ${tab === k ? "border-brand text-brand" : "border-transparent text-slate-500"}`}>{t(label)}</button>
        ))}
      </div>
      {tab === "train" && <TrainRoom />}
      {tab === "lessons" && <div className="max-w-2xl space-y-6">
        <Alert tone="blue">{t("admin.aiHint")}</Alert>
        <AiLessons base="platform/chat/lessons" title={t("admin.aiTitle")} canWrite prefill={null} />
        <section className="space-y-2 rounded-xl border bg-white p-4 text-sm">
          <h3 className="font-semibold">{t("admin.metaTitle")}</h3>
          {meta.data && !meta.data.configured && <Alert tone="amber">{t("admin.metaMissing")}</Alert>}
          {meta.data?.configured && (
            <dl className="space-y-2">
              <div><dt className="text-xs text-slate-500">{t("admin.metaWebhook")}</dt><dd className="select-all break-all rounded bg-slate-50 p-2 font-mono text-xs" dir="ltr">{meta.data.webhookUrl}</dd></div>
              <div><dt className="text-xs text-slate-500">{t("admin.metaVerify")}</dt><dd className="select-all break-all rounded bg-slate-50 p-2 font-mono text-xs" dir="ltr">{meta.data.verifyToken}</dd></div>
              <div><dt className="text-xs text-slate-500">{t("admin.metaRedirect")}</dt><dd className="select-all break-all rounded bg-slate-50 p-2 font-mono text-xs" dir="ltr">{meta.data.redirectUri}</dd></div>
              {!meta.data.loginReady && <Alert tone="amber">{t("admin.metaNoAppId")}</Alert>}
              <p className="text-xs text-slate-500">{t("admin.metaSteps")}</p>
            </dl>
          )}
        </section>
      </div>}
    </>
  );
}
