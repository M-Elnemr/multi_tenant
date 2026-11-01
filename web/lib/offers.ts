/** A short line describing what an offer gives ("Buy 2, get 1 free"), shared by the dashboard and the storefront. */
type Cfg = { type: string; config: Record<string, unknown> };
/** A short line describing what the offer gives, used on cards and the preview. */
export function offerSummary(o: Cfg, t: (k: string, v?: Record<string, string | number>) => string, fmt: (m: number) => string): string {
  const c = o.config as Record<string, number | string | { minQty: number; percent: number }[]>;
  switch (o.type) {
    case "PERCENT_OFF": case "FLASH_SALE": return t("offer.sum.percent", { p: Number(c.percent ?? 0) });
    case "FIXED_OFF": return t("offer.sum.fixed", { a: fmt(Number(c.amountMinor ?? 0)) });
    case "BUY_X_GET_Y": return Number(c.getPercent ?? 100) >= 100 ? t("offer.sum.bxgyFree", { x: Number(c.buyQty ?? 1), y: Number(c.getQty ?? 1) }) : t("offer.sum.bxgy", { x: Number(c.buyQty ?? 1), y: Number(c.getQty ?? 1), p: Number(c.getPercent) });
    case "BUNDLE_PRICE": return t("offer.sum.bundle", { a: fmt(Number(c.bundlePriceMinor ?? 0)) });
    case "TIERED_QTY": { const tiers = (c.tiers as { minQty: number; percent: number }[]) ?? []; return tiers.map((x) => t("offer.sum.tier", { q: x.minQty, p: x.percent })).join(" · "); }
    case "ORDER_THRESHOLD": return t("offer.sum.threshold", { m: fmt(Number(c.minSubtotalMinor ?? 0)), v: c.discountType === "FIXED" ? fmt(Number(c.value ?? 0)) : `${c.value}%` });
    case "FREE_SHIPPING": return Number(c.minSubtotalMinor ?? 0) > 0 ? t("offer.sum.freeShipAbove", { m: fmt(Number(c.minSubtotalMinor)) }) : t("offer.sum.freeShip");
    case "GIFT_WITH_PURCHASE": return Number(c.minSubtotalMinor ?? 0) > 0 ? t("offer.sum.giftAbove", { m: fmt(Number(c.minSubtotalMinor)) }) : t("offer.sum.gift");
    case "FIRST_ORDER": return t("offer.sum.first", { v: c.discountType === "FIXED" ? fmt(Number(c.value ?? 0)) : `${c.value}%` });
    case "COUPON": return t("offer.sum.coupon", { v: c.discountType === "FIXED" ? fmt(Number(c.value ?? 0)) : `${c.value}%` });
  }
  return "";
}

