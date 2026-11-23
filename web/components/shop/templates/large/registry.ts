import type { LargeId } from "./ids";
import { editorialSet } from "./editorial";
import { boldSet } from "./bold";
import { lifestyleSet } from "./lifestyle";
import { marketSet } from "./market";
import { minimalSet } from "./minimal";
import type { LargeSet } from "./types";

/** The large templates that are built so far (server side). A template id is only offered once it has an entry here. */
export const LARGE_SETS: Partial<Record<LargeId, LargeSet>> = { "sn-market": marketSet, "sn-editorial": editorialSet, "sn-minimal": minimalSet, "sn-bold": boldSet, "sn-lifestyle": lifestyleSet };
export const BUILT_LARGE_IDS = Object.keys(LARGE_SETS) as LargeId[];
