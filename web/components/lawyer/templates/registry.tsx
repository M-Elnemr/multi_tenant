import type { LnId } from "./shared/ctx";
import type { LnTemplate } from "./types";
import { lnGlobal } from "./ln-global";
import { lnBoutique } from "./ln-boutique";
import { lnAdvisory } from "./ln-advisory";
import { lnDigital } from "./ln-digital";
import { lnHeritage } from "./ln-heritage";

/**
 * The five ln-* lawyer templates. Each has its own shell (header and footer) and home page; ln-global also has its own inner pages, the other four
 * share the inner-page kit in shared/kit.tsx and restyle it with their own CSS.
 */
export const LN_TEMPLATES: Record<LnId, LnTemplate> = {
  "ln-global": lnGlobal,
  "ln-boutique": lnBoutique,
  "ln-advisory": lnAdvisory,
  "ln-digital": lnDigital,
  "ln-heritage": lnHeritage,
};
