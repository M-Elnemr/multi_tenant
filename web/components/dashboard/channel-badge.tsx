"use client";

import { useI18n } from "@/components/i18n-provider";

/** Where an order came from, shown as a small coloured label with the channel's own symbol. */
export const CHANNELS = ["WEBSITE", "FACEBOOK", "INSTAGRAM", "WHATSAPP", "POS", "OTHER"] as const;

const STYLE: Record<string, { icon: string; cls: string }> = {
  WEBSITE: { icon: "🌐", cls: "bg-sky-100 text-sky-800" },
  FACEBOOK: { icon: "📘", cls: "bg-blue-100 text-blue-800" },
  INSTAGRAM: { icon: "📷", cls: "bg-pink-100 text-pink-800" },
  WHATSAPP: { icon: "💬", cls: "bg-emerald-100 text-emerald-800" },
  POS: { icon: "🧾", cls: "bg-amber-100 text-amber-800" },
  OTHER: { icon: "•", cls: "bg-slate-100 text-slate-700" },
};

export function ChannelBadge({ channel }: { channel?: string | null }) {
  const { t } = useI18n();
  const c = channel && STYLE[channel] ? channel : "OTHER";
  return <span className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold ${STYLE[c].cls}`}><span aria-hidden>{STYLE[c].icon}</span>{t(`channel.${c}`)}</span>;
}
