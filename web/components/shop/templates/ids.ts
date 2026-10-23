export const TEMPLATE_IDS = ["original", "atelier", "souq", "midnight", "bloom", "oud"] as const;
export type TemplateId = (typeof TEMPLATE_IDS)[number];
export const isTemplate = (v: unknown): v is TemplateId => typeof v === "string" && (TEMPLATE_IDS as readonly string[]).includes(v);
