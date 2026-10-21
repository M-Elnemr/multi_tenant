"use client";

import { useEffect, useState, useSyncExternalStore } from "react";
import { ApiError } from "./client";
import type { Translate } from "./i18n";

/** Calls the marketplace API through this site's own /api/open proxy. */
export async function oapi<T = unknown>(path: string, init: { method?: string; body?: unknown; idempotencyKey?: string } = {}): Promise<T> {
  const headers: Record<string, string> = {};
  if (init.body !== undefined) headers["Content-Type"] = "application/json";
  if (init.idempotencyKey) headers["Idempotency-Key"] = init.idempotencyKey;
  const res = await fetch(`/api/open/${path.replace(/^\//, "")}`, { method: init.method ?? (init.body !== undefined ? "POST" : "GET"), headers, body: init.body === undefined ? undefined : JSON.stringify(init.body) });
  const text = await res.text();
  let data: unknown = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = null; }
  if (!res.ok) {
    const d = (data ?? {}) as { code?: string; message?: string; fields?: Record<string, unknown> };
    throw new ApiError(res.status, d.code ?? "ERROR", d.message ?? res.statusText, d.fields as Record<string, string> | undefined);
  }
  const method = init.method ?? (init.body !== undefined ? "POST" : "GET");
  if (method !== "GET" && /^\/?(auth\/|me)/.test(path) && typeof window !== "undefined") window.dispatchEvent(new Event("open:auth"));
  return data as T;
}

// ---- cart ---------------------------------------------------------------------------------------------------------------

export type OpenCartLine = {
  variantId: string; productId: string; shopId: string; shopSlug: string; shopName: string; slug: string; name: string; variantLabel: string;
  unitPriceMinor: number; currency: string; quantity: number; image?: string | null;
};

function store<T>(key: string, event: string, empty: T) {
  let cached: { raw: string | null; value: T } = { raw: null, value: empty };
  const read = (): T => {
    try {
      const raw = localStorage.getItem(key);
      if (raw === cached.raw) return cached.value;
      cached = { raw, value: raw ? (JSON.parse(raw) as T) : empty };
      return cached.value;
    } catch { return empty; }
  };
  const write = (v: T) => {
    try { localStorage.setItem(key, JSON.stringify(v)); } catch { /* private mode: lives for this page view only */ }
    window.dispatchEvent(new Event(event));
  };
  const subscribe = (cb: () => void) => {
    window.addEventListener(event, cb);
    window.addEventListener("storage", cb);
    return () => { window.removeEventListener(event, cb); window.removeEventListener("storage", cb); };
  };
  return { read, write, subscribe, empty };
}

const EMPTY_LINES: OpenCartLine[] = [];
const cart = store<OpenCartLine[]>("ocart:v1", "ocart:changed", EMPTY_LINES);

export function addToCart(line: OpenCartLine) {
  const lines = [...cart.read()];
  const i = lines.findIndex((l) => l.variantId === line.variantId);
  if (i >= 0) lines[i] = { ...lines[i], quantity: Math.min(lines[i].quantity + line.quantity, 99), unitPriceMinor: line.unitPriceMinor };
  else lines.push(line);
  cart.write(lines);
}
export function setQuantity(variantId: string, quantity: number) { cart.write(cart.read().map((l) => (l.variantId === variantId ? { ...l, quantity: Math.max(1, Math.min(quantity, 99)) } : l))); }
export function removeLine(variantId: string) { cart.write(cart.read().filter((l) => l.variantId !== variantId)); }
export function removeShop(shopId: string) { cart.write(cart.read().filter((l) => l.shopId !== shopId)); }
export function clearCart() { cart.write(EMPTY_LINES); }
/** Prices shown from the cart are display-only: the server prices every order again at checkout. */
export function useOpenCart(): OpenCartLine[] { return useSyncExternalStore(cart.subscribe, cart.read, () => EMPTY_LINES); }

