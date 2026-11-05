export const TEMPLATE_IDS = ["original", "atelier", "street", "grove", "midnight", "maison", "feast", "bloom", "oud", "souq"] as const;
export type TemplateId = (typeof TEMPLATE_IDS)[number];
export const isTemplate = (v: unknown): v is TemplateId => typeof v === "string" && (TEMPLATE_IDS as readonly string[]).includes(v);
