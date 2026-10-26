"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { api } from "@/lib/client";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Badge, Button, Card, Empty, ErrorText, Loading, PageHeader, Tabs, Textarea } from "@/components/ui";
import { dateTime } from "@/lib/format";

type Msg = { role: string; content: string; createdAt: string };
type Q = {
  id: number; question: string; status: "OPEN" | "ANSWERED" | "DISMISSED"; answer?: string | null; replyText?: string | null; delivery?: "SENT" | "SAVED" | "FAILED" | null; deliveryNote?: string | null;
  createdAt: string; answeredAt?: string | null; channel?: string | null; customerName?: string | null; customerPhone?: string | null; messages?: Msg[];
};
type Tab = "OPEN" | "ANSWERED" | "DISMISSED";

export default function QuestionsPage() {
  return <Suspense><Questions /></Suspense>;
}

function who(q: Q, guest: string) { return q.customerName ?? q.customerPhone ?? guest; }

function Questions() {
  const { t, locale } = useI18n();
  const me = useMe();
  const write = me.canWrite("ai");
  const params = useSearchParams();
  const [tab, setTab] = useState<Tab>("OPEN");
  const [picked, setPicked] = useState<number | null>(params.get("id") ? Number(params.get("id")) : null);
  const list = useApi<Q[]>(`store/ai/questions?status=${tab}`);
  const counts = useApi<{ open: number }>("store/ai/questions/open-count");
  const items = list.data ?? [];
  const current = picked ?? items[0]?.id ?? null;
  return (
    <>
      <PageHeader title={t("qs.title")} subtitle={t("qs.hint")} />
      <div className="mb-4"><Tabs tabs={(["OPEN", "ANSWERED", "DISMISSED"] as Tab[]).map((k) => ({ key: k, label: t(`qs.tab.${k}`), count: k === "OPEN" ? counts.data?.open : undefined }))} value={tab} onChange={(k) => { setTab(k); setPicked(null); }} /></div>
      <ErrorText error={list.error} />
      {list.loading && !list.data ? <Loading /> : items.length === 0 ? <Empty>{t("qs.none")}</Empty> : (
        <div className="grid gap-4 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
          <ul className="space-y-2">
            {items.map((q) => (
              <li key={q.id}>
                <button type="button" onClick={() => setPicked(q.id)} className={`w-full rounded-2xl border p-3 text-start transition ${current === q.id ? "border-brand bg-brand-soft" : "border-slate-200 bg-white hover:border-slate-300"}`}>
                  <div className="flex items-center justify-between gap-2 text-sm"><span className="font-semibold text-ink">{who(q, t("qs.guest"))}</span><span className="text-xs text-slate-500">{t(`qs.ch.${q.channel ?? "WEB"}`)}</span></div>
                  <p className="mt-1 line-clamp-2 text-sm text-slate-700">{q.question}</p>
                  <p className="mt-1 text-xs text-slate-400">{dateTime(q.createdAt, locale)}</p>
                </button>
              </li>
            ))}
          </ul>
          {current !== null && <Detail key={current} id={current} write={write} onChanged={() => { void list.reload(); void counts.reload(); }} />}
        </div>
      )}
    </>
  );
}

