"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/client";
import { AiLessons } from "@/components/dashboard/ai-lessons";
import { useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Button, ErrorText, Field, Input, Select, Textarea } from "@/components/ui";

type Shop = { id: string; name: string; slug: string };
type Msg = { role: "user" | "assistant"; content: string };
type Draft = { instruction: string; exampleQuestion: string; exampleAnswer: string };
type Check = { expect: string; pass: boolean; why: string };
type EvalResult = { id: number; category: string; transcript: { customer: string; seller: string }[]; checks: Check[]; passed: number; of: number; note: string };
type EvalRun = { status: "RUNNING" | "DONE" | "FAILED"; done: number; total: number; error?: string; results: EvalResult[]; summary?: { overallPct: number; byCategory: Record<string, number> } };

const MODELS = [["default", "GLM 5.3 Flash (default)"], ["qwen", "Alibaba Qwen 3.7 Flash"], ["anthropic", "Claude Haiku 5.5"]] as const;

/** The platform admin plays a customer of any shop, on any model, and turns a bad reply into a lesson (for every shop or for that shop only). */
export function TrainRoom() {
  const { t } = useI18n();
  const shops = useApi<Shop[]>("platform/chat/train/shops");
  const [shopId, setShopId] = useState("");
  const [filter, setFilter] = useState("");
  const [model, setModel] = useState("default");
  const [msgs, setMsgs] = useState<Msg[]>([]);
  const [text, setText] = useState("");
  const [teachAt, setTeachAt] = useState<number | null>(null);
  const [remark, setRemark] = useState("");
  const [draft, setDraft] = useState<Draft | null>(null);
  const [scope, setScope] = useState<"all" | "shop">("all");
  const [saved, setSaved] = useState(false);
  const [lessonsKey, setLessonsKey] = useState(0);
  const endRef = useRef<HTMLDivElement>(null);
  const base = shopId ? `platform/chat/train/${shopId}` : null;

  const pickShop = (id: string) => {
    setShopId(id); setTeachAt(null); setDraft(null); setSaved(false); setMsgs([]);
    if (!id) return;
    api<{ messages: Msg[]; model: string }>(`platform/chat/train/${id}`).then((r) => { setMsgs(r.messages); setModel(r.model || "default"); }).catch(() => undefined);
  };
  useEffect(() => { endRef.current?.scrollIntoView({ block: "end" }); }, [msgs.length]);

  const send = useAction(async (message: string) => {
    if (!base || !message.trim()) return;
    setMsgs((m) => [...m, { role: "user", content: message }]);
    setText("");
    const r = await api<{ reply: string }>(base, { body: { message, model } });
    setMsgs((m) => [...m, { role: "assistant", content: r.reply }]);
  });
  const reset = useAction(async () => { if (!base) return; await api(base, { method: "DELETE" }); setMsgs([]); setTeachAt(null); setDraft(null); });
  const write = useAction(async () => {
    if (teachAt === null) return;
    const customer = [...msgs.slice(0, teachAt)].reverse().find((m) => m.role === "user")?.content ?? "";
    setDraft(await api<Draft>("platform/chat/train/draft-lesson", { body: { customerMessage: customer, agentReply: msgs[teachAt]?.content ?? "", remark } }));
  });
  const save = useAction(async () => {
    if (!draft || !base) return;
    await api(scope === "shop" ? `${base}/lessons` : "platform/chat/lessons", { body: draft });
    setSaved(true); setLessonsKey((k) => k + 1);
  });
  const retry = useAction(async () => {
    if (!base || teachAt === null) return;
    const customer = [...msgs.slice(0, teachAt)].reverse().find((m) => m.role === "user")?.content;
    if (!customer) return;
    await api(base, { method: "DELETE" });
    setMsgs([]); setTeachAt(null); setDraft(null); setSaved(false);
    setMsgs([{ role: "user", content: customer }]);
    const r = await api<{ reply: string }>(base, { body: { message: customer, model } });
    setMsgs([{ role: "user", content: customer }, { role: "assistant", content: r.reply }]);
  });

  const list = (shops.data ?? []).filter((s) => !filter || `${s.name} ${s.slug}`.toLowerCase().includes(filter.toLowerCase()));

  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <div className="space-y-3">
        <Alert tone="blue">{t("train.hint")}</Alert>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label={t("train.shop")}>
            <div className="space-y-1.5">
              <Input value={filter} onChange={(e) => setFilter(e.target.value)} placeholder={t("train.search")} />
              <Select value={shopId} onChange={(e) => pickShop(e.target.value)}>
                <option value="">{t("train.pickShop")}</option>
                {list.map((s) => <option key={s.id} value={s.id}>{s.name} ({s.slug})</option>)}
              </Select>
            </div>
          </Field>
          <Field label={t("train.agent")} hint={t("train.agentHint")}>
            <Select value={model} onChange={(e) => setModel(e.target.value)}>{MODELS.map(([k, l]) => <option key={k} value={k}>{l}</option>)}</Select>
          </Field>
        </div>
        <div className="flex h-[30rem] flex-col overflow-hidden rounded-2xl border bg-white">
          <div className="flex-1 space-y-3 overflow-y-auto p-3">
            {!shopId && <p className="text-sm text-slate-500">{t("train.pickShopFirst")}</p>}
            {msgs.map((m, i) => (
              <div key={i} className={`flex ${m.role === "user" ? "justify-end" : "justify-start"}`}>
                <div className={`max-w-[85%] space-y-1 rounded-2xl px-3 py-2 text-sm ${m.role === "user" ? "bg-brand text-white" : "bg-slate-100 text-slate-800"}`}>
                  <p className="whitespace-pre-wrap">{m.content}</p>
                  {m.role === "assistant" && <button type="button" className="text-xs font-semibold text-brand underline" onClick={() => { setTeachAt(i); setDraft(null); setSaved(false); setRemark(""); }}>{t("train.teach")}</button>}
                </div>
              </div>
            ))}
            {send.loading && <p className="text-xs text-slate-500">…</p>}
            <div ref={endRef} />
          </div>
          <form className="flex gap-2 border-t p-2" onSubmit={(e) => { e.preventDefault(); void send.run(text); }}>
            <Input value={text} onChange={(e) => setText(e.target.value)} placeholder={t("train.type")} disabled={!shopId} />
            <Button type="submit" loading={send.loading} disabled={!shopId || !text.trim()}>{t("train.send")}</Button>
            <Button type="button" variant="secondary" onClick={() => void reset.run()} disabled={!shopId}>{t("train.new")}</Button>
          </form>
        </div>
        <ErrorText error={send.error ?? reset.error} />
      </div>

      <div className="space-y-4">
        {teachAt !== null && (
          <section className="space-y-3 rounded-xl border bg-white p-4 text-sm">
            <h3 className="font-semibold">{t("train.teachTitle")}</h3>
            <p className="rounded-lg bg-slate-50 p-2 text-slate-700">🤖 {msgs[teachAt]?.content}</p>
            <Field label={t("train.remark")} hint={t("train.remarkHint")}><Textarea rows={2} maxLength={800} value={remark} onChange={(e) => setRemark(e.target.value)} /></Field>
            <Button type="button" variant="secondary" loading={write.loading} disabled={!remark.trim()} onClick={() => void write.run()}>{t("train.writeLesson")}</Button>
            {draft && (
              <div className="space-y-3 border-t pt-3">
                <Field label={t("ai.instruction")}><Textarea rows={2} maxLength={1000} value={draft.instruction} onChange={(e) => setDraft({ ...draft, instruction: e.target.value })} /></Field>
                <Field label={t("ai.exQuestion")}><Textarea rows={2} maxLength={1000} value={draft.exampleQuestion} onChange={(e) => setDraft({ ...draft, exampleQuestion: e.target.value })} /></Field>
                <Field label={t("ai.exAnswer")}><Textarea rows={3} maxLength={2000} value={draft.exampleAnswer} onChange={(e) => setDraft({ ...draft, exampleAnswer: e.target.value })} /></Field>
                <div className="flex flex-wrap gap-4">
                  <label className="flex items-center gap-2"><input type="radio" checked={scope === "all"} onChange={() => setScope("all")} />{t("train.scopeAll")}</label>
                  <label className="flex items-center gap-2"><input type="radio" checked={scope === "shop"} onChange={() => setScope("shop")} />{t("train.scopeShop")}</label>
                </div>
                {saved && <Alert tone="green">{t("ai.lessonSaved")}</Alert>}
                <div className="flex flex-wrap gap-2">
                  <Button type="button" loading={save.loading} disabled={saved || !draft.instruction.trim()} onClick={() => void save.run()}>{t("ai.saveLesson")}</Button>
                  {saved && <Button type="button" variant="secondary" loading={retry.loading} onClick={() => void retry.run()}>{t("train.retry")}</Button>}
                </div>
              </div>
            )}
            <ErrorText error={write.error ?? save.error ?? retry.error} />
          </section>
        )}
        {shopId && <EvalPanel shopId={shopId} model={model} />}
        <div>
          <h3 className="mb-2 font-semibold">{scope === "shop" && shopId ? t("train.shopLessons") : t("train.allLessons")}</h3>
          <AiLessons key={`${scope}-${shopId}-${lessonsKey}`} base={scope === "shop" && shopId ? `platform/chat/train/${shopId}/lessons` : "platform/chat/lessons"} title={t("admin.aiTitle")} canWrite={false} prefill={null} />
        </div>
      </div>
    </div>
  );
}

