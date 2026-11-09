"use client";

import { motion, useReducedMotion } from "motion/react";
import { useState } from "react";
import { api } from "@/lib/client";
import { dateTime, money, timeOnly } from "@/lib/format";
import { ChannelIcon } from "../../channel-icons";
import { useAction, useApi } from "../../hooks";
import { useI18n } from "../../i18n-provider";
import { Icon } from "../../icons";
import { ConfirmDialog } from "../../overlay";
import { useToast } from "../../toast";
import { Badge, Button, Empty, ErrorText } from "../../ui";
import { Bar, Panel, PanelHead } from "../marketing/kit";
import type { Broadcast, Recipient, RecipientsRes, RecipientStatus } from "./types";
import { usePoll } from "./use-poll";

const PAGE = 100;

/** Three small dots that pulse in turn; static when the person asked for reduced motion. */
function LoadingDots() {
  const reduce = useReducedMotion();
  return (
    <span className="inline-flex items-center gap-0.5" aria-hidden>
      {[0, 1, 2].map((i) => (
        <motion.span key={i} className="h-1 w-1 rounded-full bg-current" animate={reduce ? undefined : { opacity: [0.25, 1, 0.25] }} transition={reduce ? undefined : { duration: 1.1, repeat: Infinity, delay: i * 0.18 }} />
      ))}
    </span>
  );
}

function RecipientRow({ r }: { r: Recipient }) {
  const { t, locale } = useI18n();
  const tone: Record<RecipientStatus, string> = {
    SENDING: "bg-amber-100 text-amber-950 ring-1 ring-amber-500/40 dark:bg-amber-400/15 dark:text-amber-100",
    SENT: "bg-emerald-50 text-emerald-900 dark:bg-emerald-400/10 dark:text-emerald-100",
    FAILED: "bg-red-50 text-red-900 dark:bg-red-400/10 dark:text-red-100",
    PENDING: "bg-surface-2 text-ink-2",
    STOPPED: "bg-surface-2 text-muted",
    SKIPPED: "text-muted opacity-80",
  };
  return (
    <li className={`flex items-center gap-2.5 rounded-[10px] px-3 py-2 text-[13px] ${tone[r.status]}`}>
      <ChannelIcon channel={r.channel} className="h-3.5 w-3.5 shrink-0" />
      <span className="min-w-0 flex-1 truncate font-medium">{r.name || (r.phone ? <span dir="ltr">{r.phone}</span> : "-")}</span>
      {r.status === "SENDING" && <span className="inline-flex shrink-0 items-center gap-1.5 text-[12px] font-semibold">{t("gx.rs.SENDING")}<LoadingDots /></span>}
      {r.status === "SENT" && <span className="inline-flex shrink-0 items-center gap-1 text-[12px] tnum"><Icon name="check" className="h-3.5 w-3.5 text-emerald-600" />{r.sentAt ? timeOnly(r.sentAt, locale) : t("gx.rs.SENT")}</span>}
      {r.status === "FAILED" && <span className="inline-flex min-w-0 shrink-0 items-center gap-1 text-[12px]"><Icon name="xcircle" className="h-3.5 w-3.5 shrink-0" /><span className="truncate">{r.error || t("gx.rs.FAILED")}</span></span>}
      {(r.status === "PENDING" || r.status === "STOPPED") && <span className="shrink-0 text-[12px]">{t(`gx.rs.${r.status}`)}</span>}
      {r.status === "SKIPPED" && <span className="shrink-0 text-[12px]">{r.error ? (["OUTSIDE_WINDOW", "NO_OWN_NUMBER", "OPTED_OUT", "NO_PHONE"].includes(r.error) ? t(`gx.reason.${r.error}`) : r.error) : t("gx.rs.SKIPPED")}</span>}
    </li>
  );
}

