"use client";

import Link from "next/link";
import { api } from "@/lib/client";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Button, Empty, ErrorText, Loading, PageHeader } from "@/components/ui";
import { CampaignsView } from "@/components/dashboard/marketing/campaigns";
import { PostsView, type Overview } from "@/components/dashboard/marketing/posts";
import { PlanView } from "@/components/dashboard/marketing/plan";
import { AutopilotView } from "@/components/dashboard/marketing/autopilot";
import { useNavTitle, useSection } from "@/components/dashboard/section";

export default function MarketingPage() {
  const { t } = useI18n();
  const me = useMe();
  const canWrite = me.canWrite("marketing");
  const ov = useApi<Overview>("store/marketing/overview");
  const [tab] = useSection(["posts", "plan", "ads", "auto"] as const, "posts");
  const title = useNavTitle(t("mk.title"));
  const start = useAction(async () => { const r = await api<{ url: string }>("store/ai/meta/start", { body: {} }); window.location.assign(r.url); });
  if (ov.loading && !ov.data) return <Loading />;
  const data = ov.data;
  return (
    <>
      <PageHeader title={title} subtitle={t("mk.subtitle")} actions={canWrite ? <Button variant="secondary" loading={start.loading} onClick={() => void start.run()}>{data && data.channels.length > 0 ? t("mk.reconnect") : t("mk.connectFb")}</Button> : undefined} />
      <ErrorText error={ov.error ?? start.error} />
      {data && data.channels.length === 0 ? (
        <Empty>
          <p className="mb-1 text-base font-bold text-ink">{t("mk.noChannels.title")}</p>
          <p className="mx-auto mb-4 max-w-md">{t("mk.noChannels.body")}</p>
          {canWrite && <Button loading={start.loading} onClick={() => void start.run()}>{t("mk.connectFb")}</Button>}
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
