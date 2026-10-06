"use client";

import { useSyncExternalStore } from "react";

export type CartLine = { variantId: string; productId: string; slug: string; name: string; variantLabel: string; unitPriceMinor: number; quantity: number; imageUrl?: string | null };

const KEY = "cart:v1";
const EVENT = "cart:changed";
const EMPTY: CartLine[] = [];
let cached: { raw: string | null; lines: CartLine[] } = { raw: null, lines: EMPTY };

function read(): CartLine[] {
  try {
    const raw = localStorage.getItem(KEY);
    if (raw === cached.raw) return cached.lines;
    const lines = raw ? (JSON.parse(raw) as CartLine[]) : EMPTY;
    cached = { raw, lines };
    return lines;
  } catch {
    return EMPTY;
  }
}

function write(lines: CartLine[]) {
  try {
    localStorage.setItem(KEY, JSON.stringify(lines));
  } catch {
    // private mode: cart lives only for this page view
  }
  window.dispatchEvent(new Event(EVENT));
}

export function addToCart(line: CartLine) {
  const lines = [...read()];
  const i = lines.findIndex((l) => l.variantId === line.variantId);
  if (i >= 0) lines[i] = { ...lines[i], quantity: Math.min(lines[i].quantity + line.quantity, 99), unitPriceMinor: line.unitPriceMinor };
  else lines.push(line);
  write(lines);
}

export function setQuantity(variantId: string, quantity: number) {
  write(read().map((l) => (l.variantId === variantId ? { ...l, quantity: Math.max(1, Math.min(quantity, 99)) } : l)));
}

export function removeLine(variantId: string) { write(read().filter((l) => l.variantId !== variantId)); }

export function clearCart() { write(EMPTY); }

function subscribe(cb: () => void) {
  window.addEventListener(EVENT, cb);
  window.addEventListener("storage", cb);
  return () => { window.removeEventListener(EVENT, cb); window.removeEventListener("storage", cb); };
}

/** Browser-side cart (per store origin). Prices shown here are display-only: the server re-prices everything at checkout. */
export function useCart(): CartLine[] {
  return useSyncExternalStore(subscribe, read, () => EMPTY);
}
