"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import { useSearchParams } from "next/navigation";
import { api } from "@/lib/client";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Badge, Button, Card, Empty, ErrorText, Loading, PageHeader, Tabs, Textarea } from "@/components/ui";
import { dateTime } from "@/lib/format";

type Msg = { id: number; role: string; content: string; byOwner: boolean; createdAt: string };
type Chat = {
  id: string; channel: string; customerName?: string | null; customerPhone?: string | null; lastMessage?: string | null; lastFromCustomer: boolean; updatedAt: string;
  needsHuman: boolean; needsHumanReason?: string | null; aiPaused: boolean; aiPausedUntil?: string | null; lastOrder?: string | null; messages?: Msg[];
  delivery?: "SENT" | "SAVED" | "FAILED"; deliveryNote?: string | null;
};
type Tab = "NEEDS" | "ALL";

export default function ConversationsPage() {
  return <Suspense><Inbox /></Suspense>;
}

function who(c: Chat, guest: string) { return c.customerName ?? c.customerPhone ?? guest; }

function Inbox() {
  const { t, locale } = useI18n();
  const me = useMe();
  const write = me.canWrite("ai");
  const params = useSearchParams();
  const [tab, setTab] = useState<Tab>(params.get("id") ? "ALL" : "NEEDS");
  const [picked, setPicked] = useState<string | null>(params.get("id"));
  const list = useApi<Chat[]>(`store/chat/conversations?needsYou=${tab === "NEEDS"}`);
  const count = useApi<{ count: number }>("store/chat/conversations/needs-you-count");
  // new customer messages arrive while the page is open
  useEffect(() => {
    const timer = setInterval(() => { void list.reload(); void count.reload(); }, 15000);
    return () => clearInterval(timer);
  }, [list, count]);
  const items = list.data ?? [];
  const current = picked ?? items[0]?.id ?? null;
  return (
    <>
      <PageHeader title={t("inbox.title")} subtitle={t("inbox.hint")} />
      <div className="mb-4"><Tabs tabs={[{ key: "NEEDS" as Tab, label: t("inbox.tab.NEEDS"), count: count.data?.count }, { key: "ALL" as Tab, label: t("inbox.tab.ALL") }]} value={tab} onChange={(k) => { setTab(k); setPicked(null); }} /></div>
      <ErrorText error={list.error} />
      {list.loading && !list.data ? <Loading /> : items.length === 0 ? <Empty>{tab === "NEEDS" ? t("inbox.noneNeeds") : t("inbox.none")}</Empty> : (
        <div className="grid gap-4 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
          <ul className="space-y-2">
            {items.map((c) => (
              <li key={c.id}>
                <button type="button" onClick={() => setPicked(c.id)} className={`w-full rounded-2xl border p-3 text-start transition ${current === c.id ? "border-brand bg-brand-soft" : "border-slate-200 bg-white hover:border-slate-300"}`}>
                  <div className="flex items-center justify-between gap-2 text-sm">
                    <span className="font-semibold text-ink">{who(c, t("qs.guest"))}</span>
                    <span className="flex items-center gap-1.5">
                      {c.needsHuman && <Badge tone="red">{t("inbox.needs")}</Badge>}
                      {c.aiPaused && <Badge tone="amber">{t("inbox.paused")}</Badge>}
                      <span className="text-xs text-slate-500">{t(`qs.ch.${c.channel}`)}</span>
                    </span>
                  </div>
                  {c.needsHuman && c.needsHumanReason && <p className="mt-1 text-xs font-medium text-red-700">{c.needsHumanReason}</p>}
                  <p className="mt-1 line-clamp-2 text-sm text-slate-700">{c.lastFromCustomer ? "" : `${t("inbox.you")}: `}{c.lastMessage}</p>
                  <p className="mt-1 text-xs text-slate-400">{dateTime(c.updatedAt, locale)}</p>
                </button>
              </li>
            ))}
          </ul>
          {current !== null && <Detail key={current} id={current} write={write} onChanged={() => { void list.reload(); void count.reload(); }} />}
        </div>
      )}
    </>
  );
}

