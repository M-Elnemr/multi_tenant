import type { Locale } from "./i18n";

const intl = (locale: Locale) => (locale === "ar" ? "ar-EG" : "en-GB");

/** Money is stored as integer minor units (piastres): 19999 -> 199.99 */
export function money(minor: number | null | undefined, currency: string, locale: Locale): string {
  if (minor === null || minor === undefined) return "-";
  return new Intl.NumberFormat(intl(locale), { style: "currency", currency, minimumFractionDigits: 2 }).format(minor / 100);
}

export function dateTime(iso: string | null | undefined, locale: Locale, timeZone = "Africa/Cairo"): string {
  if (!iso) return "-";
  return new Intl.DateTimeFormat(intl(locale), { dateStyle: "medium", timeStyle: "short", timeZone }).format(new Date(iso));
}

export function dateOnly(iso: string | null | undefined, locale: Locale, timeZone = "Africa/Cairo"): string {
  if (!iso) return "-";
  return new Intl.DateTimeFormat(intl(locale), { dateStyle: "medium", timeZone }).format(new Date(iso));
}

export function timeOnly(iso: string, locale: Locale, timeZone = "Africa/Cairo"): string {
  return new Intl.DateTimeFormat(intl(locale), { hour: "2-digit", minute: "2-digit", timeZone }).format(new Date(iso));
}

/** Calendar day (yyyy-mm-dd) of an instant in the clinic's timezone. */
export function dayKey(d: Date, timeZone = "Africa/Cairo"): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone, year: "numeric", month: "2-digit", day: "2-digit" }).format(d);
}

export function toMinor(input: string): number {
  const n = Number(input.replace(",", "."));
  return Number.isFinite(n) ? Math.round(n * 100) : 0;
}

export function fromMinor(minor: number | null | undefined): string {
  return minor === null || minor === undefined ? "" : (minor / 100).toFixed(2);
}
