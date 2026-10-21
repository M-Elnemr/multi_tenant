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

/** A cart holds products of ONE shop (delivery and cash collection are per shop). Returns the other shop's name when the cart belongs to a different shop; nothing is added then. */
export function addToCart(line: OpenCartLine): { conflict: string } | null {
  const cur = cart.read();
  if (cur.length > 0 && cur[0].shopId !== line.shopId) return { conflict: cur[0].shopName };
  const lines = [...cur];
  const i = lines.findIndex((l) => l.variantId === line.variantId);
  if (i >= 0) lines[i] = { ...lines[i], quantity: Math.min(lines[i].quantity + line.quantity, 99), unitPriceMinor: line.unitPriceMinor };
  else lines.push(line);
  cart.write(lines);
  return null;
}
/** The shopper chose to drop the other shop's cart and start a new one with this product. */
export function replaceCart(line: OpenCartLine) { cart.write([line]); saveCoupon(""); }
export function setQuantity(variantId: string, quantity: number) { cart.write(cart.read().map((l) => (l.variantId === variantId ? { ...l, quantity: Math.max(1, Math.min(quantity, 99)) } : l))); }
export function removeLine(variantId: string) { cart.write(cart.read().filter((l) => l.variantId !== variantId)); }
export function clearCart() { cart.write(EMPTY_LINES); saveCoupon(""); }
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

// ---- orders placed on this device (so a guest can follow an order without an account) -----------------------------------------

export type KnownOrder = { shop: string; shopName: string; number: string; phone: string; at: number };
const EMPTY_ORDERS: KnownOrder[] = [];
const orders = store<KnownOrder[]>("oorders:v1", "oorders:changed", EMPTY_ORDERS);
export function rememberOrder(o: KnownOrder) { orders.write([o, ...orders.read().filter((x) => !(x.shop === o.shop && x.number === o.number))].slice(0, 30)); }
export function useKnownOrders(): KnownOrder[] { return useSyncExternalStore(orders.subscribe, orders.read, () => EMPTY_ORDERS); }

// ---- who is signed in ----------------------------------------------------------------------------------------------------

export type OpenMe = { id: string; name: string; phone?: string | null; email?: string | null };

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

const COUPON = "ocoupon:v1";
/** The coupon code applied in the cart, carried to checkout (a cart has one shop, so one coupon). */
export function loadCoupon(): string { try { return localStorage.getItem(COUPON) ?? ""; } catch { return ""; } }
export function saveCoupon(code: string) { try { if (code) localStorage.setItem(COUPON, code); else localStorage.removeItem(COUPON); } catch { /* private mode */ } }

export type CartQuote = {
  shop: { id: string; slug: string; name: string; host: string | null }; currency: string; subtotalMinor: number; discountMinor: number; shippingMinor: number; codFeeMinor: number;
  etaMinDays: number | null; etaMaxDays: number | null; totalMinor: number; shippingKnown: boolean; lines: { variantId: string; productName: string; unitPriceMinor: number; quantity: number }[];
};

/** crypto.randomUUID needs a secure context (https or localhost); fall back to getRandomValues elsewhere. */
export function uuid(): string {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID();
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40; b[8] = (b[8] & 0x3f) | 0x80;
  const h = [...b].map((x) => x.toString(16).padStart(2, "0")).join("");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}
