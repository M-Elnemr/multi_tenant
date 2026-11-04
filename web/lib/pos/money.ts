/** Till money in whole minor units (piasters), the same arithmetic the server and the tax invoice use. */
import type { CartLine, Shop } from "./db";

export type Totals = { subtotalMinor: number; lineDiscountsMinor: number; discountMinor: number; totalMinor: number; vatMinor: number; pieces: number };

export function lineTotal(l: Pick<CartLine, "unitMinor" | "qty" | "discountMinor">): number {
  return Math.max(0, l.unitMinor * l.qty - Math.min(l.discountMinor, l.unitMinor * l.qty));
}

/** Prices include VAT when the shop says so: the VAT shown is the part of the total that is VAT (no VAT line otherwise), as on the tax invoice. */
export function totals(lines: CartLine[], cartDiscountMinor: number, shop: Pick<Shop, "vatIncluded" | "vatPercent"> | null): Totals {
  const subtotalMinor = lines.reduce((n, l) => n + l.unitMinor * l.qty, 0);
  const lineDiscountsMinor = lines.reduce((n, l) => n + Math.min(l.discountMinor, l.unitMinor * l.qty), 0);
  const discountMinor = Math.min(subtotalMinor, lineDiscountsMinor + Math.max(0, cartDiscountMinor));
  const totalMinor = subtotalMinor - discountMinor;
  const pct = shop?.vatPercent ?? 0;
  const vatMinor = shop?.vatIncluded && pct > 0 ? Math.round((totalMinor * pct) / (100 + pct)) : 0;
  return { subtotalMinor, lineDiscountsMinor, discountMinor, totalMinor, vatMinor, pieces: lines.reduce((n, l) => n + l.qty, 0) };
}

/** "12.5" → 1250; empty or bad → 0. Arabic digits are read too. */
export function toMinor(text: string): number {
  const s = text.replace(/[٠-٩]/g, (d) => String("٠١٢٣٤٥٦٧٨٩".indexOf(d))).replace(/[٫,]/g, ".").replace(/[^\d.]/g, "");
  const n = Number(s);
  return Number.isFinite(n) && n > 0 ? Math.round(n * 100) : 0;
}

/** Notes and coins, largest first, for counting the drawer and for quick cash buttons. */
export function denominations(currency: string): number[] {
  if (currency === "EGP") return [20000, 10000, 5000, 2000, 1000, 500, 100, 50, 25];
  if (currency === "SAR" || currency === "AED") return [50000, 10000, 5000, 1000, 500, 100, 50, 25];
  return [10000, 5000, 2000, 1000, 500, 100, 50, 25, 10, 5, 1];
}

/** Round amounts a customer is likely to hand over for this total (the exact amount first). */
export function quickCash(totalMinor: number, currency: string): number[] {
  const notes = denominations(currency).filter((d) => d >= 1000);
  const out = new Set<number>([totalMinor]);
  for (const d of [...notes].reverse()) {
    const up = Math.ceil(totalMinor / d) * d;
    if (up > totalMinor) out.add(up);
    if (out.size >= 5) break;
  }
  return [...out].sort((a, b) => a - b).slice(0, 5);
}

/** The whole-sale discount shared over the lines by their value, the last line taking the rounding: exactly as the server records it. */
export function allocate<T extends Pick<CartLine, "unitMinor" | "qty" | "discountMinor">>(lines: T[], cartDiscountMinor: number): (T & { discountMinor: number })[] {
  const gross = lines.map((l) => l.unitMinor * l.qty - Math.min(l.discountMinor, l.unitMinor * l.qty));
  const net = gross.reduce((a, b) => a + b, 0);
  const cart = Math.max(0, Math.min(cartDiscountMinor, net));
  let given = 0;
  return lines.map((l, i) => {
    let share = i === lines.length - 1 ? cart - given : net === 0 ? 0 : Math.floor((cart * gross[i]) / net);
    share = Math.max(0, Math.min(share, gross[i]));
    given += share;
    return { ...l, discountMinor: Math.min(l.discountMinor, l.unitMinor * l.qty) + share };
  });
}
