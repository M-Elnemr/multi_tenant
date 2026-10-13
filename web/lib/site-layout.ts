export type SiteLayout = { order?: string[]; hidden?: string[] } | null | undefined;

export const CLINIC_SECTIONS = ["hero", "about", "services", "doctors", "how", "visit", "gallery", "insurance", "faq", "contact"] as const;
export const STORE_SECTIONS = ["banners", "trust", "categories", "featured", "offers", "bestSellers", "newest", "whatsapp", "branches"] as const;

export type Arranged<T extends string> = { key: T; hidden: boolean };

/** The owner's order first (unknown keys ignored), every remaining section after it in its default place. Hidden sections only show in edit mode. */
export function arrange<T extends string>(defaults: readonly T[], layout: SiteLayout, edit: boolean): Arranged<T>[] {
  const hidden = new Set(layout?.hidden ?? []);
  const chosen = (layout?.order ?? []).filter((k): k is T => (defaults as readonly string[]).includes(k));
  const all = [...new Set([...chosen, ...defaults])];
  return all.map((key) => ({ key, hidden: hidden.has(key) })).filter((s) => edit || !s.hidden);
}
