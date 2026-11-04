"use client";

import { useEffect, useSyncExternalStore } from "react";
import { ME_KEY } from "./hooks";

/** Registers the site's service worker (production only): the shop opens fast, the till works with no internet, the dashboard can be read offline. */
export function SwRegister() {
  useEffect(() => {
    if (process.env.NODE_ENV !== "production" || !("serviceWorker" in navigator)) return;
    navigator.serviceWorker.register("/sw.js").catch(() => null);
  }, []);
  return null;
}

/** On logout: the dashboard's offline copy and the last-seen profile are wiped from this browser. */
export function forgetPrivateCopies() {
  try { localStorage.removeItem(ME_KEY); } catch { /* private mode */ }
  try { navigator.serviceWorker?.controller?.postMessage({ type: "forget-private" }); } catch { /* no worker */ }
}

function subscribe(cb: () => void) {
  window.addEventListener("online", cb);
  window.addEventListener("offline", cb);
  return () => { window.removeEventListener("online", cb); window.removeEventListener("offline", cb); };
}

/** True while the browser has no connection. */
export function useOnline() {
  return useSyncExternalStore(subscribe, () => navigator.onLine, () => true);
}
