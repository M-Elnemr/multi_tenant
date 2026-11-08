"use client";

import { useI18n } from "@/components/i18n-provider";
import { ChannelIcon } from "@/components/channel-icons";
import { Icon } from "@/components/icons";

/** Where an order came from, shown as a small hairline label with the channel's own mark. */
export const CHANNELS = ["WEBSITE", "FACEBOOK", "INSTAGRAM", "WHATSAPP", "POS", "OTHER"] as const;

const KNOWN = new Set<string>(CHANNELS);

export function ChannelMark({ channel, className = "h-3.5 w-3.5" }: { channel?: string | null; className?: string }) {
  const c = channel && KNOWN.has(channel) ? channel : "OTHER";
  if (c === "FACEBOOK" || c === "INSTAGRAM" || c === "WHATSAPP") return <ChannelIcon channel={c} className={className} />;
  return <Icon name={c === "WEBSITE" ? "globe" : c === "POS" ? "receipt" : "dot"} className={`${className} text-muted`} />;
}

export function ChannelBadge({ channel }: { channel?: string | null }) {
  const { t } = useI18n();
  const c = channel && KNOWN.has(channel) ? channel : "OTHER";
  return <span className="inline-flex items-center gap-1 whitespace-nowrap rounded-full bg-canvas px-2 py-0.5 text-[11px] font-semibold text-ink-2 ring-1 ring-line"><ChannelMark channel={c} className="h-3 w-3" />{t(`channel.${c}`)}</span>;
}
