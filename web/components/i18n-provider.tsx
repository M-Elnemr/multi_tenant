"use client";

import { createContext, useContext, useMemo } from "react";
import { makeT, type Locale, type Translate } from "@/lib/i18n";

type Ctx = { locale: Locale; t: Translate; currency: string; timezone: string; tenantType: "STORE" | "CLINIC" | "LAWYER" | null; siteName: string; whiteLabel: boolean };
const I18nContext = createContext<Ctx>({ locale: "ar", t: makeT("ar"), currency: "EGP", timezone: "Africa/Cairo", tenantType: null, siteName: "", whiteLabel: false });

export function I18nProvider({ locale, currency = "EGP", timezone = "Africa/Cairo", tenantType = null, siteName = "", whiteLabel = false, children }: { locale: Locale; currency?: string; timezone?: string; tenantType?: "STORE" | "CLINIC" | "LAWYER" | null; siteName?: string; whiteLabel?: boolean; children: React.ReactNode }) {
  const value = useMemo(() => ({ locale, t: makeT(locale), currency, timezone, tenantType, siteName, whiteLabel }), [locale, currency, timezone, tenantType, siteName, whiteLabel]);
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n() {
  return useContext(I18nContext);
}

export function useT() {
  return useContext(I18nContext).t;
}
