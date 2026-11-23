/**
 * The ten website designs of a law office: the three original ones (kept, so no published office changes) and seven more. They differ in composition (how the hero is built) and in palette,
 * type and finish (lawyer.css, scoped to .lw[data-tpl]). The list must match LegalProfileService.TEMPLATES and V110.
 */
export const LAWYER_TEMPLATE_IDS = ["classic", "modern", "heritage", "chambers", "justice", "marble", "gavel", "minimal", "emerald", "nocturne", "ln-global", "ln-boutique", "ln-advisory", "ln-digital", "ln-heritage"] as const; // DR-B: the last five are the new multi-page templates (components/lawyer/templates)
export type LawyerTemplateId = (typeof LAWYER_TEMPLATE_IDS)[number];
export const isLawyerTemplate = (v: unknown): v is LawyerTemplateId => typeof v === "string" && (LAWYER_TEMPLATE_IDS as readonly string[]).includes(v);

/** split: text and a framed photo side by side. center: an emblem and centred text, no photo needed. editorial: a large name with a vertical rule and the figures. band: a headline over a wide photo band. */
export type LawyerHero = "split" | "center" | "editorial" | "band";
export type LawyerAreas = "cards" | "rows" | "tiles" | "chips";
export type LawyerServices = "cards" | "table";
export type LawyerProcess = "cards" | "timeline" | "inline";
export type LawyerReviews = "grid" | "single" | "wall";
export type LawyerHeader = "bar" | "center" | "slim";
export type LawyerLook = { hero: LawyerHero; areas: LawyerAreas; services: LawyerServices; process: LawyerProcess; reviews: LawyerReviews; header: LawyerHeader; bg: string; accent: string };
export const LAWYER_LOOKS: Record<LawyerTemplateId, LawyerLook> = {
  classic: { hero: "split", areas: "cards", services: "cards", process: "cards", reviews: "grid", header: "bar", bg: "linear-gradient(135deg,#0b1d3a,#1e3a5f)", accent: "#c9a227" },
  modern: { hero: "split", areas: "tiles", services: "table", process: "inline", reviews: "grid", header: "bar", bg: "linear-gradient(135deg,#f8fafc,#e2e8f0)", accent: "#0f766e" },
  heritage: { hero: "split", areas: "rows", services: "cards", process: "timeline", reviews: "single", header: "center", bg: "linear-gradient(135deg,#2b1d14,#5c3b23)", accent: "#d4a373" },
  chambers: { hero: "editorial", areas: "rows", services: "table", process: "timeline", reviews: "wall", header: "center", bg: "linear-gradient(135deg,#1c2430,#394556)", accent: "#c8b07a" },
  justice: { hero: "center", areas: "chips", services: "cards", process: "timeline", reviews: "single", header: "center", bg: "linear-gradient(135deg,#4a0f1c,#7a1f33)", accent: "#e6c27a" },
  marble: { hero: "band", areas: "tiles", services: "table", process: "inline", reviews: "wall", header: "slim", bg: "linear-gradient(135deg,#f3f1ec,#d9d5cc)", accent: "#1a1a1a" },
  gavel: { hero: "split", areas: "cards", services: "table", process: "cards", reviews: "grid", header: "slim", bg: "linear-gradient(135deg,#17181c,#2b2d34)", accent: "#d64545" },
  minimal: { hero: "center", areas: "rows", services: "table", process: "inline", reviews: "single", header: "slim", bg: "linear-gradient(135deg,#ffffff,#f1f1f1)", accent: "#111111" },
  emerald: { hero: "editorial", areas: "tiles", services: "cards", process: "timeline", reviews: "wall", header: "bar", bg: "linear-gradient(135deg,#0c3a31,#14604f)", accent: "#e1c46d" },
  nocturne: { hero: "band", areas: "chips", services: "cards", process: "inline", reviews: "grid", header: "bar", bg: "linear-gradient(135deg,#120f2b,#2a2160)", accent: "#a99bff" },
  // DR-B: look entries for the new templates (they render through components/lawyer/templates; only bg/accent are used by pickers)
  "ln-global": { hero: "editorial", areas: "rows", services: "table", process: "inline", reviews: "grid", header: "bar", bg: "linear-gradient(135deg,#f6f5f2,#e8e4dc)", accent: "#c8341d" },
  "ln-boutique": { hero: "split", areas: "cards", services: "cards", process: "cards", reviews: "single", header: "center", bg: "linear-gradient(135deg,#fbf1e8,#eed6c4)", accent: "#5b2447" },
  "ln-advisory": { hero: "split", areas: "tiles", services: "table", process: "timeline", reviews: "grid", header: "slim", bg: "linear-gradient(135deg,#eef2f7,#cfd9e6)", accent: "#12467a" },
  "ln-digital": { hero: "center", areas: "chips", services: "cards", process: "inline", reviews: "grid", header: "slim", bg: "linear-gradient(135deg,#f3f1ff,#d9fbef)", accent: "#5b4bff" },
  "ln-heritage": { hero: "editorial", areas: "rows", services: "table", process: "timeline", reviews: "single", header: "center", bg: "linear-gradient(135deg,#f3eee3,#ded2b8)", accent: "#6e1423" },
};
