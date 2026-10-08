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

/** "2 years 6 months" / "2 سنة و 6 شهور" from the age the server computes. Plain digits on purpose (same as phone numbers). */
export function ageText(years: number | null | undefined, months: number | null | undefined, locale: Locale): string {
  if (years === null || years === undefined) return "-";
  const m = months ?? 0;
  if (locale === "ar") {
    const y = years > 0 ? `${years} ${years === 1 ? "سنة" : years === 2 ? "سنتان" : years <= 10 ? "سنوات" : "سنة"}` : "";
    const mo = m > 0 ? `${m} ${m === 1 ? "شهر" : m === 2 ? "شهران" : "شهور"}` : "";
    return y && mo ? `${y} و ${mo}` : y || mo || "0";
  }
  const y = years > 0 ? `${years} ${years === 1 ? "year" : "years"}` : "";
  const mo = m > 0 ? `${m} ${m === 1 ? "month" : "months"}` : "";
  return y && mo ? `${y} ${mo}` : y || mo || "0";
}

export function toMinor(input: string): number {
  const n = Number(input.replace(",", "."));
  return Number.isFinite(n) ? Math.round(n * 100) : 0;
}

export function fromMinor(minor: number | null | undefined): string {
  return minor === null || minor === undefined ? "" : (minor / 100).toFixed(2);
}
