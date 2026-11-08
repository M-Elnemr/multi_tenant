"use client";

import Link from "next/link";
import { useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { ChannelIcon } from "@/components/channel-icons";
import { Icon } from "@/components/icons";
import { AnimatedNumber, Empty, Skeleton, cx } from "@/components/ui";
import { ChatAvatar, StatusTag, when } from "@/components/inbox/bits";
import type { ChatRow, Counts, Page } from "@/components/inbox/types";
import { money } from "@/lib/format";
import { AI_GRADIENT } from "./kit";

type AiSettings = { enabled: boolean; channels: string[]; pointsMinor?: number; agentName?: string | null };
const CH_LABEL: Record<string, string> = { WHATSAPP: "pts.chWhatsapp", FACEBOOK: "pts.chMessenger", INSTAGRAM: "pts.chInstagram", WEB: "pts.chWeb" };

/** The AI seller at a glance: on/off, the channels it answers on, credit in EGP and how many chats wait for a person. */
export function AiSellerCard() {
  const { t, locale } = useI18n();
  const ai = useApi<AiSettings>("store/ai/settings");
  const counts = useApi<Counts>("store/chat/conversations/counts");
  if (!ai.data) return <Skeleton className="h-44 w-full !rounded-[18px]" />;
  const s = ai.data;
  const balance = s.pointsMinor ?? 0;
  const lowCredit = balance < 100;
  const live = s.enabled && !lowCredit;
  const needs = counts.data?.needsReply ?? 0;
  return (
    <section className="relative overflow-hidden rounded-[18px] bg-ink p-5 text-surface sm:p-6">
      <span aria-hidden className="pointer-events-none absolute -end-20 -top-24 h-64 w-64 rounded-full opacity-30 blur-3xl" style={{ background: AI_GRADIENT }} />
      <div className="relative flex flex-wrap items-start justify-between gap-4">
        <div className="flex min-w-0 items-center gap-3">
          <span className="grid h-11 w-11 shrink-0 place-items-center rounded-[13px] text-white" style={{ background: AI_GRADIENT }}><Icon name="sparkles" className="h-5 w-5" /></span>
          <div className="min-w-0">
            <p className="font-display text-[17px] font-semibold leading-tight">{s.agentName ? `${t("dash.aiSeller")} · ${s.agentName}` : t("dash.aiSeller")}</p>
            <p className="mt-1 flex items-center gap-1.5 text-[12.5px] opacity-75">
              <span className={cx("h-1.5 w-1.5 rounded-full", live ? "bg-emerald-400 live-dot" : "bg-amber-400")} />
              {live ? t("dash.aiOn") : s.enabled ? t("kit.creditEmpty") : t("dash.aiOff")}
            </p>
          </div>
        </div>
        <Link href="/dashboard/ai?tab=settings" className="inline-flex h-9 items-center gap-1.5 rounded-[10px] bg-white/10 px-3 text-[13px] font-semibold ring-1 ring-inset ring-white/15 transition hover:bg-white/15">
          <Icon name="settings" className="h-4 w-4" />{t("nav.aiSettings")}
        </Link>
      </div>

      <div className="relative mt-5 grid gap-3 sm:grid-cols-3">
        <div className="rounded-[14px] bg-white/[.06] p-3.5 ring-1 ring-inset ring-white/10">
          <p className="text-[12px] opacity-60">{t("pts.title")}</p>
          <p className={cx("mt-1.5 font-display text-[1.45rem] font-semibold leading-none tnum", lowCredit && "text-rose-300")}><AnimatedNumber value={balance} format={(n) => money(n, "EGP", locale)} /></p>
          <Link href="/dashboard/ai?tab=settings" className="mt-2 inline-block text-[12px] font-semibold opacity-75 hover:opacity-100">{t("wa.requestTopup")}</Link>
        </div>
        <Link href="/dashboard/inbox" className="group rounded-[14px] bg-white/[.06] p-3.5 ring-1 ring-inset ring-white/10 transition hover:bg-white/10">
          <p className="text-[12px] opacity-60">{t("inbox.st.NEEDS_REPLY")}</p>
          <p className={cx("mt-1.5 font-display text-[1.45rem] font-semibold leading-none tnum", needs > 0 && "text-amber-300")}>{counts.data ? <AnimatedNumber value={needs} /> : "—"}</p>
          <p className="mt-2 flex items-center gap-1 text-[12px] font-semibold opacity-75 group-hover:opacity-100">{t("nav.inbox")}<Icon name="chevron" className="h-3 w-3 flip-rtl" /></p>
        </Link>
        <div className="rounded-[14px] bg-white/[.06] p-3.5 ring-1 ring-inset ring-white/10">
          <p className="text-[12px] opacity-60">{t("pts.channels")}</p>
          <div className="mt-2 flex flex-wrap gap-1.5">
            {s.channels.length === 0 && <Link href="/dashboard/ai?tab=settings" className="text-[12.5px] font-semibold underline">{t("onb.channels")}</Link>}
            {s.channels.map((c) => (
              <span key={c} className="inline-flex items-center gap-1.5 rounded-full bg-white/10 px-2 py-1 text-[11.5px] font-medium"><ChannelIcon channel={c as never} className="h-3.5 w-3.5" />{t(CH_LABEL[c] ?? c)}</span>
            ))}
          </div>
        </div>
      </div>
    </section>
  );
}

/** The top conversations that need a reply, each linking straight into the inbox. */
export function InboxPreview() {
  const { t, locale } = useI18n();
  const q = useApi<Page<ChatRow>>("store/chat/conversations?page=true&status=NEEDS_REPLY&limit=5&sort=priority");
  const rows = q.data?.data ?? [];
  return (
    <section className="overflow-hidden rounded-[14px] bg-surface shadow-card">
      <div className="flex min-h-14 items-center justify-between gap-3 px-5 py-3.5 hairline-b">
        <div className="min-w-0">
          <h2 className="truncate text-[14.5px] font-semibold text-ink">{t("home.needsReply")}</h2>
          <p className="truncate text-xs text-muted">{t("home.needsReplyHint")}</p>
        </div>
        <Link href="/dashboard/inbox" className="group inline-flex shrink-0 items-center gap-1 text-[13px] font-semibold text-muted transition hover:text-ink">{t("dash.viewAll")}<Icon name="arrow" className="h-3.5 w-3.5 flip-rtl" /></Link>
      </div>
      {q.loading && !q.data ? (
        <div className="space-y-3 p-5"><Skeleton className="h-11 w-full" /><Skeleton className="h-11 w-full" /><Skeleton className="h-11 w-full" /></div>
      ) : rows.length === 0 ? (
        <div className="p-5"><Empty compact icon="check" title={t("home.allCaughtUp")}>{t("home.allCaughtUpHint")}</Empty></div>
      ) : (
        <ul className="divide-y divide-line">
          {rows.map((c) => (
            <li key={c.id}>
              <Link href={`/dashboard/inbox?id=${c.id}`} className="group flex items-center gap-3 px-5 py-3 transition hover:bg-surface-2">
                <ChatAvatar name={c.customerName} channel={c.channel} size={38} unread={c.unread} />
                <div className="min-w-0 flex-1">
                  <p className="flex items-center gap-2 truncate text-[13.5px] font-semibold text-ink">{c.customerName || c.customerPhone || t("home.visitor")}{c.priority === "HIGH" && <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-rose-500" />}</p>
                  <p className="truncate text-xs text-muted">{c.lastMessage}</p>
                </div>
                <div className="flex shrink-0 flex-col items-end gap-1">
                  <span className="text-[11px] text-faint tnum">{when(c.lastCustomerAt ?? c.updatedAt, locale, t("inbox.yesterday"))}</span>
                  <StatusTag status="NEEDS_REPLY" label={t("inbox.st.NEEDS_REPLY")} size="xs" />
                </div>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
