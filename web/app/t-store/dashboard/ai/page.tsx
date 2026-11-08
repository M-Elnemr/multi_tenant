"use client";

import { Suspense, useCallback, useState } from "react";
import { useSearchParams } from "next/navigation";
import { api } from "@/lib/client";
import { ChatPanel } from "@/components/shop/live-chat";
import { AiChannels } from "@/components/dashboard/ai-channels";
import { AiWhatsApp } from "@/components/dashboard/ai-whatsapp";
import { PointsWallet } from "@/components/dashboard/points-wallet";
import { AiLessons } from "@/components/dashboard/ai-lessons";
import { Unanswered } from "@/components/dashboard/unanswered";
import { AiReadiness } from "@/components/dashboard/ai-readiness";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, ErrorText, Field, Input, PageHeader, Select } from "@/components/ui";
import { Switch } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { ChoiceTile, SaveBar, SettingsCard, ToggleRow, HeaderLink } from "@/components/dashboard/kit";
import { useNavTitle, useSection } from "@/components/dashboard/section";

type Settings = { enabled: boolean; tone: string; maxDiscountPct: number; greeting: string | null; channels: string[]; agentName?: string | null };
const AI_CHANNELS = [["WHATSAPP", "pts.chWhatsapp"], ["FACEBOOK", "pts.chMessenger"], ["INSTAGRAM", "pts.chInstagram"], ["WEB", "pts.chWeb"]] as const;
type Tab = "test" | "lessons" | "channels" | "whatsapp" | "settings";

export default function AiAssistantPage() {
  return <Suspense><AiAssistant /></Suspense>;
}

function AiAssistant() {
  const { t } = useI18n();
  const params = useSearchParams();
  const pendingId = params.get("connect");
  const metaError = params.get("metaError");
  const me = useMe();
  const write = me.canWrite("ai");
  // each part is its own side-menu item; a Facebook/WhatsApp connect coming back lands on the channels part
  const [tab] = useSection<Tab>(write ? ["test", "lessons", "channels", "whatsapp", "settings"] : ["lessons", "channels", "whatsapp", "settings"], pendingId || metaError ? "channels" : write ? "test" : "lessons");
  const title = useNavTitle(t("ai.title"));
  const [teach, setTeach] = useState<{ question: string; answer: string; n: number } | null>(null);

  const send = useCallback((message: string) => api<{ conversationId: string; reply: string }>("store/ai/test", { body: { message } }), []);
  const load = useCallback(() => api<{ role: "user" | "assistant"; content: string }[]>("store/ai/test"), []);
  const reset = useCallback(() => api("store/ai/test", { method: "DELETE" }).then(() => undefined), []);

  return (
    <>
      <PageHeader eyebrow={t("ai.title")} title={title} subtitle={t("ai.subtitle")} actions={<HeaderLink href="/dashboard/inbox" icon="chat">{t("nav.inbox")}</HeaderLink>} />
      {(tab === "test" || tab === "lessons") && <AiReadiness />}
      {tab === "test" && (
        <div className="grid gap-5 lg:grid-cols-2">
          <div>
            <Alert tone="blue">{t("ai.testHint")}</Alert>
            <div className="mt-3 flex h-[32rem] flex-col overflow-hidden rounded-[18px] bg-surface ring-1 ring-line">
              <ChatPanel className="flex-1" greeting={t("chat.greeting")} send={send} load={load} onReset={reset} onTeach={(question, answer) => { setTeach({ question, answer, n: Date.now() }); }} />
            </div>
          </div>
          <div>
            <AiLessons key={teach?.n ?? 0} base="store/ai/lessons" title={t("ai.teachTitle")} canWrite={write} prefill={teach} />
          </div>
        </div>
      )}
      {tab === "lessons" && <div className="max-w-3xl"><AiLessons key={teach?.n ?? 0} base="store/ai/lessons" title={t("ai.teachTitle")} canWrite={write} prefill={teach} /><p className="mt-4 text-xs text-muted">{t("ai.platformLessons")}</p><Unanswered canWrite={write} /></div>}
      {tab === "channels" && <AiChannels write={write} pendingId={pendingId} metaError={metaError} />}
      {tab === "whatsapp" && <AiWhatsApp write={write} />}
      {tab === "settings" && <SettingsForm write={write} />}
    </>
  );
}

