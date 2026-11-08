"use client";

import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { Suspense, useEffect, useState, useSyncExternalStore } from "react";
import { DateRangePicker, rangeQuery, type DateRange } from "@/components/date-range";
import { useSearchParams } from "next/navigation";
import { api } from "@/lib/client";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { ChannelIcon } from "@/components/channel-icons";
import { Icon } from "@/components/icons";
import { Sheet } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { Alert, Avatar, Button, Empty, ErrorText, PageHeader, Textarea } from "@/components/ui";
import { useNavTitle } from "@/components/dashboard/section";
import { AI_SOFT, errText, PillTabs } from "@/components/dashboard/marketing/kit";
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

const wideQuery = "(min-width: 1024px)";
function useWide() {
  return useSyncExternalStore((cb) => { const m = window.matchMedia(wideQuery); m.addEventListener("change", cb); return () => m.removeEventListener("change", cb); },
    () => window.matchMedia(wideQuery).matches, () => true);
}

/** Questions the AI seller could not answer: the owner answers once, the assistant words it and sends it to the customer. */
function Questions() {
  const { t, locale } = useI18n();
  const me = useMe();
  const write = me.canWrite("ai");
  const params = useSearchParams();
  const wide = useWide();
  const reduce = useReducedMotion();
  const title = useNavTitle(t("qs.title"));
  const [tab, setTab] = useState<Tab>("OPEN");
  const [picked, setPicked] = useState<number | null>(params.get("id") ? Number(params.get("id")) : null);
  const [range, setRange] = useState<DateRange>({});
  const rq = (b: string) => { const qs = rangeQuery(new URLSearchParams(), range).toString(); return qs ? `${b}${b.includes("?") ? "&" : "?"}${qs}` : b; };
  const list = useApi<Q[]>(rq(`store/ai/questions?status=${tab}`));
  const counts = useApi<{ open: number }>("store/ai/questions/open-count");
  const items = list.data ?? [];
  const current = picked ?? (wide ? items[0]?.id ?? null : null);
  const changed = () => { void list.reload(); void counts.reload(); };
  return (
    <>
      <PageHeader eyebrow={t("navgrp.inbox")} title={title} subtitle={t("qs.hint")} actions={<DateRangePicker value={range} onChange={setRange} />} />
      <div className="mb-5">
        <PillTabs id="qs" value={tab} onChange={(k) => { setTab(k); setPicked(null); }}
          tabs={(["OPEN", "ANSWERED", "DISMISSED"] as Tab[]).map((k) => ({ key: k, label: t(`qs.tab.${k}`), count: k === "OPEN" ? counts.data?.open : undefined }))} />
      </div>
      <ErrorText error={list.error} />
      {list.loading && !list.data ? (
        <div className="grid gap-4 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
          <div className="space-y-2">{[0, 1, 2, 3].map((i) => <div key={i} className="skeleton h-24 rounded-[16px]" style={{ opacity: 1 - i * 0.15 }} />)}</div>
          <div className="skeleton hidden h-96 rounded-[18px] lg:block" />
        </div>
      ) : items.length === 0 ? (
        <Empty icon="mail" title={t("qs.none")}>{tab === "OPEN" ? t("qsx.emptyOpen") : t("qsx.emptyOther")}</Empty>
      ) : (
        <div className="grid gap-4 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)] lg:items-start">
          <ul className="space-y-2">
            <AnimatePresence initial={false}>
              {items.map((q) => {
                const on = current === q.id;
                return (
                  <motion.li key={q.id} layout={!reduce} initial={reduce ? false : { opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }}>
                    <button type="button" onClick={() => setPicked(q.id)} aria-current={on || undefined}
                      className={`w-full rounded-[16px] p-3.5 text-start ring-1 transition ${on ? "bg-surface ring-2 ring-brand shadow-[0_6px_20px_-12px_var(--brand)]" : "bg-surface ring-line hover:ring-line-strong"}`}>
                      <div className="flex items-center gap-2.5">
                        <Avatar name={who(q, t("qs.guest"))} className="h-8 w-8 text-[12px]" />
                        <span className="min-w-0 flex-1 truncate text-[13.5px] font-semibold text-ink">{who(q, t("qs.guest"))}</span>
                        <span className="inline-flex shrink-0 items-center gap-1 rounded-full bg-canvas px-2 py-0.5 text-[11px] font-medium text-muted ring-1 ring-line"><ChannelIcon channel={q.channel ?? "WEB"} className="h-3 w-3" />{t(`qs.ch.${q.channel ?? "WEB"}`)}</span>
                      </div>
                      <p className="mt-2 line-clamp-2 text-[13px] leading-relaxed text-ink-2">{q.question}</p>
                      <p className="mt-1.5 text-[11.5px] text-faint">{dateTime(q.createdAt, locale)}</p>
                    </button>
                  </motion.li>
                );
              })}
            </AnimatePresence>
          </ul>
          {wide && current !== null && <div className="sticky top-4"><Detail key={current} id={current} write={write} onChanged={changed} /></div>}
        </div>
      )}
      {!wide && (
        <Sheet open={picked !== null} onClose={() => setPicked(null)} title={t("qs.question")}>
          {picked !== null && <Detail key={picked} id={picked} write={write} onChanged={changed} bare />}
        </Sheet>
      )}
    </>
  );
}

