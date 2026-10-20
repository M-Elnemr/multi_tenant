"use client";

import { Suspense, useCallback, useState } from "react";
import { useSearchParams } from "next/navigation";
import { api } from "@/lib/client";
import { ChatPanel } from "@/components/shop/live-chat";
import { AiChannels } from "@/components/dashboard/ai-channels";
import { AiWhatsApp } from "@/components/dashboard/ai-whatsapp";
import { AiLessons } from "@/components/dashboard/ai-lessons";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Button, ErrorText, Field, Input, PageHeader, Select } from "@/components/ui";

type Settings = { enabled: boolean; tone: string; maxDiscountPct: number; greeting: string | null };
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
  const [tab, setTab] = useState<Tab>(pendingId || metaError ? "channels" : write ? "test" : "lessons");
  const [teach, setTeach] = useState<{ question: string; answer: string; n: number } | null>(null);

  const send = useCallback((message: string) => api<{ conversationId: string; reply: string }>("store/ai/test", { body: { message } }), []);
  const load = useCallback(() => api<{ role: "user" | "assistant"; content: string }[]>("store/ai/test"), []);
  const reset = useCallback(() => api("store/ai/test", { method: "DELETE" }).then(() => undefined), []);

  return (
    <>
      <PageHeader title={t("ai.title")} subtitle={t("ai.subtitle")} />
      <div className="mb-4 flex gap-2 border-b">
        {([["test", "ai.tabTest"], ["lessons", "ai.tabLessons"], ["channels", "ai.tabChannels"], ["whatsapp", "wa.tab"], ["settings", "ai.tabSettings"]] as const).filter(([k]) => k !== "test" || write).map(([k, label]) => (
          <button key={k} type="button" onClick={() => setTab(k)} className={`-mb-px border-b-2 px-4 py-2 text-sm font-semibold ${tab === k ? "border-brand text-brand" : "border-transparent text-slate-500"}`}>{t(label)}</button>
        ))}
      </div>
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
      {tab === "lessons" && <div className="max-w-2xl"><AiLessons key={teach?.n ?? 0} base="store/ai/lessons" title={t("ai.teachTitle")} canWrite={write} prefill={teach} /><p className="mt-4 text-xs text-slate-500">{t("ai.platformLessons")}</p></div>}
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
    await api("store/ai/settings", { method: "PUT", body: { enabled: f.enabled, tone: f.tone, maxDiscountPct: f.maxDiscountPct, greeting: f.greeting ?? "" } });
    setSaved(true);
    await reload();
  });
  if (!f) return null;
  return (
    <form onSubmit={(e) => { e.preventDefault(); setSaved(false); void save.run(); }} className="max-w-xl space-y-4 rounded-xl border bg-white p-5">
      <fieldset disabled={!write} className="space-y-4 disabled:opacity-70">
        <label className="flex items-center gap-2 text-sm font-medium"><input type="checkbox" checked={f.enabled} onChange={(e) => setF({ ...f, enabled: e.target.checked })} />{t("ai.enabled")}</label>
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
  );
}
