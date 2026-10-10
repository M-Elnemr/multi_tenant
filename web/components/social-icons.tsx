import type { CSSProperties } from "react";

export type SocialKind = "facebook" | "instagram" | "tiktok" | "website";

/** Brand glyphs drawn on a 24x24 grid in white; the coloured round button around them carries the brand colour. */
export function SocialGlyph({ kind, className = "h-[18px] w-[18px]" }: { kind: SocialKind; className?: string }) {
  switch (kind) {
    case "facebook":
      return <svg viewBox="0 0 24 24" className={className} fill="currentColor" aria-hidden><path d="M13.5 22v-8.2h2.8l.5-3.3h-3.3V8.4c0-.9.4-1.7 1.8-1.7h1.6V3.8S15.6 3.5 14.3 3.5c-2.7 0-4.4 1.6-4.4 4.5v2.5H7v3.3h2.9V22h3.6Z" /></svg>;
    case "instagram":
      return <svg viewBox="0 0 24 24" className={className} fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" aria-hidden><rect x="3.5" y="3.5" width="17" height="17" rx="5" /><circle cx="12" cy="12" r="4" /><circle cx="17.2" cy="6.8" r=".6" fill="currentColor" /></svg>;
    case "tiktok":
      return <svg viewBox="0 0 24 24" className={className} fill="none" stroke="currentColor" strokeWidth={2.2} strokeLinecap="round" strokeLinejoin="round" aria-hidden><path d="M14.5 3v11.2a3.7 3.7 0 1 1-3.7-3.7M14.5 3c.3 2.7 2 4.4 4.9 4.6" /></svg>;
    default:
      return <svg viewBox="0 0 24 24" className={className} fill="none" stroke="currentColor" strokeWidth={1.9} strokeLinecap="round" aria-hidden><circle cx="12" cy="12" r="9" /><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18" /></svg>;
  }
}

const BRAND: Record<SocialKind, { label: string; bg: string }> = {
  facebook: { label: "Facebook", bg: "#1877F2" },
  instagram: { label: "Instagram", bg: "radial-gradient(circle at 30% 107%, #fdf497 0%, #fdf497 5%, #fd5949 45%, #d6249f 60%, #285AEB 90%)" },
  tiktok: { label: "TikTok", bg: "#111111" },
  website: { label: "Website", bg: "#475569" },
};

export type SocialUrls = { facebookUrl?: string; instagramUrl?: string; tiktokUrl?: string; websiteUrl?: string };

const ORDER: [keyof SocialUrls, SocialKind][] = [["facebookUrl", "facebook"], ["instagramUrl", "instagram"], ["tiktokUrl", "tiktok"], ["websiteUrl", "website"]];

export const hasSocial = (p: SocialUrls) => ORDER.some(([k]) => !!p[k]);

/** Round brand-coloured buttons (optionally with the network name beside the icon). */
export function SocialLinks({ p, withLabel = false, size = 40, className = "" }: { p: SocialUrls; withLabel?: boolean; size?: number; className?: string }) {
  return (
    <div className={`flex flex-wrap gap-2.5 ${className}`}>
      {ORDER.map(([key, kind]) => {
        const url = p[key];
        if (!url) return null;
        const b = BRAND[kind];
        const style: CSSProperties = { background: b.bg, height: size, minWidth: size };
        return (
          <a key={kind} href={url} target="_blank" rel="noopener noreferrer" aria-label={b.label} style={style}
            className={`inline-flex items-center justify-center gap-2 text-white shadow-sm transition hover:-translate-y-0.5 hover:shadow-lg active:scale-95 ${withLabel ? "rounded-full px-4 text-sm font-bold" : "rounded-full"}`}>
            <SocialGlyph kind={kind} />
            {withLabel && <span>{b.label}</span>}
          </a>
        );
      })}
    </div>
  );
}