function Detail({ id, write, onChanged, bare }: { id: number; write: boolean; onChanged: () => void; bare?: boolean }) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const d = useApi<Q>(`store/ai/questions/${id}`);
  const [text, setText] = useState("");
  const [copied, setCopied] = useState(false);
  const q = d.data;
  const send = useAction(async () => { await api(`store/ai/questions/${id}/answer`, { body: { answer: text } }); setText(""); toast(t("qsx.sent")); await d.reload(); onChanged(); });
  const dismiss = useAction(async () => { await api(`store/ai/questions/${id}/dismiss`, { body: {} }); toast(t("qsx.dismissed")); await d.reload(); onChanged(); });
  const retry = useAction(async () => { await api(`store/ai/questions/${id}/retry`, { body: {} }); await d.reload(); onChanged(); });
  // the assistant words the answer and sends it in the background: follow it until the delivery is known
  const waiting = q?.status === "ANSWERED" && !q.delivery;
  useEffect(() => {
    if (!waiting) return;
    const timer = setInterval(() => { void d.reload(); }, 2500);
    return () => clearInterval(timer);
  }, [waiting, d]);
  const err = send.error ?? dismiss.error ?? retry.error;
  useEffect(() => { if (err) toast(errText(err, t("inbox.saveFailed")), { tone: "error" }); }, [err, toast, t]);
  if (!q) return d.error ? <ErrorText error={d.error} /> : <div className="skeleton h-80 rounded-[18px]" />;
  const copy = async () => { try { await navigator.clipboard.writeText(q.replyText ?? ""); setCopied(true); } catch { setCopied(false); } };
  return (
    <div className={bare ? "space-y-4" : "space-y-4 rounded-[18px] bg-surface p-5 ring-1 ring-line"}>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex min-w-0 items-center gap-3">
          <Avatar name={who(q, t("qs.guest"))} className="h-10 w-10 text-sm" />
          <div className="min-w-0"><p className="truncate font-display text-[15px] font-semibold text-ink">{who(q, t("qs.guest"))}</p>{q.customerName && q.customerPhone && <p className="text-[12px] tnum text-muted" dir="ltr">{q.customerPhone}</p>}</div>
        </div>
        <span className="inline-flex items-center gap-1.5 rounded-full bg-canvas px-2.5 py-1 text-[12px] font-medium text-ink-2 ring-1 ring-line"><ChannelIcon channel={q.channel ?? "WEB"} className="h-3.5 w-3.5" />{t(`qs.ch.${q.channel ?? "WEB"}`)}</span>
      </div>
      {(q.messages?.length ?? 0) > 0 && (
        <div>
          <p className="mb-1.5 text-[11.5px] font-semibold uppercase tracking-wide text-faint">{t("qs.chat")}</p>
          <div className="max-h-64 space-y-1.5 overflow-y-auto rounded-[14px] bg-canvas p-3 text-[13px] ring-1 ring-line">
            {q.messages!.map((m, i) => (
              <div key={i} className={`flex ${m.role === "user" ? "justify-start" : "justify-end"}`}>
                <span className={`max-w-[85%] whitespace-pre-wrap rounded-[14px] px-3 py-1.5 ${m.role === "user" ? "bg-surface text-ink ring-1 ring-line" : `${AI_SOFT} text-ink ring-1 ring-violet-200/60`}`}>{m.content}</span>
              </div>
            ))}
          </div>
        </div>
      )}
      <div className="rounded-[14px] bg-amber-50 p-3.5 ring-1 ring-inset ring-amber-600/20">
        <p className="flex items-center gap-1.5 text-[11.5px] font-semibold uppercase tracking-wide text-amber-800"><Icon name="mail" className="h-3.5 w-3.5" />{t("qs.question")}</p>
        <p className="mt-1.5 text-[14px] font-medium leading-relaxed text-ink">{q.question}</p>
        <p className="mt-1 text-[11.5px] text-amber-800/70">{dateTime(q.createdAt, locale)}</p>
      </div>
      {q.status === "OPEN" && write && (
        <div className="space-y-2">
          <p className="text-[13px] font-semibold text-ink">{t("qs.yourAnswer")}</p>
          <Textarea rows={4} value={text} maxLength={1500} onChange={(e) => setText(e.target.value)} placeholder={t("qs.placeholder")} />
          <p className="flex items-start gap-1.5 text-[12px] text-muted"><Icon name="sparkles" className="mt-0.5 h-3.5 w-3.5 shrink-0 text-violet-500" />{t("qs.sendHint")}</p>
          <div className="flex flex-wrap justify-end gap-2">
            <Button variant="ghost" loading={dismiss.loading} onClick={() => dismiss.run()}>{t("qs.dismiss")}</Button>
            <Button variant="ink" loading={send.loading} disabled={text.trim().length === 0} onClick={() => send.run()}><Icon name="send" className="h-4 w-4 flip-rtl" />{t("qs.send")}</Button>
          </div>
        </div>
      )}
      {q.status === "ANSWERED" && (
        <div className="space-y-3">
          <div><p className="text-[11.5px] font-semibold uppercase tracking-wide text-faint">{t("qs.yourAnswer")}</p><p className="mt-1 whitespace-pre-wrap text-[13.5px] text-ink">{q.answer}</p></div>
          {waiting ? <Alert tone="blue">{t("qs.delivery.PENDING")}</Alert> : q.delivery && (
            <Alert tone={q.delivery === "FAILED" ? "red" : q.delivery === "SAVED" ? "amber" : "green"}>
              {t(`qs.delivery.${q.delivery}`)}{q.delivery === "FAILED" && q.deliveryNote ? ` ${q.deliveryNote}` : ""}
            </Alert>
          )}
          {q.replyText && (
            <div><p className="text-[11.5px] font-semibold uppercase tracking-wide text-faint">{t("qs.received")}</p><p className={`mt-1 whitespace-pre-wrap rounded-[14px] p-3 text-[13.5px] text-ink ring-1 ring-violet-200/60 ${AI_SOFT}`}>{q.replyText}</p></div>
          )}
          {q.delivery === "FAILED" && (
            <div className="flex flex-wrap gap-2">
              <Button variant="secondary" onClick={copy}><Icon name={copied ? "check" : "file"} className="h-4 w-4" />{copied ? t("qs.copied") : t("qs.copy")}</Button>
              {write && <Button loading={retry.loading} onClick={() => retry.run()}>{t("qs.retry")}</Button>}
            </div>
          )}
        </div>
      )}
      {q.status === "DISMISSED" && <p className="rounded-[12px] bg-canvas p-3 text-[13px] text-muted ring-1 ring-line">{t("qs.tab.DISMISSED")}</p>}
    </div>
  );
}
