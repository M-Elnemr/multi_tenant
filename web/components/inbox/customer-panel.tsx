"use client";

import Link from "next/link";
import { useState } from "react";
import { api, ApiError } from "@/lib/client";
import { money } from "@/lib/format";
import { useApi, useMe } from "../hooks";
import { useI18n } from "../i18n-provider";
import { ChannelIcon } from "../channel-icons";
import { Icon } from "../icons";
import { StatusBadge } from "../ui";
import { Tooltip } from "../overlay";
import { useToast } from "../toast";
import { ChatAvatar, reasonKey, when } from "./bits";
import type { ChatDetail } from "./types";

type Tab = "details" | "orders" | "chats" | "notes" | "followups";
type Profile = { conversations: { id: string; channel: string; updatedAt: string; status: string; lastMessage?: string }[] };

/**
 * The customer next to the chat: who they are and where they came from, tags and the team's notes, orders and spending, their other chats, and this chat's follow-ups.
 * Only what the shop really knows is shown; a Messenger / Instagram profile not linked to a customer says so.
 */
export function CustomerPanel({ chat, onClose, onChanged, canWrite }: { chat: ChatDetail; onClose: () => void; onChanged: () => void; canWrite: boolean }) {
  const { t, locale } = useI18n();
  const { can } = useMe();
  const toast = useToast();
  const [tab, setTab] = useState<Tab>("details");
  const c = chat.customer;
  const canCustomer = can("customer.read");
  const profile = useApi<Profile>(c && canCustomer && tab === "chats" ? `store/customers/${c.id}` : null);
  const [tag, setTag] = useState("");
  const [draft, setNotes] = useState<string | null>(null);   // null: not edited, show what is saved
  const notes = draft ?? c?.notes ?? "";
  const [noteText, setNoteText] = useState("");
  const name = chat.customerName || chat.customerPhone || t("inbox.guest");
  const num = (n: number) => n.toLocaleString(locale === "ar" ? "ar-EG" : "en-US");

  const addTag = async () => {
    const v = tag.trim();
    if (!c || !v) return;
    try { await api(`store/customers/${c.id}/tags`, { body: { tag: v } }); setTag(""); onChanged(); } catch (e) { toast(e instanceof ApiError ? e.message : t("inbox.saveFailed"), { tone: "error" }); }
  };
  const dropTag = async (v: string) => { if (!c) return; await api(`store/customers/${c.id}/tags/${encodeURIComponent(v)}`, { method: "DELETE" }).catch(() => undefined); onChanged(); };
  const saveNotes = async () => {
    if (!c || notes === (c.notes ?? "")) return;
    try { await api(`store/customers/${c.id}/notes`, { method: "PUT", body: { notes } }); toast(t("inbox.saved.ok")); setNotes(null); onChanged(); } catch (e) { toast(e instanceof ApiError ? e.message : t("inbox.saveFailed"), { tone: "error" }); }
  };
  const addNote = async () => {
    const v = noteText.trim();
    if (!v) return;
    try { await api(`store/chat/conversations/${chat.id}/notes`, { body: { body: v } }); setNoteText(""); onChanged(); } catch (e) { toast(e instanceof ApiError ? e.message : t("inbox.saveFailed"), { tone: "error" }); }
  };

  const tabs: { key: Tab; icon: string; label: string; n?: number }[] = [
    { key: "details", icon: "users", label: t("inbox.p.details") },
    { key: "orders", icon: "box", label: t("inbox.p.orders"), n: c?.ordersCount },
    { key: "chats", icon: "chat", label: t("inbox.p.chats") },
    { key: "notes", icon: "edit", label: t("inbox.p.notes"), n: chat.notes.length },
    { key: "followups", icon: "clock", label: t("inbox.p.followups"), n: chat.followUps.filter((f) => f.status === "SCHEDULED").length },
  ];
  const row = (icon: string, label: string, value?: React.ReactNode) => value ? (
    <div className="flex items-start gap-3 py-2">
      <Icon name={icon} className="mt-0.5 h-4 w-4 shrink-0 text-faint" />
      <div className="min-w-0 flex-1"><p className="text-[11.5px] text-faint">{label}</p><div className="break-words text-[13px] font-medium text-ink">{value}</div></div>
    </div>
  ) : null;

  return (
    <div className="flex h-full min-h-0 bg-surface">
      <div className="flex min-w-0 flex-1 flex-col">
        <div className="flex items-center justify-between gap-2 border-b border-line px-4 py-3">
          <p className="font-display text-[14.5px] font-semibold text-ink">{tabs.find((x) => x.key === tab)?.label}</p>
          <button type="button" onClick={onClose} aria-label={t("common.close")} className="grid h-8 w-8 place-items-center rounded-[9px] text-muted hover:bg-[color-mix(in_srgb,var(--ink)_6%,transparent)] hover:text-ink"><Icon name="close" className="h-4 w-4" /></button>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto px-4 py-4">
          {tab === "details" && (
            <>
              <div className="flex flex-col items-center pb-4 text-center">
                <ChatAvatar name={chat.customerName} channel={chat.channel} size={64} />
                <p className="mt-3 font-display text-[16px] font-semibold text-ink">{name}</p>
                {c?.customerNumber && <p className="text-[11.5px] text-faint tnum">#{c.customerNumber}</p>}
                <div className="mt-2 flex flex-wrap justify-center gap-1.5">
                  <span className="inline-flex items-center gap-1 rounded-full bg-canvas px-2 py-0.5 text-[11.5px] font-medium text-muted ring-1 ring-line"><ChannelIcon channel={chat.channel} className="h-3 w-3" />{t(`inbox.ch.${chat.channel}`)}</span>
                  {c && c.ordersCount > 0 && <span className="rounded-full bg-emerald-50 px-2 py-0.5 text-[11.5px] font-semibold text-emerald-700">{t("inbox.returning")}</span>}
                  {c && c.ordersCount === 0 && <span className="rounded-full bg-sky-50 px-2 py-0.5 text-[11.5px] font-semibold text-sky-700">{t("inbox.newCustomer")}</span>}
                </div>
                {c && canCustomer && <Link href={`/dashboard/customers/${c.id}`} className="mt-3 inline-flex items-center gap-1.5 rounded-[10px] px-3 py-1.5 text-[12.5px] font-semibold text-brand ring-1 ring-brand/25 transition hover:bg-brand-soft">{t("inbox.openProfile")}<Icon name="arrow" className="h-3.5 w-3.5 flip-rtl" /></Link>}
              </div>
              {c && (
                <div className="mb-3 grid grid-cols-3 gap-2 text-center">
                  <Stat label={t("inbox.orders")} value={num(c.ordersCount)} />
                  <Stat label={t("inbox.spent")} value={money(c.totalSpentMinor, "EGP", locale)} />
                  <Stat label={t("inbox.lastOrder")} value={c.lastOrderAt ? when(c.lastOrderAt, locale, t("inbox.yesterday")) : "—"} />
                </div>
              )}
              <div className="divide-y divide-line">
                {row("phone", t("inbox.phone"), chat.customerPhone ? <a dir="ltr" className="tnum hover:text-brand" href={`tel:${chat.customerPhone}`}>{chat.customerPhone}</a> : c?.phone ? <span dir="ltr" className="tnum">{c.phone}</span> : undefined)}
                {row("mail", t("inbox.email"), c?.email ?? undefined)}
                {row("pin", t("inbox.address"), c?.address ? [c.address.addressLine1, c.address.district, c.address.city].filter(Boolean).join("، ") : undefined)}
                {row("calendar", t("inbox.firstContact"), new Date(chat.createdAt).toLocaleDateString(locale === "ar" ? "ar-EG" : "en-GB", { day: "numeric", month: "short", year: "numeric" }))}
                {row("alert", t("inbox.optedOut"), c?.broadcastOptOutAt ? t("inbox.optedOutYes") : undefined)}
              </div>
              {!c && <p className="mt-3 rounded-[12px] bg-canvas p-3 text-[12.5px] leading-relaxed text-muted ring-1 ring-line">{chat.channel === "FACEBOOK" || chat.channel === "INSTAGRAM" ? t("inbox.unlinkedMeta") : t("inbox.unlinked")}</p>}
              {c && (
                <div className="mt-4">
                  <p className="mb-2 text-[11.5px] font-semibold uppercase tracking-wide text-faint">{t("inbox.tags")}</p>
                  <div className="flex flex-wrap gap-1.5">
                    {c.tags.map((g) => (
                      <span key={g} className="inline-flex items-center gap-1 rounded-full bg-brand-soft py-0.5 pe-1 ps-2.5 text-[12px] font-semibold text-brand">{g}
                        {canWrite && canCustomer && <button type="button" onClick={() => void dropTag(g)} aria-label={t("common.remove")} className="grid h-4 w-4 place-items-center rounded-full hover:bg-brand/15"><Icon name="close" className="h-3 w-3" /></button>}
                      </span>
                    ))}
                    {c.tags.length === 0 && <span className="text-[12px] text-faint">{t("inbox.noTags")}</span>}
                  </div>
                  {canWrite && canCustomer && (
                    <form onSubmit={(e) => { e.preventDefault(); void addTag(); }} className="mt-2 flex gap-1.5">
                      <input value={tag} onChange={(e) => setTag(e.target.value)} maxLength={40} placeholder={t("inbox.addTag")} className="h-9 min-w-0 flex-1 rounded-[9px] bg-canvas px-2.5 text-[13px] outline-none ring-1 ring-line focus:ring-2 focus:ring-brand/30" />
                      <button type="submit" disabled={!tag.trim()} className="rounded-[9px] bg-ink px-3 text-[12.5px] font-semibold text-surface disabled:opacity-40">{t("common.add")}</button>
                    </form>
                  )}
                  <p className="mb-2 mt-5 text-[11.5px] font-semibold uppercase tracking-wide text-faint">{t("inbox.customerNotes")}</p>
                  <textarea value={notes} onChange={(e) => setNotes(e.target.value)} onBlur={() => void saveNotes()} disabled={!canWrite || !canCustomer} rows={3} maxLength={4000} placeholder={t("inbox.customerNotesPh")}
                    className="w-full resize-none rounded-[11px] bg-canvas px-3 py-2 text-[13px] outline-none ring-1 ring-line focus:bg-surface focus:ring-2 focus:ring-brand/30" />
                </div>
              )}
            </>
          )}

          {tab === "orders" && (
            !c ? <Empty text={t("inbox.noOrdersUnlinked")} /> : c.orders.length === 0 ? <Empty text={t("inbox.noOrders")} /> : (
              <ul className="space-y-2">
                {c.orders.map((o) => (
                  <li key={o.id}>
                    <Link href={`/dashboard/orders/${o.id}`} className="flex items-center justify-between gap-2 rounded-[12px] p-3 ring-1 ring-line transition hover:bg-[color-mix(in_srgb,var(--ink)_3%,transparent)]">
                      <span className="min-w-0"><span className="block font-mono text-[12.5px] font-semibold text-ink" dir="ltr">{o.orderNumber}</span><span className="text-[11.5px] text-faint">{when(o.createdAt, locale, t("inbox.yesterday"))}</span></span>
                      <span className="flex flex-col items-end gap-1"><span className="text-[13px] font-semibold tnum">{money(o.totalMinor, o.currency, locale)}</span><StatusBadge status={o.status} /></span>
                    </Link>
                  </li>
                ))}
                {canCustomer && <li><Link href={`/dashboard/customers/${c.id}`} className="block py-2 text-center text-[12.5px] font-semibold text-brand">{t("inbox.allOrders")}</Link></li>}
              </ul>
            )
          )}

          {tab === "chats" && (
            !c ? <Empty text={t("inbox.noChatsUnlinked")} /> : !profile.data ? <div className="space-y-2">{[0, 1].map((i) => <div key={i} className="skeleton h-14 rounded-[12px]" />)}</div> : (
              <ul className="space-y-2">
                {profile.data.conversations.map((v) => (
                  <li key={v.id}>
                    <Link href={`/dashboard/inbox?id=${v.id}`} className={`flex items-center gap-2.5 rounded-[12px] p-3 ring-1 transition hover:bg-[color-mix(in_srgb,var(--ink)_3%,transparent)] ${v.id === chat.id ? "ring-brand/30 bg-brand-soft/40" : "ring-line"}`}>
                      <ChannelIcon channel={v.channel} className="h-4 w-4" />
                      <span className="min-w-0 flex-1"><span className="line-clamp-1 text-[12.5px] text-ink-2">{v.lastMessage}</span><span className="text-[11px] text-faint">{when(v.updatedAt, locale, t("inbox.yesterday"))}</span></span>
                    </Link>
                  </li>
                ))}
              </ul>
            )
          )}

          {tab === "notes" && (
            <>
              {canWrite && (
                <form onSubmit={(e) => { e.preventDefault(); void addNote(); }} className="mb-3">
                  <textarea value={noteText} onChange={(e) => setNoteText(e.target.value)} rows={3} maxLength={2000} placeholder={t("inbox.notePh")} className="w-full resize-none rounded-[11px] bg-amber-50/70 px-3 py-2 text-[13px] outline-none ring-1 ring-amber-200 focus:ring-2 focus:ring-amber-300" />
                  <button type="submit" disabled={!noteText.trim()} className="mt-1.5 w-full rounded-[10px] bg-amber-600 py-2 text-[12.5px] font-semibold text-white disabled:opacity-40">{t("inbox.saveNote")}</button>
                </form>
              )}
              {chat.notes.length === 0 ? <Empty text={t("inbox.noNotes")} /> : (
                <ul className="space-y-2">{[...chat.notes].reverse().map((n) => <li key={n.id} className="rounded-[12px] border border-amber-200 bg-amber-50 p-3 text-[13px] text-amber-950"><p className="mb-1 text-[11px] font-semibold text-amber-700">{n.author} · {when(n.createdAt, locale, t("inbox.yesterday"))}</p><p className="whitespace-pre-wrap" dir="auto">{n.body}</p></li>)}</ul>
              )}
            </>
          )}

          {tab === "followups" && (
            <>
              {chat.followUps.length === 0 ? <Empty text={t("inbox.noFollowups")} /> : (
                <ol className="relative space-y-3 border-s border-line ps-4">
                  {chat.followUps.map((f) => (
                    <li key={f.id} className="relative">
                      <span className={`absolute -start-[21px] top-1 h-2.5 w-2.5 rounded-full ring-4 ring-surface ${f.status === "SENT" ? "bg-emerald-500" : f.status === "SCHEDULED" ? "bg-sky-500 live-dot" : f.status === "FAILED" ? "bg-red-500" : "bg-slate-300"}`} />
                      <p className="text-[12px] font-semibold text-ink">{t("inbox.step", { n: f.stepPosition })} · {t(`fu.st.${f.status}`)}</p>
                      <p className="text-[11.5px] text-faint tnum">{new Date(f.sentAt ?? f.dueAt).toLocaleString(locale === "ar" ? "ar-EG" : "en-GB", { day: "numeric", month: "short", hour: "numeric", minute: "2-digit" })}{f.reason ? ` · ${t(reasonKey(f.reason))}` : ""}</p>
                      <p className="mt-1 line-clamp-2 text-[12.5px] text-muted">{f.body}</p>
                    </li>
                  ))}
                </ol>
              )}
              <Link href="/dashboard/inbox/followups" className="mt-4 block rounded-[10px] py-2 text-center text-[12.5px] font-semibold text-brand ring-1 ring-brand/25 hover:bg-brand-soft">{t("inbox.followupSettings")}</Link>
            </>
          )}
        </div>
      </div>
      {/* section rail */}
      <div className="flex w-12 shrink-0 flex-col items-center gap-1 border-s border-line bg-canvas py-3">
        {tabs.map((x) => (
          <Tooltip key={x.key} label={x.label} placement="left">
            <button type="button" onClick={() => setTab(x.key)} aria-label={x.label} aria-pressed={tab === x.key}
              className={`relative grid h-9 w-9 place-items-center rounded-[10px] transition ${tab === x.key ? "bg-ink text-surface shadow-sm" : "text-muted hover:bg-surface hover:text-ink"}`}>
              <Icon name={x.icon} className="h-[17px] w-[17px]" />
              {(x.n ?? 0) > 0 && tab !== x.key && <span className="absolute -end-0.5 -top-0.5 grid h-4 min-w-4 place-items-center rounded-full bg-brand px-1 text-[9px] font-bold text-white">{x.n}</span>}
            </button>
          </Tooltip>
        ))}
      </div>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return <div className="rounded-[12px] bg-canvas px-2 py-2.5 ring-1 ring-line"><p className="truncate text-[13.5px] font-semibold text-ink tnum">{value}</p><p className="truncate text-[10.5px] text-faint">{label}</p></div>;
}

function Empty({ text }: { text: string }) {
  return <p className="rounded-[12px] bg-canvas px-4 py-8 text-center text-[12.5px] text-muted ring-1 ring-line">{text}</p>;
}
