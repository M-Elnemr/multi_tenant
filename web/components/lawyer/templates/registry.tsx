import type { LnId } from "./shared/ctx";
import type { LnTemplate } from "./types";
import { lnGlobal } from "./ln-global";
import { lnBoutique } from "./ln-boutique";
import { lnAdvisory } from "./ln-advisory";

/**
 * The five ln-* lawyer templates. ln-global is finished; the other four are not written yet and fall back to ln-global's composition
 * (so a site that picks one still renders) until each one lands in its own commit.
 */
export const LN_TEMPLATES: Record<LnId, LnTemplate> = {
  "ln-global": lnGlobal,
  "ln-boutique": lnBoutique,
  "ln-advisory": lnAdvisory,
  "ln-digital": lnGlobal,
  "ln-heritage": lnGlobal,
};
