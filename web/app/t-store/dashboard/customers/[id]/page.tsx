"use client";

import Link from "next/link";
import { use, useState } from "react";
import { api, ApiError } from "@/lib/client";
import { money } from "@/lib/format";
import { useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { ChannelIcon } from "@/components/channel-icons";
import { Icon } from "@/components/icons";
import { Loading, StatusBadge } from "@/components/ui";
import { useToast } from "@/components/toast";
import { ChatAvatar, when } from "@/components/inbox/bits";
import type { CustomerCard } from "@/components/inbox/types";

type Profile = CustomerCard & { conversations: { id: string; channel: string; updatedAt: string; status: string; unhappy: boolean; lastMessage?: string }[] };

/** A customer's full profile: who they are, how they reach the shop, what they bought, every chat on any channel, and the team's tags and notes. */
export default function CustomerProfilePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { t, locale } = useI18n();
  const toast = useToast();
  const { canWrite } = useMe();
  const p = useApi<Profile>(`store/customers/${id}`);
  const [draft, setNotes] = useState<string | null>(null);   // null: not edited, show what is saved
  const [tag, setTag] = useState("");
  if (p.error) return <p className="rounded-[14px] bg-red-50 p-4 text-sm text-red-700">{p.error instanceof ApiError ? p.error.message : String(p.error)}</p>;
  if (!p.data) return <Loading variant="detail" />;
  const c = p.data;
  const notes = draft ?? c.notes ?? "";
  const write = canWrite("customers");
  const n = (x: number) => x.toLocaleString(locale === "ar" ? "ar-EG" : "en-US");
  const channels = Array.from(new Set(c.conversations.map((v) => v.channel)));
  const save = async () => {
    try { await api(`store/customers/${id}/notes`, { method: "PUT", body: { notes } }); toast(t("inbox.saved.ok")); setNotes(null); void p.reload(); } catch (e) { toast(e instanceof ApiError ? e.message : t("inbox.saveFailed"), { tone: "error" }); }
  };
  const add = async () => {
    if (!tag.trim()) return;
    try { await api(`store/customers/${id}/tags`, { body: { tag } }); setTag(""); void p.reload(); } catch (e) { toast(e instanceof ApiError ? e.message : t("inbox.saveFailed"), { tone: "error" }); }
  };

  return (
    <div className="space-y-5">
      <Link href="/dashboard/customers" className="inline-flex items-center gap-1.5 text-[13px] font-semibold text-muted hover:text-ink"><Icon name="arrow" className="h-4 w-4 rotate-180 rtl:rotate-0" />{t("nav.customers")}</Link>
      {/* header */}
      <div className="relative overflow-hidden rounded-[22px] bg-surface p-6 ring-1 ring-line">
        <div className="absolute inset-0 bg-[radial-gradient(60%_80%_at_100%_0%,color-mix(in_srgb,var(--brand)_10%,transparent),transparent_70%)] rtl:bg-[radial-gradient(60%_80%_at_0%_0%,color-mix(in_srgb,var(--brand)_10%,transparent),transparent_70%)]" aria-hidden />
        <div className="relative flex flex-wrap items-center gap-5">
          <ChatAvatar name={c.name} channel={channels[0] ?? "WEB"} size={72} />
          <div className="min-w-0 flex-1">
            <h1 className="font-display text-[24px] font-semibold text-ink">{c.name || t("inbox.guest")}</h1>
            <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-[13px] text-muted">
              {c.customerNumber && <span className="tnum">#{c.customerNumber}</span>}
              {c.phone && <a href={`tel:${c.phone}`} dir="ltr" className="tnum hover:text-brand">{c.phone}</a>}
              {c.email && <span>{c.email}</span>}
              <span>{t("cust.since", { d: new Date(c.createdAt).toLocaleDateString(locale === "ar" ? "ar-EG" : "en-GB", { month: "long", year: "numeric" }) })}</span>
            </div>
            <div className="mt-2.5 flex flex-wrap gap-1.5">
              {channels.map((ch) => <span key={ch} className="inline-flex items-center gap-1 rounded-full bg-canvas px-2 py-0.5 text-[11.5px] font-medium text-muted ring-1 ring-line"><ChannelIcon channel={ch} className="h-3 w-3" />{t(`inbox.ch.${ch}`)}</span>)}
              <span className={`rounded-full px-2 py-0.5 text-[11.5px] font-semibold ${c.ordersCount > 0 ? "bg-emerald-50 text-emerald-700" : "bg-sky-50 text-sky-700"}`}>{c.ordersCount > 0 ? t("inbox.returning") : t("inbox.newCustomer")}</span>
              {c.broadcastOptOutAt && <span className="rounded-full bg-amber-50 px-2 py-0.5 text-[11.5px] font-semibold text-amber-800">{t("inbox.optedOutYes")}</span>}
            </div>
          </div>
          <div className="flex gap-2">
            {c.conversations[0] && <Link href={`/dashboard/inbox?id=${c.conversations[0].id}`} className="inline-flex h-10 items-center gap-1.5 rounded-[11px] bg-ink px-4 text-[13px] font-semibold text-surface"><Icon name="chat" className="h-4 w-4" />{t("cust.openChat")}</Link>}
            {c.phone && <a href={`https://wa.me/${c.phone.replace(/\D/g, "")}`} target="_blank" rel="noreferrer" className="inline-flex h-10 items-center gap-1.5 rounded-[11px] px-4 text-[13px] font-semibold text-ink-2 ring-1 ring-line-strong hover:bg-canvas"><ChannelIcon channel="WHATSAPP" className="h-4 w-4" />WhatsApp</a>}
          </div>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        {[
          { label: t("inbox.orders"), value: n(c.ordersCount), icon: "box" },
          { label: t("inbox.spent"), value: money(c.totalSpentMinor, "EGP", locale), icon: "wallet" },
          { label: t("cust.avg"), value: c.ordersCount ? money(Math.round(c.totalSpentMinor / c.ordersCount), "EGP", locale) : "—", icon: "chart" },
          { label: t("inbox.lastOrder"), value: c.lastOrderAt ? when(c.lastOrderAt, locale, t("inbox.yesterday")) : "—", icon: "calendar" },
        ].map((s) => (
          <div key={s.label} className="rounded-[16px] bg-surface p-4 ring-1 ring-line">
            <Icon name={s.icon} className="mb-2 h-4 w-4 text-faint" />
            <p className="font-display text-[20px] font-semibold text-ink tnum">{s.value}</p>
            <p className="text-[12px] text-muted">{s.label}</p>
          </div>
        ))}
      </div>

      <div className="grid gap-5 lg:grid-cols-[1.4fr_1fr]">
        <div className="space-y-5">
          <section className="rounded-[18px] bg-surface ring-1 ring-line">
            <h2 className="flex items-center justify-between px-5 pt-4 font-display text-[15px] font-semibold">{t("inbox.p.orders")}<span className="text-[12px] font-normal text-faint tnum">{n(c.orders.length)}</span></h2>
            {c.orders.length === 0 ? <p className="px-5 py-10 text-center text-[13px] text-muted">{t("inbox.noOrders")}</p> : (
              <ul className="mt-2 divide-y divide-line">
                {c.orders.map((o) => (
                  <li key={o.id}><Link href={`/dashboard/orders/${o.id}`} className="flex items-center justify-between gap-3 px-5 py-3 transition hover:bg-canvas">
                    <span><span className="block font-mono text-[13px] font-semibold" dir="ltr">{o.orderNumber}</span><span className="text-[12px] text-faint">{new Date(o.createdAt).toLocaleDateString(locale === "ar" ? "ar-EG" : "en-GB", { day: "numeric", month: "short", year: "numeric" })}</span></span>
                    <span className="flex items-center gap-3"><StatusBadge status={o.status} /><span className="w-24 text-end text-[13.5px] font-semibold tnum">{money(o.totalMinor, o.currency, locale)}</span></span>
                  </Link></li>
                ))}
              </ul>
            )}
          </section>
          <section className="rounded-[18px] bg-surface ring-1 ring-line">
            <h2 className="px-5 pt-4 font-display text-[15px] font-semibold">{t("inbox.p.chats")}</h2>
            {c.conversations.length === 0 ? <p className="px-5 py-10 text-center text-[13px] text-muted">{t("cust.noChats")}</p> : (
              <ul className="mt-2 divide-y divide-line">
                {c.conversations.map((v) => (
                  <li key={v.id}><Link href={`/dashboard/inbox?id=${v.id}`} className="flex items-center gap-3 px-5 py-3 transition hover:bg-canvas">
                    <ChannelIcon channel={v.channel} className="h-4 w-4" />
                    <span className="min-w-0 flex-1"><span className="line-clamp-1 text-[13px] text-ink-2" dir="auto">{v.lastMessage}</span><span className="text-[11.5px] text-faint">{when(v.updatedAt, locale, t("inbox.yesterday"))} · {t(`inbox.st.${v.status}`)}</span></span>
                    {v.unhappy && <Icon name="alert" className="h-4 w-4 text-rose-500" />}
                  </Link></li>
                ))}
              </ul>
            )}
          </section>
        </div>
        <div className="space-y-5">
          <section className="rounded-[18px] bg-surface p-5 ring-1 ring-line">
            <h2 className="mb-3 font-display text-[15px] font-semibold">{t("inbox.tags")}</h2>
            <div className="flex flex-wrap gap-1.5">
              {c.tags.map((g) => <span key={g} className="inline-flex items-center gap-1 rounded-full bg-brand-soft py-0.5 pe-1 ps-2.5 text-[12px] font-semibold text-brand">{g}{write && <button type="button" onClick={async () => { await api(`store/customers/${id}/tags/${encodeURIComponent(g)}`, { method: "DELETE" }).catch(() => undefined); void p.reload(); }} className="grid h-4 w-4 place-items-center rounded-full hover:bg-brand/15" aria-label={t("common.remove")}><Icon name="close" className="h-3 w-3" /></button>}</span>)}
              {c.tags.length === 0 && <span className="text-[12.5px] text-faint">{t("inbox.noTags")}</span>}
            </div>
            {write && <form onSubmit={(e) => { e.preventDefault(); void add(); }} className="mt-3 flex gap-1.5"><input value={tag} onChange={(e) => setTag(e.target.value)} maxLength={40} placeholder={t("inbox.addTag")} className="h-9 min-w-0 flex-1 rounded-[9px] bg-canvas px-2.5 text-[13px] outline-none ring-1 ring-line focus:ring-2 focus:ring-brand/30" /><button type="submit" disabled={!tag.trim()} className="rounded-[9px] bg-ink px-3 text-[12.5px] font-semibold text-surface disabled:opacity-40">{t("common.add")}</button></form>}
          </section>
          <section className="rounded-[18px] bg-surface p-5 ring-1 ring-line">
            <h2 className="mb-3 font-display text-[15px] font-semibold">{t("inbox.customerNotes")}</h2>
            <textarea value={notes} onChange={(e) => setNotes(e.target.value)} disabled={!write} rows={6} maxLength={4000} placeholder={t("inbox.customerNotesPh")} className="w-full resize-none rounded-[12px] bg-canvas px-3 py-2.5 text-[13.5px] outline-none ring-1 ring-line focus:bg-surface focus:ring-2 focus:ring-brand/30" />
            {write && <button type="button" onClick={() => void save()} disabled={notes === (c.notes ?? "")} className="mt-2 rounded-[10px] bg-ink px-4 py-2 text-[13px] font-semibold text-surface disabled:opacity-40">{t("common.save")}</button>}
          </section>
          {c.address && (
            <section className="rounded-[18px] bg-surface p-5 ring-1 ring-line">
              <h2 className="mb-2 font-display text-[15px] font-semibold">{t("inbox.address")}</h2>
              <p className="text-[13.5px] text-ink-2">{[c.address.addressLine1, c.address.district, c.address.city].filter(Boolean).join("، ")}</p>
            </section>
          )}
        </div>
      </div>
    </div>
  );
}
