/** Large-catalog shop templates (design revolution, workstream C). Each one is its own component set under large/<id>/, not a composition of the shared sections. */
export const LARGE_TEMPLATE_IDS = ["sn-editorial", "sn-market", "sn-minimal", "sn-bold", "sn-lifestyle"] as const;
export type LargeId = (typeof LARGE_TEMPLATE_IDS)[number];
export const isLarge = (v: unknown): v is LargeId => typeof v === "string" && (LARGE_TEMPLATE_IDS as readonly string[]).includes(v);
