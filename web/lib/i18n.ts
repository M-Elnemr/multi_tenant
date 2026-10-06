import { dictionaries, type Locale } from "./messages";

export type { Locale };

export function normalizeLocale(v: string | undefined | null): Locale {
  return v?.toLowerCase().startsWith("en") ? "en" : "ar";
}

export function dir(locale: Locale): "rtl" | "ltr" {
  return locale === "ar" ? "rtl" : "ltr";
}

export type Translate = (key: string, vars?: Record<string, string | number>) => string;

/** Missing keys fall back to English, then to the key itself (scripts/check-i18n.mjs fails the build before that can ship). */
export function makeT(locale: Locale): Translate {
  return (key, vars) => {
    let s = dictionaries[locale][key] ?? dictionaries.en[key] ?? key;
    if (vars) for (const [k, v] of Object.entries(vars)) s = s.replaceAll(`{${k}}`, String(v));
    return s;
  };
}
