import { headers } from "next/headers";
import { sanitizeTokens, tokensToCss, type SiteTheme } from "./theme";
import { decodeThemeParam } from "./theme-param";

/** Anything a public profile payload may carry: the owner's colours, or null/absent (the design's own colours). */
export type WithSiteTheme = { siteTheme?: SiteTheme | null };

/** The CSS custom properties this request renders with: the editor's draft (preview frame only), else the saved theme. Hex-only by construction. */
export async function themeCss(saved?: SiteTheme | null): Promise<string> {
  const h = await headers();
  if (h.get("x-site-preview") === "1") {
    const d = h.get("x-site-theme");
    if (d === "off") return "";
    if (d) return tokensToCss(decodeThemeParam(d));
  }
  return saved?.tokens ? tokensToCss(sanitizeTokens(saved.tokens)) : "";
}

/** Server-rendered scoped style, so a themed site never flashes its default colours. Renders nothing when there is no theme. */
export async function SiteThemeStyle({ theme }: { theme?: SiteTheme | null }) {
  const css = await themeCss(theme);
  if (!css) return null;
  return <style data-site-theme dangerouslySetInnerHTML={{ __html: `:root{${css}}` }} />;
}
