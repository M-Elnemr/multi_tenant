import { sanitizeTokens, THEME_TOKENS, type ThemeTokens } from "./theme";

/**
 * Preview drafts travel as `?theme=primary:0f766e,bg:ffffff` (hex without '#'), or `?theme=off` for the design's own colours.
 * They only ever apply inside the editor/gallery frame (`preview=1`); proxy.ts ignores `?theme=` from a normal visitor.
 */
export function encodeThemeParam(tokens: ThemeTokens): string {
  return THEME_TOKENS.filter((k) => tokens[k]).map((k) => `${k}:${tokens[k]!.slice(1)}`).join(",");
}

/** Hex-only, whitelisted tokens only (everything else is dropped by sanitizeTokens). */
export function decodeThemeParam(raw: string): ThemeTokens {
  const o: Record<string, string> = {};
  for (const part of raw.slice(0, 400).split(",")) {
    const [k, v] = part.split(":");
    if (k && v && /^[0-9a-fA-F]{6}$/.test(v)) o[k] = `#${v}`;
  }
  return sanitizeTokens(o);
}
