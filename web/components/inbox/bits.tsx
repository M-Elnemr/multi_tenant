"use client";

import { ChannelDot } from "../channel-icons";
import { Icon } from "../icons";
import type { Status } from "./types";

/** Each status's look: needs reply amber, waiting sky, unhappy rose, takeover violet, closed slate. */
export const STATUS_STYLE: Record<Status, { cls: string; icon: string; dot: string }> = {
  NEEDS_REPLY: { cls: "bg-amber-50 text-amber-800 ring-amber-600/15", icon: "clock", dot: "#f59e0b" },
  WAITING: { cls: "bg-sky-50 text-sky-800 ring-sky-600/15", icon: "send", dot: "#0ea5e9" },
  UNHAPPY: { cls: "bg-rose-50 text-rose-700 ring-rose-600/15", icon: "alert", dot: "#f43f5e" },
  TAKEOVER: { cls: "bg-violet-50 text-violet-700 ring-violet-600/15", icon: "users", dot: "#7c3aed" },
  OPEN: { cls: "bg-emerald-50 text-emerald-700 ring-emerald-600/15", icon: "dot", dot: "#10b981" },
  CLOSED: { cls: "bg-slate-100 text-slate-600 ring-slate-500/15", icon: "check", dot: "#94a3b8" },
};

export function StatusTag({ status, label, size = "sm" }: { status: Status; label: string; size?: "xs" | "sm" }) {
  const s = STATUS_STYLE[status];
  return (
    <span className={`inline-flex items-center gap-1 whitespace-nowrap rounded-[6px] font-semibold ring-1 ring-inset ${s.cls} ${size === "xs" ? "px-1.5 py-px text-[10.5px]" : "px-2 py-0.5 text-[11.5px]"}`}>
      <Icon name={s.icon} className={size === "xs" ? "h-2.5 w-2.5" : "h-3 w-3"} />{label}
    </span>
  );
}

/** «AI on» / «AI paused» / «You» pill. */
export function AiPill({ active, takeover, label, size = "sm" }: { active: boolean; takeover?: boolean; label: string; size?: "xs" | "sm" }) {
  const cls = active ? "bg-[linear-gradient(135deg,rgb(124_92_255/.12),rgb(34_211_238/.12))] text-violet-700 ring-violet-500/20" : takeover ? "bg-violet-600 text-white ring-violet-600" : "bg-slate-100 text-slate-600 ring-slate-500/15";
  return (
    <span className={`inline-flex items-center gap-1 whitespace-nowrap rounded-full font-semibold ring-1 ring-inset ${cls} ${size === "xs" ? "px-1.5 py-px text-[10.5px]" : "px-2 py-0.5 text-[11.5px]"}`}>
      <Icon name={active ? "sparkles" : takeover ? "users" : "minus"} className={size === "xs" ? "h-2.5 w-2.5" : "h-3 w-3"} />{label}
    </span>
  );
}

const PALETTE = ["#7c5cff", "#0ea5e9", "#10b981", "#f59e0b", "#ef4444", "#ec4899", "#14b8a6", "#6366f1", "#84cc16", "#f97316"];

/** Initials on a color picked from the name, with the channel's badge on the corner. */
export function ChatAvatar({ name, channel, size = 40, unread }: { name?: string | null; channel: string; size?: number; unread?: boolean }) {
  const n = (name ?? "").trim();
  const initials = n ? n.split(/\s+/).slice(0, 2).map((w) => [...w][0]).join("").toUpperCase() : "?";
  let h = 0;
  for (const ch of n) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  const color = PALETTE[h % PALETTE.length];
  return (
    <span className="relative inline-flex shrink-0" style={{ width: size, height: size }}>
      <span className="grid h-full w-full place-items-center rounded-full font-semibold text-white" style={{ background: n ? `linear-gradient(140deg, ${color}, color-mix(in srgb, ${color} 70%, black))` : "var(--line-strong)", fontSize: size * 0.36 }}>
        {n ? initials : <Icon name="users" className="h-1/2 w-1/2 opacity-80" />}
      </span>
      <ChannelDot channel={channel} size={Math.round(size * 0.42)} className="absolute -bottom-0.5 -end-0.5" />
      {unread && <span className="absolute -start-0.5 -top-0.5 h-3 w-3 rounded-full bg-brand ring-2 ring-surface" />}
    </span>
  );
}

/** «10:42», «Yesterday», «Mon», «12 Oct» in the reader's language and time zone. */
export function when(iso: string | null | undefined, locale: string, yesterday: string) {
  if (!iso) return "";
  const d = new Date(iso), now = new Date();
  const loc = locale === "ar" ? "ar-EG" : "en-GB";
  const sameDay = d.toDateString() === now.toDateString();
  if (sameDay) return d.toLocaleTimeString(loc, { hour: "numeric", minute: "2-digit" });
  const y = new Date(now); y.setDate(now.getDate() - 1);
  if (d.toDateString() === y.toDateString()) return yesterday;
  if (now.getTime() - d.getTime() < 6 * 86400_000) return d.toLocaleDateString(loc, { weekday: "short" });
  return d.toLocaleDateString(loc, { day: "numeric", month: "short" });
}

/** «23h 10m left» style remaining time, or null when past. */
export function left(iso: string | undefined, locale: string) {
  if (!iso) return null;
  const ms = new Date(iso).getTime() - Date.now();
  if (ms <= 0) return null;
  const h = Math.floor(ms / 3600_000), m = Math.floor((ms % 3600_000) / 60_000);
  return locale === "ar" ? `${h.toLocaleString("ar-EG")}س ${m.toLocaleString("ar-EG")}د` : `${h}h ${m}m`;
}

export function dayLabel(iso: string, locale: string, today: string, yesterday: string) {
  const d = new Date(iso), now = new Date();
  if (d.toDateString() === now.toDateString()) return today;
  const y = new Date(now); y.setDate(now.getDate() - 1);
  if (d.toDateString() === y.toDateString()) return yesterday;
  return d.toLocaleDateString(locale === "ar" ? "ar-EG" : "en-GB", { weekday: "long", day: "numeric", month: "long" });
}

const REASONS = ["CUSTOMER_REPLIED", "TAKEOVER", "CLOSED", "PAUSED", "TURNED_OFF", "OWNER", "OUTSIDE_WINDOW", "OPTED_OUT", "SHOP_NOT_ACTIVE", "NEEDS_TEAM", "EARLIER_STEP_NOT_SENT"];

/** A follow-up's reason in words (a provider's own error text becomes «the channel refused the message»). */
export function reasonKey(r: string) { return `fu.reason.${REASONS.includes(r) ? r : "PROVIDER_FAILED"}`; }
