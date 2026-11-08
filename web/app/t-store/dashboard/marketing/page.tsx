"use client";

import Link from "next/link";
import { api } from "@/lib/client";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { useToast } from "@/components/toast";
import { Alert, Button, Empty, ErrorText, PageHeader } from "@/components/ui";
import { CampaignsView } from "@/components/dashboard/marketing/campaigns";
import { PostsView, type Overview } from "@/components/dashboard/marketing/posts";
import { PlanView } from "@/components/dashboard/marketing/plan";
import { AutopilotView } from "@/components/dashboard/marketing/autopilot";
import { CardSkeletons, errText } from "@/components/dashboard/marketing/kit";
import { useNavTitle, useSection } from "@/components/dashboard/section";

export default function MarketingPage() {
  const { t } = useI18n();
  const me = useMe();
  const toast = useToast();
  const canWrite = me.canWrite("marketing");
  const ov = useApi<Overview>("store/marketing/overview");
  const [tab] = useSection(["posts", "plan", "ads", "auto"] as const, "posts");
  const title = useNavTitle(t("mk.title"));
  const start = useAction(async () => {
    try { const r = await api<{ url: string }>("store/ai/meta/start", { body: {} }); window.location.assign(r.url); }
    catch (e) { toast(errText(e, t("inbox.saveFailed")), { tone: "error" }); }
  });
  const data = ov.data;
  return (
    <>
      <PageHeader eyebrow={t("navgrp.marketing")} title={title} subtitle={t("mk.subtitle")}
        actions={<>
          {data && data.channels.length > 0 && (
            <span className="hidden items-center gap-1.5 text-[12.5px] text-muted sm:inline-flex"><span className="h-2 w-2 rounded-full bg-emerald-500" />{Array.from(new Set(data.channels.map((c) => t(`ai.platform.${c.platform}`)))).join(" · ")}</span>
          )}
          {canWrite && <Button variant="secondary" loading={start.loading} onClick={() => void start.run()}><Icon name="link" className="h-4 w-4" />{data && data.channels.length > 0 ? t("mk.reconnect") : t("mk.connectFb")}</Button>}
        </>} />
      <ErrorText error={ov.error} />
      {ov.loading && !data ? <CardSkeletons n={2} className="grid gap-4 md:grid-cols-2" h="h-64" /> : data && data.channels.length === 0 ? (
        <Empty icon="link" title={t("mk.noChannels.title")}
          action={canWrite ? <Button variant="ink" loading={start.loading} onClick={() => void start.run()}>{t("mk.connectFb")}</Button> : undefined}>
          <p>{t("mk.noChannels.body")}</p>
          <p className="mt-3 text-xs"><Link href="/dashboard/ai?tab=channels" className="font-semibold text-brand hover:underline">{t("mk.manageChannels")}</Link></p>
        </Empty>
      ) : data && (
        <div className="space-y-5">
          {data.adAccounts.length === 0 && <Alert tone="blue">{t("mk.reconnectHint")}</Alert>}
          {tab === "posts" ? <PostsView ov={data} canWrite={canWrite} /> : tab === "plan" ? <PlanView canWrite={canWrite} /> : tab === "auto" ? <AutopilotView canWrite={canWrite} /> : <CampaignsView ov={data} canWrite={canWrite} />}
        </div>
      )}
    </>
  );
}