// ---- wishlist (kept on this device) --------------------------------------------------------------------------------------

export type WishItem = { id: string; shopSlug: string; slug: string; name: string; image?: string | null; priceMinor: number | null; currency: string };
const EMPTY_WISH: WishItem[] = [];
const wish = store<WishItem[]>("owish:v1", "owish:changed", EMPTY_WISH);

export function toggleWish(item: WishItem) {
  const list = wish.read();
  wish.write(list.some((w) => w.id === item.id) ? list.filter((w) => w.id !== item.id) : [item, ...list].slice(0, 200));
}
export function useWishlist(): WishItem[] { return useSyncExternalStore(wish.subscribe, wish.read, () => EMPTY_WISH); }

// ---- guest purchases remembered on this device (so a guest can follow an order without an account) -----------------------

export type KnownGroup = { groupId: string; phone: string; at: number };
const EMPTY_GROUPS: KnownGroup[] = [];
const groups = store<KnownGroup[]>("ogroups:v1", "ogroups:changed", EMPTY_GROUPS);
export function rememberGroup(g: KnownGroup) { groups.write([g, ...groups.read().filter((x) => x.groupId !== g.groupId)].slice(0, 30)); }
export function useKnownGroups(): KnownGroup[] { return useSyncExternalStore(groups.subscribe, groups.read, () => EMPTY_GROUPS); }

// ---- who is signed in ----------------------------------------------------------------------------------------------------

export type OpenMe = { id: string; name: string; phone: string };

/** undefined while loading, null when signed out. */
export function useOpenMe(): OpenMe | null | undefined {
  const [me, setMe] = useState<OpenMe | null | undefined>(undefined);
  useEffect(() => {
    let alive = true;
    const load = () => oapi<OpenMe>("me").then((m) => alive && setMe(m)).catch(() => alive && setMe(null));
    load();
    window.addEventListener("open:auth", load);
    return () => { alive = false; window.removeEventListener("open:auth", load); };
  }, []);
  return me;
}

// ---- small shared helpers ------------------------------------------------------------------------------------------------


/** A backend error as text in the shopper's language: error.<CODE> when we have it, else the server's message. */
export function errMsg(t: Translate, e: unknown): string {
  const err = e as { code?: string; message?: string };
  const key = err.code ? `error.${err.code}` : "";
  const tr = key ? t(key) : "";
  return tr && tr !== key ? tr : err.message || t("error.generic");
}

const COUPONS = "ocoupons:v1";
/** Coupon codes the shopper applied in the cart, per shop id, carried to checkout. */
export function loadCoupons(): Record<string, string> {
  try { return JSON.parse(localStorage.getItem(COUPONS) ?? "{}") as Record<string, string>; } catch { return {}; }
}
export function saveCoupons(c: Record<string, string>) {
  try { localStorage.setItem(COUPONS, JSON.stringify(c)); } catch { /* private mode */ }
}

export type QuotePart = {
  shopId: string; shopSlug: string | null; shopName: string | null; host: string | null;
  shippingMethod?: { id: string; name: string; type: string };
  quote?: { currency: string; subtotalMinor: number; discountMinor: number; shippingMinor: number; codFeeMinor: number; etaMinDays: number | null; etaMaxDays: number | null; totalMinor: number; lines: { variantId: string; productName: string; unitPriceMinor: number; quantity: number }[] };
  error?: { code: string; message: string };
};
export type CartQuote = { shops: QuotePart[]; currency: string | null; subtotalMinor: number; discountMinor: number; shippingMinor: number; codFeeMinor: number; totalMinor: number; shippingKnown: boolean; hasErrors: boolean };

/** crypto.randomUUID needs a secure context (https or localhost); fall back to getRandomValues elsewhere. */
export function uuid(): string {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID();
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40; b[8] = (b[8] & 0x3f) | 0x80;
  const h = [...b].map((x) => x.toString(16).padStart(2, "0")).join("");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}
