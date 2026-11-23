import adds from "@/i18n-additions/ws-a.json";
import { makeT, type Locale } from "@/lib/i18n";
import type { T } from "./kit";

/**
 * The cn.* strings live in web/i18n-additions/ws-a.json ([english, arabic] pairs) until the lead merges them into messages.ts.
 * Reading them here keeps the templates working before and after that merge; keys that are already in messages.ts are used as before.
 */
const A = adds as unknown as Record<string, [string, string]>;
const fill = (s: string, v?: Record<string, string | number>) => { if (v) for (const [k, x] of Object.entries(v)) s = s.replaceAll(`{${k}}`, String(x)); return s; };

export const cnDict = (locale: Locale): Record<string, string> => Object.fromEntries(Object.entries(A).map(([k, [en, ar]]) => [k, locale === "ar" ? ar : en]));

export function cnT(locale: Locale): T {
  const base = makeT(locale);
  const d = cnDict(locale);
  return (k, v) => (d[k] !== undefined ? fill(d[k], v) : base(k, v));
}

/** Same lookup for client components, from the dictionary the server passed down. */
export const cnTFrom = (d: Record<string, string>, base: T): T => (k, v) => (d[k] !== undefined ? fill(d[k], v) : base(k, v));
