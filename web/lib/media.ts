export type Variant = "thumb" | "medium" | "original";

type Media = { url?: string | null; mediaBase?: string | null; mediaExt?: string | null };

/**
 * Where to load an image from. When the picture sits in the public bucket we link the immutable object directly (the edge proxy serves /media
 * straight from storage, no app round trip, cached forever). Otherwise we go through the API route, which streams it or redirects.
 */
export function mediaUrl(m: Media | null | undefined, variant: Variant): string | null {
  if (!m) return null;
  if (m.mediaBase && m.mediaExt) return `${m.mediaBase}-${variant}.${m.mediaExt}`;
  if (!m.url) return null;
  const u = m.url.startsWith("/api/v1/") ? m.url.replace("/api/v1/", "/api/bff/") : m.url;
  return u.includes("/api/bff/files/") ? `${u}${u.includes("?") ? "&" : "?"}variant=${variant}` : u;
}

export function fileUrl(fileId: string, variant: Variant = "thumb"): string {
  return `/api/bff/files/${fileId}/content?variant=${variant}`;
}
