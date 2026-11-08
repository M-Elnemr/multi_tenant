/**
 * The chat channels' own marks (simplified glyphs, drawn here: no brand files), used as small badges on avatars, filters and headers.
 * Colors: WhatsApp #25D366, Messenger #0084FF→#A033FF, Instagram the warm gradient, website = the shop's brand color.
 */
export type ChannelKey = "WHATSAPP" | "FACEBOOK" | "INSTAGRAM" | "WEB" | "TEST" | string;

export const CHANNEL_COLOR: Record<string, string> = { WHATSAPP: "#25D366", FACEBOOK: "#0084FF", INSTAGRAM: "#E1306C", WEB: "var(--brand)" };

function Glyph({ channel, className }: { channel: ChannelKey; className?: string }) {
  switch (channel) {
    case "WHATSAPP":
      return (
        <svg viewBox="0 0 24 24" className={className} aria-hidden fill="currentColor">
          <path d="M12 2.2a9.7 9.7 0 0 0-8.4 14.6L2.3 21.7l5-1.3A9.7 9.7 0 1 0 12 2.2Zm0 17.7a8 8 0 0 1-4.1-1.1l-.3-.2-3 .8.8-2.9-.2-.3A8 8 0 1 1 12 19.9Zm4.4-6c-.2-.1-1.4-.7-1.7-.8-.2-.1-.4-.1-.5.1l-.8 1c-.1.2-.3.2-.5.1a6.6 6.6 0 0 1-3.3-2.9c-.2-.4.2-.4.7-1.3.1-.2 0-.3 0-.4l-.8-1.8c-.2-.5-.4-.4-.5-.4h-.5a.9.9 0 0 0-.6.3 2.7 2.7 0 0 0-.8 2c0 1.2.9 2.3 1 2.5.1.2 1.7 2.6 4.1 3.6 1.5.7 2.1.7 2.9.6.5-.1 1.4-.6 1.6-1.1.2-.6.2-1 .1-1.1l-.4-.3Z" />
        </svg>
      );
    case "FACEBOOK":
      return (
        <svg viewBox="0 0 24 24" className={className} aria-hidden fill="currentColor">
          <path d="M12 2.2C6.4 2.2 2.1 6.3 2.1 11.6c0 2.8 1.2 5.2 3.1 6.9v3.3l3-1.7c.9.3 1.8.4 2.8.4 5.6 0 9.9-4.1 9.9-9.4S17.6 2.2 12 2.2Zm1 12.6-2.5-2.7-4.9 2.7 5.4-5.7 2.6 2.7 4.8-2.7-5.4 5.7Z" />
        </svg>
      );
    case "INSTAGRAM":
      return (
        <svg viewBox="0 0 24 24" className={className} aria-hidden fill="none" stroke="currentColor" strokeWidth={2}>
          <rect x="3" y="3" width="18" height="18" rx="5.5" /><circle cx="12" cy="12" r="4.2" /><circle cx="17.4" cy="6.6" r="1.1" fill="currentColor" stroke="none" />
        </svg>
      );
    case "WEB":
      return (
        <svg viewBox="0 0 24 24" className={className} aria-hidden fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round">
          <circle cx="12" cy="12" r="9" /><path d="M3 12h18M12 3c2.5 2.6 3.8 5.6 3.8 9s-1.3 6.4-3.8 9M12 3C9.5 5.6 8.2 8.6 8.2 12s1.3 6.4 3.8 9" />
        </svg>
      );
    default:
      return <svg viewBox="0 0 24 24" className={className} aria-hidden fill="currentColor"><circle cx="12" cy="12" r="5" /></svg>;
  }
}

/** The glyph in the channel's color (plain). */
export function ChannelIcon({ channel, className = "h-4 w-4" }: { channel: ChannelKey; className?: string }) {
  return <span className="inline-flex" style={{ color: CHANNEL_COLOR[channel] ?? "var(--muted)" }}><Glyph channel={channel} className={className} /></span>;
}

/** A round colored badge with the white glyph (on avatars and in lists). */
export function ChannelDot({ channel, size = 16, className = "" }: { channel: ChannelKey; size?: number; className?: string }) {
  const bg = channel === "INSTAGRAM" ? "linear-gradient(45deg,#F58529,#DD2A7B 55%,#8134AF)" : channel === "FACEBOOK" ? "linear-gradient(135deg,#00B2FF,#006AFF 55%,#A033FF)" : CHANNEL_COLOR[channel] ?? "var(--faint)";
  return (
    <span className={`grid shrink-0 place-items-center rounded-full text-white ring-2 ring-surface ${className}`} style={{ width: size, height: size, background: bg }}>
      <Glyph channel={channel} className="h-[62%] w-[62%]" />
    </span>
  );
}