function SettingsForm({ write }: { write: boolean }) {
  const { t } = useI18n();
  const toast = useToast();
  const { data, reload } = useApi<Settings>("store/ai/settings");
  const [edit, setEdit] = useState<Partial<Settings>>({});
  const f = data ? { ...data, ...edit } : null;
  const dirty = Object.keys(edit).length > 0;
  const setF = (v: Partial<Settings>) => setEdit({ ...edit, ...v });
  const save = useAction(async () => {
    if (!f) return;
    await api("store/ai/settings", { method: "PUT", body: { enabled: f.enabled, tone: f.tone, maxDiscountPct: f.maxDiscountPct, greeting: f.greeting ?? "", channels: f.channels, agentName: (f.agentName ?? "").trim() } });
    await reload();
    setEdit({});
    toast(t("ai.saved"));
  });
  if (!f) return <div className="max-w-3xl space-y-4"><div className="skeleton h-40 rounded-[18px]" /><div className="skeleton h-56 rounded-[18px]" /></div>;
  const toggle = (c: string) => setF({ channels: f.channels.includes(c) ? f.channels.filter((x) => x !== c) : [...f.channels, c] });
  return (
    <div className="max-w-3xl space-y-5">
      <form onSubmit={(e) => { e.preventDefault(); void save.run(); }} className="space-y-5">
        <fieldset disabled={!write} className="space-y-5 disabled:opacity-80">
          <SettingsCard tone="ai" icon="sparkles" title={t("ai.enabled")} description={t("pts.freeShopHint")} action={<Switch checked={f.enabled} onChange={(v) => setF({ enabled: v })} disabled={!write} label={t("ai.enabled")} />}>
            <div>
              <p className="text-[13px] font-semibold text-ink">{t("pts.channels")}</p>
              <p className="mt-0.5 text-[12.5px] text-muted">{t("pts.channelsHint")}</p>
              <div className="mt-3 grid grid-cols-1 gap-2 sm:grid-cols-2">
                {AI_CHANNELS.map(([c, label]) => <ChoiceTile key={c} on={f.channels.includes(c)} onClick={() => toggle(c)} disabled={!write}>{t(label)}</ChoiceTile>)}
              </div>
            </div>
          </SettingsCard>

          <SettingsCard icon="users" title={t("ai.identityTitle")} description={t("ai.identityHint")}>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label={t("ai.agentName")} hint={t("ai.agentNameHint")}>
                <Input maxLength={30} value={f.agentName ?? ""} onChange={(e) => setF({ agentName: e.target.value })} placeholder={t("ai.agentNamePh")} />
              </Field>
              <Field label={t("ai.tone")}>
                <Select value={f.tone} onChange={(e) => setF({ tone: e.target.value })}>{["FRIENDLY", "FORMAL", "SHORT"].map((x) => <option key={x} value={x}>{t(`ai.tone.${x}`)}</option>)}</Select>
              </Field>
            </div>
            <div className="mt-4"><Field label={t("ai.greeting")}><Input maxLength={300} value={f.greeting ?? ""} onChange={(e) => setF({ greeting: e.target.value })} /></Field></div>
          </SettingsCard>

          <SettingsCard icon="tag" title={t("ai.maxDiscount")} description={t("ai.maxDiscountHint")}>
            <ToggleRow title={t("ai.maxDiscount")} control={
              <div className="relative"><Input className="w-24 pe-8 tnum" type="number" min={0} max={50} value={f.maxDiscountPct} onChange={(e) => setF({ maxDiscountPct: Math.max(0, Math.min(50, Number(e.target.value) || 0)) })} /><span className="pointer-events-none absolute inset-y-0 end-3 grid place-items-center text-xs font-semibold text-faint">%</span></div>
            } />
          </SettingsCard>
        </fieldset>
        <ErrorText error={save.error} />
        {write && <SaveBar dirty={dirty} saving={save.loading} onDiscard={() => setEdit({})} />}
      </form>
      <PointsWallet write={write} />
    </div>
  );
}