function Detail({ id, write, onChanged }: { id: string; write: boolean; onChanged: () => void }) {
  const { t, locale } = useI18n();
  const d = useApi<Chat>(`store/chat/conversations/${id}`);
  const [text, setText] = useState("");
  const [sent, setSent] = useState<Chat | null>(null);
  const bottom = useRef<HTMLDivElement>(null);
  const c = d.data;
  const reply = useAction(async () => { const r = await api<Chat>(`store/chat/conversations/${id}/reply`, { body: { message: text } }); setText(""); setSent(r); await d.reload(); onChanged(); });
  const pause = useAction(async () => { await api(`store/chat/conversations/${id}/pause`, { body: { hours: 2 } }); await d.reload(); onChanged(); });
  const resume = useAction(async () => { await api(`store/chat/conversations/${id}/resume`, { body: {} }); await d.reload(); onChanged(); });
  const done = useAction(async () => { await api(`store/chat/conversations/${id}/done`, { body: {} }); await d.reload(); onChanged(); });
  useEffect(() => {
    const timer = setInterval(() => { void d.reload(); }, 8000);
    return () => clearInterval(timer);
  }, [d]);
  useEffect(() => { bottom.current?.scrollIntoView({ block: "nearest" }); }, [c?.messages?.length]);
  if (!c) return d.error ? <ErrorText error={d.error} /> : <Loading />;
  return (
    <Card className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <p className="font-bold text-ink">{who(c, t("qs.guest"))}</p>
          {c.customerPhone && <p className="text-xs text-slate-500" dir="ltr">{c.customerPhone}</p>}
          {c.lastOrder && <p className="text-xs text-slate-500">{t("inbox.lastOrder")}: {c.lastOrder}</p>}
        </div>
        <Badge tone="blue">{t(`qs.ch.${c.channel}`)}</Badge>
      </div>
      {c.needsHuman && <Alert tone="red">{t("inbox.needsBanner")} {c.needsHumanReason}</Alert>}
      {c.aiPaused ? <Alert tone="amber">{t("inbox.pausedBanner")} {c.aiPausedUntil ? dateTime(c.aiPausedUntil, locale) : ""}</Alert> : <Alert tone="green">{t("inbox.aiOn")}</Alert>}
      <div className="max-h-[28rem] space-y-1.5 overflow-y-auto rounded-xl bg-slate-50 p-3 text-sm">
        {(c.messages ?? []).map((m) => (
          <div key={m.id} className={`flex ${m.role === "user" ? "justify-start" : "justify-end"}`}>
            <div className={`max-w-[85%] rounded-2xl px-3 py-1.5 ${m.role === "user" ? "bg-white text-ink shadow-sm" : m.byOwner ? "bg-amber-100 text-ink" : "bg-brand-soft text-ink"}`}>
              <p className="text-[11px] font-semibold text-slate-500">{m.role === "user" ? t("inbox.customer") : m.byOwner ? t("inbox.you") : t("inbox.assistant")}</p>
              <p className="whitespace-pre-wrap">{m.content}</p>
              <p className="mt-0.5 text-[10px] text-slate-400">{dateTime(m.createdAt, locale)}</p>
            </div>
          </div>
        ))}
        <div ref={bottom} />
      </div>
      <ErrorText error={reply.error ?? pause.error ?? resume.error ?? done.error} />
      {sent?.delivery && (
        <Alert tone={sent.delivery === "FAILED" ? "red" : sent.delivery === "SAVED" ? "amber" : "green"}>
          {t(`qs.delivery.${sent.delivery}`)}{sent.delivery === "FAILED" && sent.deliveryNote ? ` ${sent.deliveryNote}` : ""}
        </Alert>
      )}
      {write && (
        <div className="space-y-2">
          <Textarea rows={3} value={text} maxLength={2000} onChange={(e) => setText(e.target.value)} placeholder={t("inbox.placeholder")} />
          <p className="text-xs text-slate-500">{t("inbox.sendHint")}</p>
          <div className="flex flex-wrap gap-2">
            <Button loading={reply.loading} disabled={text.trim().length === 0} onClick={() => reply.run()}>{t("inbox.send")}</Button>
            {c.aiPaused
              ? <Button variant="ghost" loading={resume.loading} onClick={() => resume.run()}>{t("inbox.resume")}</Button>
              : <Button variant="ghost" loading={pause.loading} onClick={() => pause.run()}>{t("inbox.pause")}</Button>}
            {c.needsHuman && <Button variant="ghost" loading={done.loading} onClick={() => done.run()}>{t("inbox.done")}</Button>}
          </div>
        </div>
      )}
    </Card>
  );
}
