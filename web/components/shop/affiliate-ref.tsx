"use client";

import { useEffect } from "react";

const KEY = "mt-ref";

/** The marketer code remembered on this device (null when none or expired). */
export function savedRef(): string | null {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return null;
    const { code, until } = JSON.parse(raw) as { code: string; until: number };
    if (Date.now() > until) { localStorage.removeItem(KEY); return null; }
    return code;
  } catch { return null; }
}

/** A visitor who arrives with ?ref=CODE is counted once and the code is remembered for the days the shop chose; the last link wins. */
export function AffiliateRef() {
  useEffect(() => {
    const code = new URLSearchParams(window.location.search).get("ref")?.trim().toUpperCase();
    if (!code || !/^[A-Z0-9]{4,24}$/.test(code)) return;
    void fetch("/api/bff/shop/affiliate/visit", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ ref: code }) })
      .then((r) => r.json()).then((j: { valid?: boolean; days?: number }) => {
        if (j.valid && j.days) { try { localStorage.setItem(KEY, JSON.stringify({ code, until: Date.now() + j.days * 86_400_000 })); } catch { /* private window: the code is not remembered */ } }
      }).catch(() => undefined);
  }, []);
  return null;
}
