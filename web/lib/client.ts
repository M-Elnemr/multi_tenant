"use client";

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public fields?: Record<string, string>,
    public params?: Record<string, unknown>,
  ) {
    super(message);
  }
}

/** Calls the backend through this site's own /api/bff proxy (tenant + auth are handled server-side). */
export async function api<T = unknown>(path: string, init: { method?: string; body?: unknown; idempotencyKey?: string } = {}): Promise<T> {
  const headers: Record<string, string> = {};
  if (init.body !== undefined) headers["Content-Type"] = "application/json";
  if (init.idempotencyKey) headers["Idempotency-Key"] = init.idempotencyKey;
  let res: Response;
  try {
    res = await fetch(`/api/bff/${path.replace(/^\//, "")}`, {
      method: init.method ?? (init.body !== undefined ? "POST" : "GET"),
      headers,
      body: init.body === undefined ? undefined : JSON.stringify(init.body),
    });
  } catch {
    throw new ApiError(0, "NETWORK", "No connection to the server");   // offline, or the server is not reachable
  }
  const text = await res.text();
  let data: unknown = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = null;
  }
  if (!res.ok) {
    const d = (data ?? {}) as { code?: string; message?: string; fields?: Record<string, string>; params?: Record<string, unknown> };
    if (res.status === 401 && typeof window !== "undefined" && !path.startsWith("auth/")) {
      window.location.assign(`/login?next=${encodeURIComponent(window.location.pathname)}`);
    }
    throw new ApiError(res.status, d.code ?? `HTTP_${res.status}`, d.message ?? res.statusText, d.fields, d.params);
  }
  if (/^\/?(auth\/(login|activate|reset-password|logout|client\/google|change-password)|shop\/me)/.test(path) && typeof window !== "undefined") {
    window.dispatchEvent(new Event("auth:changed"));   // header/account widgets re-read who is logged in
  }
  return data as T;
}

export function newKey(): string {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID();
  // plain http (a phone on the local network, a test address) has no randomUUID: build one from random bytes
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40; b[8] = (b[8] & 0x3f) | 0x80;
  const h = [...b].map((x) => x.toString(16).padStart(2, "0")).join("");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}
