import { THEME_TOKENS, type ThemeToken, type ThemeTokens } from "./theme";

/**
 * Registry of every website design the platform knows: its business kind, whether the picker offers it (active) or it only keeps existing sites
 * working (retired), and for the new designs an art-directed default palette of the 11 theme tokens. The palette is what the template's CSS falls back to
 * (`var(--c-primary, <this>)`), what "Reset to the design's colours" restores, and the starting point of the colour editor.
 */
export type SiteKind = "store" | "small" | "service" | "clinic" | "lawyer";
export type FullPalette = Record<ThemeToken, string>;
export type PaletteEntry = { kind: SiteKind; active: boolean; tokens?: FullPalette };

// order: primary, secondary, accent, bg, bg2, surface, text, text2, btnBg, btnText, border
const P = (v: string): FullPalette => Object.fromEntries(THEME_TOKENS.map((k, i) => [k, v.split(" ")[i]])) as FullPalette;
const n = (kind: SiteKind, v: string): PaletteEntry => ({ kind, active: true, tokens: P(v) });
const old = (kind: SiteKind, ids: string[]): [string, PaletteEntry][] => ids.map((id) => [id, { kind, active: false }]);
/** Older designs the owner chose to keep offered next to the new ones. They keep their own look (they do not read the colour tokens) and no default palette. */
const kept = (kind: SiteKind, ids: string[]): [string, PaletteEntry][] => ids.map((id) => [id, { kind, active: true }]);

export const TEMPLATE_PALETTES: Record<string, PaletteEntry> = {
  // clinics
  "cn-institute": n("clinic", "#12356b #0b2447 #c8a24a #ffffff #f1f5fb #ffffff #0b1b33 #4a5b78 #12356b #ffffff #d9e2f0"),
  "cn-boutique": n("clinic", "#7a4b3a #3b2a22 #b08968 #fbf7f2 #f3ebe1 #fffdf9 #2a1f1a #6b5a50 #3b2a22 #fbf7f2 #e6dccf"),
  "cn-medtech": n("clinic", "#0891b2 #0e3a4a #22d3ee #f6fbfd #e6f4f8 #ffffff #0a2530 #4b6674 #0e7490 #ffffff #cfe6ee"),
  "cn-family": n("clinic", "#e8744f #7a3b2a #f6b73c #fffaf5 #fdeee0 #ffffff #3a241b #7a5d50 #bd4a28 #ffffff #f3dccb"),
  "cn-atelier": n("clinic", "#1c1c1c #8c7b66 #b89b6b #f7f5f1 #edeae4 #ffffff #1c1c1c #6a655d #1c1c1c #f7f5f1 #ded9d0"),
  // lawyers
  "ln-global": { ...n("lawyer", "#0f2a4a #1f3d63 #b08d3c #ffffff #f3f5f8 #ffffff #0e1a2b #4d5b70 #0f2a4a #ffffff #dce2ea"), active: false },
  "ln-boutique": { ...n("lawyer", "#5b2333 #3a1620 #c19a5b #fbf8f5 #f2ebe4 #fffdfb #2a1a1e #6e5a5f #5b2333 #ffffff #e5d9d0"), active: false },
  "ln-advisory": { ...n("lawyer", "#234a7d #3b4a5f #5b8bc9 #f8f9fb #eceff4 #ffffff #1a2433 #556070 #234a7d #ffffff #d8dee8"), active: false },
  "ln-digital": { ...n("lawyer", "#4f46e5 #1e1b4b #06b6d4 #ffffff #f5f6ff #ffffff #111827 #5b6478 #4f46e5 #ffffff #e3e6f5"), active: false },
  "ln-heritage": { ...n("lawyer", "#2f3b2f #5a4630 #a68a4b #f6f1e6 #ece4d2 #fbf8f0 #231f16 #675d49 #2f3b2f #f6f1e6 #ddd2b9"), active: false },
  // large shops
  "sn-editorial": n("store", "#141210 #6b5d4d #b45a3c #faf8f5 #f0ece6 #ffffff #141210 #6a635a #141210 #faf8f5 #e3ddd3"),
  "sn-market": n("store", "#2563eb #0f172a #f59e0b #f3f5f9 #e8ecf3 #ffffff #0f172a #546178 #2563eb #ffffff #dbe1ec"),
  "sn-minimal": n("store", "#1a1a1a #888888 #1a1a1a #ffffff #f6f6f6 #ffffff #1a1a1a #6d6d6d #1a1a1a #ffffff #e8e8e8"),
  "sn-bold": n("store", "#ff3d2e #111111 #ffe14d #fffbf0 #ffefc2 #ffffff #111111 #55504a #111111 #ffe14d #111111"),
  "sn-lifestyle": n("store", "#4d7c5a #2d4a38 #e0a458 #f7f5ee #ebe8dc #fffefa #24302a #5f6c63 #3f6b4d #ffffff #ddd9c9"),
  // small shops (1-3 products)
  "pn-flagship": n("small", "#1d1d1f #6e6e73 #0071e3 #ffffff #f5f5f7 #ffffff #1d1d1f #6e6e73 #0071e3 #ffffff #e5e5ea"),
  "pn-showcase": n("small", "#c9a24b #8a6f2e #e6c97a #0e0d0b #181612 #1d1a15 #f2ead9 #b3a98f #c9a24b #0e0d0b #34301f"),
  "pn-trio": n("small", "#c2410c #7c2d12 #f59e0b #fffaf4 #fdeedd #ffffff #2b1a10 #7a6150 #c2410c #ffffff #f2dcc4"),
  "pn-launch": n("small", "#7c3aed #2e1065 #f472b6 #fbf9ff #f0e9ff #ffffff #1b1033 #62577d #7c3aed #ffffff #e4d9fb"),
  "pn-brand": n("small", "#0f766e #134e4a #14b8a6 #fcfcfa #f1f3ef #ffffff #15201e #5a6865 #0f766e #ffffff #e2e7e3"),
  // service businesses
  "vn-wellness": n("service", "#a9746e #4a2f2c #d9b8a0 #fbf5f1 #f3e6df #fffdfb #35221f #7a625d #8c5a54 #ffffff #ead8cf"),
  "vn-homepro": n("service", "#0b5fa5 #0a2a47 #f28c28 #f6f9fc #e7eff7 #ffffff #0c2238 #4a6078 #f28c28 #1a1204 #d6e2ef"),
  "vn-repair": n("service", "#0e7490 #1e293b #fbbf24 #f1f4f7 #e2e8ee #ffffff #111c28 #526173 #1e293b #fbbf24 #cfd8e1"),
  "vn-studio": n("service", "#5b7c6a #2f4538 #e8b4a0 #f8f7f3 #ecebe3 #ffffff #202a25 #5e6a63 #3f6050 #ffffff #dfdfd3"),
  "vn-multi": n("service", "#0f766e #0f172a #f59e0b #f7f9f9 #e9f0ef #ffffff #10201f #52625f #0f766e #ffffff #d6e2e0"),
  // retired: still valid in the database and still render exactly as before, never offered by the picker
  ...Object.fromEntries([
    ...old("store", ["original", "atelier", "street", "grove", "midnight", "maison", "feast", "oud", "souq"]),
    ...kept("store", ["bloom"]),   // kept on request (2026-11-23)
    ...old("small", ["solo", "vitrine", "capsule", "monolith", "pedestal", "spotlight", "launch", "signature", "curated", "origin"]),
    ...old("service", ["lumiere", "barbershop", "homefix", "fixit", "aqua", "sparkle", "garage", "pro", "slot", "bazaar"]),
    ...old("clinic", ["luxe", "persona", "dental", "derma", "center"]),
    ...kept("clinic", ["clear"]),   // kept on request (2026-11-23): the clinic now offers 5 new designs + this one
    ...old("lawyer", ["classic", "modern", "heritage", "chambers", "justice", "marble", "gavel", "minimal", "emerald", "nocturne"]),
  ]),
};