/** Scores the assistant on the golden test conversations for this shop and model; a judge model grades each one. */
function EvalPanel({ shopId, model }: { shopId: string; model: string }) {
  const { t } = useI18n();
  const [run, setRun] = useState<EvalRun | null>(null);
  const [runId, setRunId] = useState<string | null>(null);
  const start = useAction(async () => {
    setRun(null);
    const r = await api<{ id: string }>(`platform/chat/train/${shopId}/eval`, { body: { model } });
    setRunId(r.id);
  });
  useEffect(() => {
    if (!runId) return;
    let stop = false;
    const tick = async () => {
      try {
        const r = await api<EvalRun>(`platform/chat/train/eval/${runId}`);
        if (stop) return;
        setRun(r);
        if (r.status === "RUNNING") setTimeout(tick, 4000);
      } catch { if (!stop) setTimeout(tick, 8000); }
    };
    void tick();
    return () => { stop = true; };
  }, [runId]);
  const running = run?.status === "RUNNING" || (start.loading);
  const failed = (run?.results ?? []).filter((r) => r.checks.some((c) => !c.pass) || r.passed < r.of);

  return (
    <section className="space-y-3 rounded-xl border bg-white p-4 text-sm">
      <h3 className="font-semibold">{t("train.evalTitle")}</h3>
      <p className="text-slate-600">{t("train.evalHint")}</p>
      <Button type="button" variant="secondary" loading={running} disabled={running} onClick={() => void start.run()}>{t("train.evalRun")}</Button>
      {run?.status === "RUNNING" && <p className="text-slate-500">{t("train.evalRunning", { done: run.done, total: run.total })}</p>}
      {run?.status === "FAILED" && <Alert tone="red">{run.error}</Alert>}
      {run?.summary && (
        <div className="space-y-2">
          <p className="font-semibold">{t("train.evalOverall", { pct: run.summary.overallPct })}</p>
          <div className="flex flex-wrap gap-1.5">
            {Object.entries(run.summary.byCategory).map(([k, v]) => (
              <span key={k} className={`rounded-full px-2 py-0.5 text-xs ${v >= 80 ? "bg-emerald-50 text-emerald-700" : v >= 50 ? "bg-amber-50 text-amber-700" : "bg-rose-50 text-rose-700"}`}>{k} {v}%</span>
            ))}
          </div>
        </div>
      )}
      {run?.summary && failed.length > 0 && (
        <details className="rounded-lg border p-2">
          <summary className="cursor-pointer font-semibold">{t("train.evalFailed")} ({failed.length})</summary>
          <div className="mt-2 space-y-3">
            {failed.map((r) => (
              <div key={r.id} className="space-y-1 border-t pt-2">
                <p className="text-xs font-semibold text-slate-500">#{r.id} · {r.category} · {r.passed}/{r.of}</p>
                {r.transcript.map((x, i) => (
                  <div key={i} className="space-y-0.5">
                    <p className="whitespace-pre-wrap">👤 {x.customer}</p>
                    <p className="whitespace-pre-wrap text-slate-700">🤖 {x.seller}</p>
                  </div>
                ))}
                {r.checks.filter((c) => !c.pass).map((c, i) => <p key={i} className="text-xs text-rose-700">✗ {c.expect} — {c.why}</p>)}
                {r.note && <p className="text-xs text-slate-500">{r.note}</p>}
              </div>
            ))}
          </div>
        </details>
      )}
      <ErrorText error={start.error} />
    </section>
  );
}
