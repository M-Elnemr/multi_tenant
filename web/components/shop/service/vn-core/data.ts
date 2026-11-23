import { cache } from "react";
import { backendJson } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";
import type { ShopBranch, ShopProfile } from "@/components/shop/types";
import type { ShopProduct } from "@/components/shop/product-card";
import { EMPTY_CONFIG, type VnConfig, type VnData, type VnGroup, type VnService } from "./types";

type Home = { featured: ShopProduct[]; newest: ShopProduct[]; bestSellers: ShopProduct[] };

/** Server-side data of a service site, from the same endpoints the old service pages use. A failed call never breaks the page: it degrades to empty. */
export const vnData = cache(async (): Promise<VnData & { failed: boolean }> => {
  const { t, locale } = await getT();
  let failed = false;
  const [prof, cfg, home, branches] = await Promise.all([
    backendJson<{ profile: ShopProfile }>("/shop/profile").then((r) => r.profile).catch(() => undefined),
    backendJson<VnConfig>("/shop/booking/config").catch(() => { failed = true; return EMPTY_CONFIG; }),
    backendJson<Home>(`/shop/home?lang=${locale}`).catch(() => ({ featured: [], newest: [], bestSellers: [] }) as Home),
    backendJson<ShopBranch[]>("/shop/branches").catch(() => [] as ShopBranch[]),
  ]);
  const products = [...(home.featured ?? []), ...(home.bestSellers ?? []), ...(home.newest ?? [])].filter((x, i, a) => a.findIndex((y) => y.id === x.id) === i);
  return { p: prof, cfg: { ...EMPTY_CONFIG, ...cfg, services: cfg.services ?? [], hours: cfg.hours ?? [] }, products, branches, t, locale, failed };
});

/** Services grouped by their category, in the shop's category order; the uncategorised ones last. */
export function groupServices(cfg: VnConfig): VnGroup[] {
  const order = new Map((cfg.categories ?? []).map((c, i) => [c.name, i]));
  const names = Array.from(new Set(cfg.services.map((s) => s.categoryName || "")));
  names.sort((a, b) => (a === "" ? 1 : b === "" ? -1 : (order.get(a) ?? 99) - (order.get(b) ?? 99)));
  return names.map((n) => ({ key: n || "_", name: n, items: cfg.services.filter((s) => (s.categoryName || "") === n) }));
}

export const isOffer = (s: VnService) => !!s.priceBeforeMinor && s.priceBeforeMinor > s.priceMinor;
export const serviceBody = (s: VnService) => s.shortDescription || s.description || "";
/** The saved hours of a weekday (ISO 1..7) as "10:00 – 18:00 · 20:00 – 22:00", or null when closed. */
export function dayRanges(cfg: VnConfig, weekday: number): string | null {
  const r = cfg.hours.filter((h) => h.weekday === weekday);
  return r.length ? r.map((x) => `${x.opens} – ${x.closes}`).join(" · ") : null;
}
/** Today's ISO weekday in the shop's timezone. */
export function todayWeekday(tz: string): number {
  try {
    const w = new Intl.DateTimeFormat("en-US", { timeZone: tz, weekday: "short" }).format(new Date());
    return ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"].indexOf(w) + 1 || 1;
  } catch { return 1; }
}
export const WEEK_ORDER = [6, 7, 1, 2, 3, 4, 5];   // Egypt's week starts on Saturday

export type DayCell = { iso: string; weekday: number; day: number; ranges: string | null; today: boolean };
/** The next `n` days in the shop's timezone with the booking hours of each (null = closed). Hours only; free TIMES always come from the slots endpoint. */
export function nextDays(cfg: VnConfig, n = 7): DayCell[] {
  const fmt = (ms: number) => new Intl.DateTimeFormat("en-CA", { timeZone: cfg.timezone, year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date(ms));
  const out: DayCell[] = [];
  for (let i = 0; i < n; i++) {
    const iso = fmt(Date.now() + i * 86400000);
    const d = new Date(iso + "T12:00:00Z");
    const weekday = d.getUTCDay() === 0 ? 7 : d.getUTCDay();
    out.push({ iso, weekday, day: d.getUTCDate(), ranges: dayRanges(cfg, weekday), today: i === 0 });
  }
  return out;
}
