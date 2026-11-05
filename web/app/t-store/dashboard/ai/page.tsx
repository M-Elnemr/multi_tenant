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
import { Alert, Button, ErrorText, Field, Input, PageHeader, Select } from "@/components/ui";
import { useNavTitle, useSection } from "@/components/dashboard/section";

type Settings = { enabled: boolean; tone: string; maxDiscountPct: number; greeting: string | null; channels: string[] };
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
      <PageHeader title={title} subtitle={t("ai.subtitle")} />
      {(tab === "test" || tab === "lessons") && <AiReadiness />}
      {tab === "test" && (
        <div className="grid gap-4 lg:grid-cols-2">
          <div>
            <Alert tone="blue">{t("ai.testHint")}</Alert>
            <div className="mt-3 flex h-[32rem] flex-col overflow-hidden rounded-2xl border bg-white">
              <ChatPanel className="flex-1" greeting={t("chat.greeting")} send={send} load={load} onReset={reset} onTeach={(question, answer) => { setTeach({ question, answer, n: Date.now() }); }} />
            </div>
          </div>
          <div>
            <AiLessons key={teach?.n ?? 0} base="store/ai/lessons" title={t("ai.teachTitle")} canWrite={write} prefill={teach} />
          </div>
        </div>
      )}
      {tab === "lessons" && <div className="max-w-2xl"><AiLessons key={teach?.n ?? 0} base="store/ai/lessons" title={t("ai.teachTitle")} canWrite={write} prefill={teach} /><p className="mt-4 text-xs text-slate-500">{t("ai.platformLessons")}</p><Unanswered canWrite={write} /></div>}
      {tab === "channels" && <AiChannels write={write} pendingId={pendingId} metaError={metaError} />}
      {tab === "whatsapp" && <AiWhatsApp write={write} />}
      {tab === "settings" && <SettingsForm write={write} />}
    </>
  );
}

function SettingsForm({ write }: { write: boolean }) {
  const { t } = useI18n();
  const { data, reload } = useApi<Settings>("store/ai/settings");
  const [edit, setEdit] = useState<Partial<Settings>>({});
  const [saved, setSaved] = useState(false);
  const f = data ? { ...data, ...edit } : null;
  const setF = (v: Settings) => setEdit(v);
  const save = useAction(async () => {
    if (!f) return;
    await api("store/ai/settings", { method: "PUT", body: { enabled: f.enabled, tone: f.tone, maxDiscountPct: f.maxDiscountPct, greeting: f.greeting ?? "", channels: f.channels } });
    setSaved(true);
    await reload();
  });
  if (!f) return null;
  const toggle = (c: string) => setF({ ...f, channels: f.channels.includes(c) ? f.channels.filter((x) => x !== c) : [...f.channels, c] });
  return (
    <div className="max-w-xl space-y-4">
    <form onSubmit={(e) => { e.preventDefault(); setSaved(false); void save.run(); }} className="space-y-4 rounded-xl border bg-white p-5">
      <fieldset disabled={!write} className="space-y-4 disabled:opacity-70">
        <label className="flex items-center gap-2 text-sm font-medium"><input type="checkbox" checked={f.enabled} onChange={(e) => setF({ ...f, enabled: e.target.checked })} />{t("ai.enabled")}</label>
        <div>
          <p className="text-sm font-medium">{t("pts.channels")}</p>
          <p className="mt-0.5 text-xs text-slate-500">{t("pts.channelsHint")}</p>
          <div className="mt-2 grid grid-cols-2 gap-2">
            {AI_CHANNELS.map(([c, label]) => (
              <label key={c} className={`flex cursor-pointer items-center gap-2 rounded-lg border px-3 py-2 text-sm font-medium transition ${f.channels.includes(c) ? "border-brand bg-brand-soft text-brand" : "border-slate-200"}`}>
                <input type="checkbox" checked={f.channels.includes(c)} onChange={() => toggle(c)} />{t(label)}
              </label>
            ))}
          </div>
        </div>
        <Field label={t("ai.tone")}>
          <Select value={f.tone} onChange={(e) => setF({ ...f, tone: e.target.value })}>{["FRIENDLY", "FORMAL", "SHORT"].map((x) => <option key={x} value={x}>{t(`ai.tone.${x}`)}</option>)}</Select>
        </Field>
        <Field label={t("ai.maxDiscount")} hint={t("ai.maxDiscountHint")}>
          <Input type="number" min={0} max={50} value={f.maxDiscountPct} onChange={(e) => setF({ ...f, maxDiscountPct: Math.max(0, Math.min(50, Number(e.target.value) || 0)) })} />
        </Field>
        <Field label={t("ai.greeting")}><Input maxLength={300} value={f.greeting ?? ""} onChange={(e) => setF({ ...f, greeting: e.target.value })} /></Field>
      </fieldset>
      <ErrorText error={save.error} />
      {saved && <Alert tone="green">{t("ai.saved")}</Alert>}
      {write && <Button type="submit" loading={save.loading}>{t("common.save")}</Button>}
    </form>
    <PointsWallet write={write} />
    </div>
  );
}
