import type { ShopBranch, ShopProfile } from "@/components/shop/types";
import type { ShopProduct } from "@/components/shop/product-card";
import type { Locale } from "@/lib/i18n";

export type T = (k: string, v?: Record<string, string | number>) => string;
export type VnAddon = { id: string; name: string; priceMinor: number; durationMinutes: number };
export type VnStaff = { id: string; name: string; imageFileId?: string | null };
export type VnService = {
  id: string; name: string; description?: string | null; shortDescription?: string | null; durationMinutes: number; priceMinor: number; priceBeforeMinor?: number | null; currency: string;
  categoryId?: string | null; categoryName?: string | null; imageFileId?: string | null; gallery?: string[]; locationMode?: "SHOP" | "HOME" | "BOTH"; serviceArea?: string | null;
  instructions?: string | null; cancellationPolicy?: string | null; requiredFields?: { key: string; label: string; required?: boolean }[]; addons?: VnAddon[]; staff?: VnStaff[];
};
/** What GET /shop/booking/config returns. Hours are ISO weekdays (1 = Monday ... 7 = Sunday). */
export type VnConfig = {
  services: VnService[]; hours: { weekday: number; opens: string; closes: string }[]; autoConfirm: boolean; maxDaysAhead: number; timezone: string; cancelDeadlineHours?: number;
  categories?: { id: string; name: string }[]; specialDays?: { date: string; opens: string; closes: string }[];
};
export const EMPTY_CONFIG: VnConfig = { services: [], hours: [], autoConfirm: false, maxDaysAhead: 30, timezone: "Africa/Cairo" };

/** Everything a template's pages need, fetched once per request on the server. */
export type VnData = { p: ShopProfile | undefined; cfg: VnConfig; products: ShopProduct[]; branches: ShopBranch[]; t: T; locale: Locale; failed?: boolean };

export type VnGroup = { key: string; name: string; items: VnService[] };
export type BookInit = { service?: string; date?: string; time?: string };

/** What a template's header, footer and mobile bar receive from the storefront layout. Only plain data (the header is a client component). */
export type ChromeProps = {
  name: string; logoUrl: string | null; announcement?: string; closedMessage?: string; isOpen: boolean; hasProducts: boolean; hasBooking: boolean;
  whatsapp?: string; phone?: string;
};
export type FooterProps = ChromeProps & { data: VnData; poweredLabel: string; poweredName: string };
export type DetailProps = VnData & { service: VnService };
export type BookProps = VnData & { init: BookInit };
