/**
 * Site theme contract (design revolution). A website's colours are a fixed set of semantic tokens. Each template maps the tokens to its own look
 * (buttons, headings, cards, borders, decoration) in its CSS, so one brand palette reads differently in every template. Photos are never recoloured.
 * Persisted per site as `site_theme` JSON ({ tokens: Partial<Record<ThemeToken,string>> }); a missing token falls back to the template's own default.
 * Values are strict #rrggbb hex only: nothing else ever reaches the stylesheet (no CSS injection).
 */
export const THEME_TOKENS = ["primary", "secondary", "accent", "bg", "bg2", "surface", "text", "text2", "btnBg", "btnText", "border"] as const;
export type ThemeToken = (typeof THEME_TOKENS)[number];
export type ThemeTokens = Partial<Record<ThemeToken, string>>;
export type SiteTheme = { tokens: ThemeTokens };

/** The CSS custom property each token is published as. Templates read these (with their own default as fallback), e.g. `color: var(--c-text, #1a1a1a)`. */
export const TOKEN_VAR: Record<ThemeToken, string> = {
  primary: "--c-primary", secondary: "--c-secondary", accent: "--c-accent", bg: "--c-bg", bg2: "--c-bg-2", surface: "--c-surface",
  text: "--c-text", text2: "--c-text-2", btnBg: "--c-btn-bg", btnText: "--c-btn-text", border: "--c-border",
};

const HEX = /^#[0-9a-fA-F]{6}$/;
export const isHex = (v: unknown): v is string => typeof v === "string" && HEX.test(v);

/** Keeps only known tokens with valid hex values; lower-cases them. Anything else is dropped. */
export function sanitizeTokens(input: unknown): ThemeTokens {
  const out: ThemeTokens = {};
  if (!input || typeof input !== "object") return out;
  for (const k of THEME_TOKENS) {
    const v = (input as Record<string, unknown>)[k];
    if (isHex(v)) out[k] = v.toLowerCase();
  }
  return out;
}

/** `:root`-scoped declarations for the tokens that are set, e.g. `--c-primary:#0f766e;`. Safe by construction (hex only). */
export function tokensToCss(tokens: ThemeTokens): string {
  return THEME_TOKENS.filter((k) => tokens[k]).map((k) => `${TOKEN_VAR[k]}:${tokens[k]};`).join("");
}

const rgb = (hex: string): [number, number, number] => [parseInt(hex.slice(1, 3), 16), parseInt(hex.slice(3, 5), 16), parseInt(hex.slice(5, 7), 16)];
const lin = (v: number) => { const c = v / 255; return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); };
export const luminance = (hex: string) => { const [r, g, b] = rgb(hex); return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b); };
/** WCAG contrast ratio between two #rrggbb colours (1..21). */
export function contrast(a: string, b: string): number {
  const la = luminance(a), lb = luminance(b);
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}

export type ThemeWarning = { pair: [ThemeToken, ThemeToken]; ratio: number; min: number };
/** Pairs that must stay readable, with their minimum ratio. The editor warns on these; the backend rejects below the hard floor (3:1). */
const PAIRS: [ThemeToken, ThemeToken, number][] = [["text", "bg", 4.5], ["text", "surface", 4.5], ["text2", "bg", 3], ["btnText", "btnBg", 4.5]];

/** Checks a palette merged over the template's defaults. */
export function checkContrast(tokens: ThemeTokens, defaults: ThemeTokens): ThemeWarning[] {
  const m = { ...defaults, ...tokens };
  const out: ThemeWarning[] = [];
  for (const [a, b, min] of PAIRS) {
    if (!m[a] || !m[b]) continue;
    const ratio = contrast(m[a]!, m[b]!);
    if (ratio < min) out.push({ pair: [a, b], ratio: Math.round(ratio * 100) / 100, min });
  }
  return out;
}

/** Picks black or white text for a background, whichever is more readable. */
export const readableOn = (bg: string) => (contrast(bg, "#ffffff") >= contrast(bg, "#111111") ? "#ffffff" : "#111111");

const mix = (a: string, b: string, t: number) => {
  const x = rgb(a), y = rgb(b);
  return "#" + x.map((v, i) => Math.round(v + (y[i] - v) * t).toString(16).padStart(2, "0")).join("");
};

/**
 * Simple mode: from a primary colour (and optional secondary), derive a harmonious full palette. `dark` flips to a dark scheme.
 * The result is a starting point the merchant previews before applying; the template still decides how each token is used.
 */
export function deriveFromBrand(primary: string, opts: { secondary?: string; dark?: boolean } = {}): ThemeTokens {
  if (!isHex(primary)) return {};
  const dark = !!opts.dark;
  const bg = dark ? mix("#0b0d12", primary, 0.06) : mix("#ffffff", primary, 0.04);
  const surface = dark ? mix(bg, "#ffffff", 0.06) : "#ffffff";
  const text = dark ? "#f3f4f6" : mix("#111111", primary, 0.12);
  const btnBg = primary;
  return {
    primary, secondary: opts.secondary && isHex(opts.secondary) ? opts.secondary : mix(primary, dark ? "#ffffff" : "#000000", 0.35),
    accent: opts.secondary && isHex(opts.secondary) ? opts.secondary : mix(primary, "#ffffff", 0.3),
    bg, bg2: mix(bg, primary, dark ? 0.1 : 0.07), surface, text, text2: mix(text, bg, 0.38),
    btnBg, btnText: readableOn(btnBg), border: mix(bg, dark ? "#ffffff" : "#000000", dark ? 0.14 : 0.12),
  };
}
