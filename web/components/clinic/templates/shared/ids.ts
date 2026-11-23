/** Design-revolution clinic templates (DR-A). The backend set and the DB CHECK are widened by the lead at integration. */
export const CN_IDS = ["cn-institute", "cn-boutique", "cn-medtech", "cn-family", "cn-atelier"] as const;
export type CnId = (typeof CN_IDS)[number];
export const isCnId = (v: unknown): v is CnId => typeof v === "string" && (CN_IDS as readonly string[]).includes(v);

/** Default palette of each template (picker swatch: bg, ink, accent). The real values live in each template's CSS as var(--c-*, default). */
export const CN_SWATCH: Record<CnId, [string, string, string]> = {
  "cn-institute": ["#f5f6f8", "#14304f", "#a47b2c"],
  "cn-boutique": ["#f3eee6", "#26211c", "#9b4a36"],
  "cn-medtech": ["#f3f7fc", "#0d1b2e", "#0a66ff"],
  "cn-family": ["#fff7ec", "#2c2a3a", "#ef6f5a"],
  "cn-atelier": ["#14120f", "#f1ece2", "#b9996a"],
};
