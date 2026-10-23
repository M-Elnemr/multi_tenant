import type { Translate } from "./i18n";

/** The parts of a failed call this file needs (ApiError has them all). */
export type ErrorLike = { status?: number; code?: string; message?: string; fields?: Record<string, string>; params?: Record<string, unknown> };

/** Standard validation sentences from the server, turned into the visitor's language. */
function rule(t: Translate, text: string): string {
  const num = (re: RegExp) => text.match(re);
  if (/must not be (blank|null|empty)/i.test(text)) return t("rule.required");
  let m = num(/size must be between (\d+) and (\d+)/i); if (m) return t("rule.length", { a: m[1], b: m[2] });
  if (/well-formed email/i.test(text)) return t("rule.email");
  m = num(/greater than or equal to (\d+)/i); if (m) return t("rule.min", { a: m[1] });
  m = num(/less than or equal to (\d+)/i); if (m) return t("rule.max", { a: m[1] });
  if (/must match/i.test(text)) return t("rule.format");
  return t("rule.invalid");
}

/**
 * One clear sentence for any failure, in the language the visitor picked:
 *  - a known code -> its translated sentence (numbers and the other company's own words are filled in);
 *  - an unknown code in Arabic -> a sentence for the kind of failure (never the English server text);
 *  - in English the server's own sentence is fine.
 */
export function errorMessage(t: Translate, locale: string, error: unknown): string {
  const e = (error ?? {}) as ErrorLike;
  const vars: Record<string, string | number> = { detail: e.message ?? "" };
  for (const [k, v] of Object.entries(e.params ?? {})) vars[k] = String(v);
  const known = (code?: string) => { const key = code ? `error.${code}` : ""; const s = key ? t(key, vars) : ""; return s && s !== key ? s : ""; };
  const byCode = known(e.code);
  if (byCode) return byCode;
  if (!e.status && !e.code) return known("NETWORK") || t("error.generic");   // a failure that never reached the server
  if (locale !== "ar" && e.message && !/^HTTP_|^ERROR$/.test(e.code ?? "")) return e.message;
  const byStatus = known(`HTTP_${e.status}`);
  if (byStatus) return byStatus;
  return known((e.status ?? 0) >= 500 ? "SERVER_ERROR" : "") || t("error.generic");
}

/** "Name: is required" lines for a rejected form (the field name in the visitor's language when we know it). */
export function fieldLines(t: Translate, fields: Record<string, string> | undefined): string[] {
  return Object.entries(fields ?? {}).map(([k, v]) => {
    const label = t(`field.${k.split(".").pop()}`);
    const name = label.startsWith("field.") ? "" : `${label}: `;
    return `${name}${rule(t, v)}`;
  });
}