function Detail({ id, write, onChanged }: { id: number; write: boolean; onChanged: () => void }) {
  const { t, locale } = useI18n();
  const d = useApi<Q>(`store/ai/questions/${id}`);
  const [text, setText] = useState("");
  const [copied, setCopied] = useState(false);
  const q = d.data;
  const send = useAction(async () => { await api(`store/ai/questions/${id}/answer`, { body: { answer: text } }); setText(""); await d.reload(); onChanged(); });
  const dismiss = useAction(async () => { await api(`store/ai/questions/${id}/dismiss`, { body: {} }); await d.reload(); onChanged(); });
  const retry = useAction(async () => { await api(`store/ai/questions/${id}/retry`, { body: {} }); await d.reload(); onChanged(); });
  // the assistant words the answer and sends it in the background: follow it until the delivery is known
  const waiting = q?.status === "ANSWERED" && !q.delivery;
  useEffect(() => {
    if (!waiting) return;
    const timer = setInterval(() => { void d.reload(); }, 2500);
    return () => clearInterval(timer);
  }, [waiting, d]);
  if (!q) return d.error ? <ErrorText error={d.error} /> : <Loading />;
  const copy = async () => { try { await navigator.clipboard.writeText(q.replyText ?? ""); setCopied(true); } catch { setCopied(false); } };
  return (
    <Card className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div><p className="font-bold text-ink">{who(q, t("qs.guest"))}</p>{q.customerName && q.customerPhone && <p className="text-xs text-slate-500" dir="ltr">{q.customerPhone}</p>}</div>
        <Badge tone="blue">{t(`qs.ch.${q.channel ?? "WEB"}`)}</Badge>
      </div>
      {(q.messages?.length ?? 0) > 0 && (
        <div>
          <p className="mb-1 text-xs font-semibold uppercase text-slate-500">{t("qs.chat")}</p>
          <div className="max-h-64 space-y-1.5 overflow-y-auto rounded-xl bg-slate-50 p-3 text-sm">
            {q.messages!.map((m, i) => (
              <div key={i} className={`flex ${m.role === "user" ? "justify-start" : "justify-end"}`}>
                <span className={`max-w-[85%] whitespace-pre-wrap rounded-2xl px-3 py-1.5 ${m.role === "user" ? "bg-white text-ink shadow-sm" : "bg-brand-soft text-ink"}`}>{m.content}</span>
              </div>
            ))}
          </div>
        </div>
      )}
      <div className="rounded-xl border border-amber-200 bg-amber-50 p-3">
        <p className="text-xs font-semibold uppercase text-amber-700">{t("qs.question")}</p>
        <p className="mt-1 text-sm font-medium text-ink">{q.question}</p>
        <p className="mt-1 text-xs text-slate-400">{dateTime(q.createdAt, locale)}</p>
      </div>
      <ErrorText error={send.error ?? dismiss.error ?? retry.error} />
      {q.status === "OPEN" && write && (
        <div className="space-y-2">
          <p className="text-sm font-semibold text-ink">{t("qs.yourAnswer")}</p>
          <Textarea rows={4} value={text} maxLength={1500} onChange={(e) => setText(e.target.value)} placeholder={t("qs.placeholder")} />
          <p className="text-xs text-slate-500">{t("qs.sendHint")}</p>
          <div className="flex flex-wrap gap-2">
            <Button loading={send.loading} disabled={text.trim().length === 0} onClick={() => send.run()}>{t("qs.send")}</Button>
            <Button variant="ghost" loading={dismiss.loading} onClick={() => dismiss.run()}>{t("qs.dismiss")}</Button>
          </div>
        </div>
      )}
      {q.status === "ANSWERED" && (
        <div className="space-y-2">
          <div><p className="text-xs font-semibold uppercase text-slate-500">{t("qs.yourAnswer")}</p><p className="mt-1 whitespace-pre-wrap text-sm text-ink">{q.answer}</p></div>
          {waiting ? <Alert tone="blue">{t("qs.delivery.PENDING")}</Alert> : q.delivery && (
            <Alert tone={q.delivery === "FAILED" ? "red" : q.delivery === "SAVED" ? "amber" : "green"}>
              {t(`qs.delivery.${q.delivery}`)}{q.delivery === "FAILED" && q.deliveryNote ? ` ${q.deliveryNote}` : ""}
            </Alert>
          )}
          {q.replyText && (
            <div><p className="text-xs font-semibold uppercase text-slate-500">{t("qs.received")}</p><p className="mt-1 whitespace-pre-wrap rounded-xl bg-brand-soft p-3 text-sm text-ink">{q.replyText}</p></div>
          )}
          {q.delivery === "FAILED" && (
            <div className="flex flex-wrap gap-2">
              <Button variant="ghost" onClick={copy}>{copied ? t("qs.copied") : t("qs.copy")}</Button>
              {write && <Button loading={retry.loading} onClick={() => retry.run()}>{t("qs.retry")}</Button>}
            </div>
          )}
        </div>
      )}
    </Card>
  );
}
