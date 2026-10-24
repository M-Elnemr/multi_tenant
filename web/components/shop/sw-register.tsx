"use client";

import { useEffect } from "react";

/** Registers the storefront service worker (production only) so repeat visits are fast and the shop opens from the cache on a weak connection. */
export function SwRegister() {
  useEffect(() => {
    if (process.env.NODE_ENV !== "production" || !("serviceWorker" in navigator)) return;
    navigator.serviceWorker.register("/sw.js").catch(() => null);
  }, []);
  return null;
}
