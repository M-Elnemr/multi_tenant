/**
 * Ten designs for a shop with one to three products: the product IS the page. They are chosen only while the shop has 1-3 active products (the backend decides);
 * a shop that grows keeps the published design, which shows its first products as the showcase and the rest in a grid below.
 */
export const SMALL_TEMPLATE_IDS = ["solo", "vitrine", "capsule", "monolith", "pedestal", "spotlight", "launch", "signature", "curated", "origin"] as const;
export type SmallTemplateId = (typeof SMALL_TEMPLATE_IDS)[number];
export const isSmallTemplate = (v: unknown): v is SmallTemplateId => typeof v === "string" && (SMALL_TEMPLATE_IDS as readonly string[]).includes(v);

export type SmallStage = "stage" | "split" | "trio" | "list" | "story";
export type SmallLook = { stage: SmallStage; dark?: boolean; categories: string[]; swatch: [string, string, string]; header: "bar" | "center" | "pill" | "overlay" | "loud" };

export const SMALL_LOOKS: Record<SmallTemplateId, SmallLook> = {
  solo: { stage: "stage", header: "center", categories: ["general"], swatch: ["#ffffff", "#111111", "#111111"] },
  vitrine: { stage: "split", header: "bar", categories: ["fashion", "beauty"], swatch: ["#f7f3ee", "#25201b", "#a78358"] },
  capsule: { stage: "trio", header: "pill", categories: ["general"], swatch: ["#f4f6ff", "#17193a", "#5b5bf0"] },
  monolith: { stage: "stage", header: "bar", dark: true, categories: ["tech"], swatch: ["#08090c", "#f2f4f8", "#7cf2d2"] },
  pedestal: { stage: "stage", header: "center", categories: ["beauty", "home"], swatch: ["#fdf2ee", "#3a2019", "#e0765a"] },
  spotlight: { stage: "split", header: "bar", dark: true, categories: ["tech", "fashion"], swatch: ["#111318", "#eef0f5", "#ffcf3f"] },
  launch: { stage: "story", header: "loud", categories: ["general"], swatch: ["#fff7ef", "#2a1608", "#ff5a1f"] },
  signature: { stage: "split", header: "center", categories: ["fashion", "home"], swatch: ["#fbfaf7", "#1a1a1a", "#8b6f47"] },
  curated: { stage: "list", header: "bar", categories: ["general", "home"], swatch: ["#f5f5f2", "#1d2420", "#2f6f55"] },
  origin: { stage: "story", header: "overlay", categories: ["food", "beauty"], swatch: ["#f6efe4", "#2b2a1f", "#6b7d3c"] },
};
