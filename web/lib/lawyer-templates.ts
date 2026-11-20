/**
 * The ten website designs of a law office: the three original ones (kept, so no published office changes) and seven more. They differ in composition (how the hero is built) and in palette,
 * type and finish (lawyer.css, scoped to .lw[data-tpl]). The list must match LegalProfileService.TEMPLATES and V110.
 */
export const LAWYER_TEMPLATE_IDS = ["classic", "modern", "heritage", "chambers", "justice", "marble", "gavel", "minimal", "emerald", "nocturne"] as const;
export type LawyerTemplateId = (typeof LAWYER_TEMPLATE_IDS)[number];
export const isLawyerTemplate = (v: unknown): v is LawyerTemplateId => typeof v === "string" && (LAWYER_TEMPLATE_IDS as readonly string[]).includes(v);

/** split: text and a framed photo side by side. center: an emblem and centred text, no photo needed. editorial: a large name with a vertical rule and the figures. band: a headline over a wide photo band. */
export type LawyerHero = "split" | "center" | "editorial" | "band";
export const LAWYER_LOOKS: Record<LawyerTemplateId, { hero: LawyerHero; bg: string; accent: string }> = {
  classic: { hero: "split", bg: "linear-gradient(135deg,#0b1d3a,#1e3a5f)", accent: "#c9a227" },
  modern: { hero: "split", bg: "linear-gradient(135deg,#f8fafc,#e2e8f0)", accent: "#0f766e" },
  heritage: { hero: "split", bg: "linear-gradient(135deg,#2b1d14,#5c3b23)", accent: "#d4a373" },
  chambers: { hero: "editorial", bg: "linear-gradient(135deg,#1c2430,#394556)", accent: "#c8b07a" },
  justice: { hero: "center", bg: "linear-gradient(135deg,#4a0f1c,#7a1f33)", accent: "#e6c27a" },
  marble: { hero: "band", bg: "linear-gradient(135deg,#f3f1ec,#d9d5cc)", accent: "#1a1a1a" },
  gavel: { hero: "split", bg: "linear-gradient(135deg,#17181c,#2b2d34)", accent: "#d64545" },
  minimal: { hero: "center", bg: "linear-gradient(135deg,#ffffff,#f1f1f1)", accent: "#111111" },
  emerald: { hero: "editorial", bg: "linear-gradient(135deg,#0c3a31,#14604f)", accent: "#e1c46d" },
  nocturne: { hero: "band", bg: "linear-gradient(135deg,#120f2b,#2a2160)", accent: "#a99bff" },
};
