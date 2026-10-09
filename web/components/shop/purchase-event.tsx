"use client";

import { useEffect } from "react";
import { trackPurchase } from "./tracking";

/** Fires the "purchase" ad event once per order (remembered in sessionStorage so a refresh does not count it twice). The amount comes from the last checkout. */
export function PurchaseEvent({ orderNumber }: { orderNumber: string }) {
  useEffect(() => {
    try {
      const key = `purchase:${orderNumber}`;
      if (sessionStorage.getItem(key)) return;
      sessionStorage.setItem(key, "1");
      const total = Number(sessionStorage.getItem("lastOrderTotal") ?? "0");
      trackPurchase(total, sessionStorage.getItem("lastOrderCurrency") ?? "EGP");
    } catch { /* storage unavailable */ }
  }, [orderNumber]);
  return null;
}