export const isKnownTemplate = (id: string) => id in TEMPLATE_PALETTES;
export const isActiveTemplate = (id: string) => TEMPLATE_PALETTES[id]?.active === true;
/** Active designs of one business kind, in registry order. */
export const activeTemplates = (kind: SiteKind): string[] => Object.entries(TEMPLATE_PALETTES).filter(([, e]) => e.kind === kind && e.active).map(([id]) => id);
/** The design's own palette, or undefined for retired designs (their CSS keeps its own colours). */
export const defaultTokens = (id: string | null | undefined): FullPalette | undefined => (id ? TEMPLATE_PALETTES[id]?.tokens : undefined);

/** Curated alternatives the editor offers next to the design's own palette. Each is a complete, contrast-checked set. */
export const CURATED_PALETTES: { id: string; tokens: ThemeTokens }[] = [
  { id: "ocean", tokens: P("#0369a1 #0c4a6e #38bdf8 #f8fbfd #e6f2f8 #ffffff #0b2230 #4c6475 #0369a1 #ffffff #d3e5ef") },
  { id: "forest", tokens: P("#166534 #14532d #a3e635 #f7faf5 #e8f1e4 #ffffff #14231a #55695b #166534 #ffffff #d6e4d3") },
  { id: "sunset", tokens: P("#c2410c #7c2d12 #fb923c #fffaf5 #ffeedd #ffffff #2b1608 #7a5a44 #c2410c #ffffff #f5dcc3") },
  { id: "berry", tokens: P("#9d174d #500724 #f472b6 #fff8fb #fde8f1 #ffffff #2d0a1a #7a5566 #9d174d #ffffff #f4d3e1") },
  { id: "royal", tokens: P("#4338ca #1e1b4b #fbbf24 #fafaff #ececff #ffffff #15133a #5a5a82 #4338ca #ffffff #dcdcf5") },
  { id: "graphite", tokens: P("#e5e7eb #9ca3af #38bdf8 #0f1115 #171a20 #1d2128 #f3f4f6 #a3aab5 #e5e7eb #0f1115 #2a2f38") },
  { id: "midnight", tokens: P("#34d399 #10b981 #fbbf24 #0b0d12 #12161e #171c26 #f1f5f9 #9aa6b8 #34d399 #05261b #232b38") },
  { id: "sand", tokens: P("#8a6a3d #4a3a22 #c9a24b #faf6ee #f0e8d8 #fffdf8 #2a2114 #6f6247 #5e4724 #ffffff #e4d9c1") },
];