function BroadcastCard({ b, balanceMinor, open, onOpen, onResume, onCancel, busy, children }: {
  b: Broadcast; balanceMinor: number; open: boolean; onOpen: () => void; onResume: () => void; onCancel: () => void; busy: boolean; children?: React.ReactNode;
}) {
  const { t, locale, currency } = useI18n();
  const n = (x: number) => x.toLocaleString(locale === "ar" ? "ar-EG" : "en-US");
  const live = b.status === "SENDING";
  return (
    <li className={`space-y-3 rounded-[16px] p-4 ring-1 ${live ? "bg-surface ring-2 ring-amber-400/60" : "bg-surface ring-line"}`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="flex flex-wrap items-center gap-1.5">
          {b.status === "SENDING" && <Badge tone="amber">{t("gx.bs.SENDING")}</Badge>}
          {b.status === "DONE" && <Badge tone="green">{t("gx.bs.DONE")}</Badge>}
          {b.status === "PAUSED" && <Badge tone="red">{t("gr.state.PAUSED")}</Badge>}
          {b.status === "CANCELED" && <Badge tone="slate">{t("gr.state.CANCELED")}</Badge>}
          {b.segments.map((s) => <Badge key={s} tone="blue">{t(`gr.seg.${s}`)}</Badge>)}
          {b.platforms.map((p) => <span key={p} className="inline-flex" title={t(`gx.plat.${p}`)}><ChannelIcon channel={p} className="h-4 w-4" /></span>)}
        </span>
        <span className="text-[12px] text-muted tnum">{dateTime(b.createdAt, locale)}</span>
      </div>
      <p className="line-clamp-2 text-[13px] text-ink-2">{b.message}</p>
      <div className="space-y-1.5">
        <Bar value={b.sentCount} max={Math.max(1, b.audience)} tone="green" />
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-[12px] text-muted tnum">
          <span><b className="text-ink">{n(b.sentCount)}</b> / {n(b.audience)} {t("gx.cSent")}</span>
          {b.pendingCount > 0 && <span>{n(b.pendingCount)} {t("gx.cPending")}</span>}
          {b.failedCount > 0 && <span className="text-red-600">{n(b.failedCount)} {t("gx.cFailed")}</span>}
          {b.skippedOutsideWindow > 0 && <span>{n(b.skippedOutsideWindow)} {t("gx.cSkipped")}</span>}
          {live && b.etaMinutes > 0 && <span className="font-semibold text-ink-2">{t("gx.eta", { n: n(b.etaMinutes) })}</span>}
        </div>
      </div>
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-[12px] text-muted tnum">
        <span>{t("gx.costSoFar")}: <b className="text-ink">{money(b.chargedMinor, currency, locale)}</b></span>
        {(live || b.status === "PAUSED") && <span>{t("gx.sumBalance")}: <b className="text-ink">{money(balanceMinor, currency, locale)}</b></span>}
        {b.pauseReason && <span className="text-amber-700">{t(`gr.pause.${b.pauseReason}`)}</span>}
      </div>
      <div className="flex flex-wrap gap-2">
        {b.status === "PAUSED" && <Button size="sm" variant="ink" loading={busy} onClick={onResume}>{t("gr.resume")}</Button>}
        {(b.status === "PAUSED" || b.status === "SENDING") && <Button size="sm" variant="ghost" onClick={onCancel}>{t("gr.cancel")}</Button>}
        <Button size="sm" variant="secondary" className="ms-auto" onClick={onOpen}>{open ? t("gx.hideRecipients") : t("gx.showRecipients")}<Icon name="chevron" className={`h-4 w-4 transition-transform ${open ? "-rotate-90" : "rotate-90"}`} /></Button>
      </div>
      {children}
    </li>
  );
}

/** Live queue: the latest broadcasts with progress, cost and the per-recipient list. One poller (3 s) runs only while something is sending and the tab is visible. */
export default function Queue({ list, balanceMinor, onTick }: { list: Broadcast[]; balanceMinor: number; onTick: () => void }) {
  const { t } = useI18n();
  const toast = useToast();
  const [openId, setOpenId] = useState<string | null>(null);
  const [cancelId, setCancelId] = useState<string | null>(null);
  const [extra, setExtra] = useState<{ id: string; rows: Recipient[] }>({ id: "", rows: [] });
  const latest = list.slice(0, 5);
  const sending = latest.find((b) => b.status === "SENDING");
  const focus = openId === "" ? null : (openId ?? sending?.id ?? latest[0]?.id ?? null);
  const rec = useApi<RecipientsRes>(focus ? `store/broadcasts/${focus}/recipients?limit=${PAGE}&offset=0` : null);
  usePoll(!!sending, () => { onTick(); void rec.reload(); });

  const resume = useAction(async (id: string) => { await api(`store/broadcasts/${id}/resume`, { body: {} }); toast(t("grx.resumed")); onTick(); void rec.reload(); });
  const cancel = useAction(async (id: string) => { await api(`store/broadcasts/${id}/cancel`, { body: {} }); toast(t("grx.canceled")); onTick(); void rec.reload(); });
  const more = useAction(async () => {
    if (!focus) return;
    const have = PAGE + (extra.id === focus ? extra.rows.length : 0);
    const r = await api<RecipientsRes>(`store/broadcasts/${focus}/recipients?limit=${PAGE}&offset=${have}`);
    setExtra({ id: focus, rows: [...(extra.id === focus ? extra.rows : []), ...r.data] });
  });

  if (latest.length === 0) return null;
  const first = rec.data?.data ?? [];
  const ids = new Set(first.map((r) => r.id));
  const rows = [...first, ...(extra.id === focus ? extra.rows : []).filter((r) => !ids.has(r.id))];
  const total = rec.data?.total ?? 0;
  const counts = rec.data?.counts ?? {};

  const recipients = (
    <div className="space-y-2 border-t border-line pt-3">
      <div className="flex flex-wrap gap-1.5 text-[11.5px] tnum">
        {(["SENDING", "SENT", "PENDING", "FAILED", "SKIPPED"] as RecipientStatus[]).filter((s) => (counts[s] ?? 0) > 0).map((s) => <Badge key={s} tone={s === "SENT" ? "green" : s === "SENDING" ? "amber" : s === "FAILED" ? "red" : "slate"}>{t(`gx.rs.${s}`)} {counts[s]}</Badge>)}
      </div>
      {!rec.data ? <div className="space-y-1.5">{[0, 1, 2].map((i) => <div key={i} className="skeleton h-9 rounded-[10px]" />)}</div>
        : rows.length === 0 ? <Empty compact icon="users">{t("gx.noRecipients")}</Empty>
        : <ul className="max-h-[28rem] space-y-1.5 overflow-y-auto pe-1">{rows.map((r) => <RecipientRow key={r.id} r={r} />)}</ul>}
      {rows.length < total && <Button size="sm" variant="secondary" className="w-full" loading={more.loading} onClick={() => void more.run()}>{t("grx.more", { n: total - rows.length })}</Button>}
      <ErrorText error={rec.error ?? more.error} />
    </div>
  );

  return (
    <Panel className="space-y-3 p-4 sm:p-5">
      <PanelHead icon="clock" title={t("gx.queue")} hint={sending ? t("gx.queueLive") : t("gr.history")} />
      <ErrorText error={resume.error ?? cancel.error} />
      <ul className="space-y-3">
        {latest.map((b) => (
          <BroadcastCard key={b.id} b={b} balanceMinor={balanceMinor} open={focus === b.id} busy={resume.loading}
            onOpen={() => setOpenId(focus === b.id ? "" : b.id)} onResume={() => void resume.run(b.id)} onCancel={() => setCancelId(b.id)}>
            {focus === b.id ? recipients : null}
          </BroadcastCard>
        ))}
      </ul>
      <ConfirmDialog open={!!cancelId} onClose={() => setCancelId(null)} danger onConfirm={() => { const id = cancelId; setCancelId(null); if (id) void cancel.run(id); }}
        title={t("gr.cancel")} body={t("gr.confirmCancel")} confirmLabel={t("gr.cancel")} cancelLabel={t("common.cancel")} />
    </Panel>
  );
}
