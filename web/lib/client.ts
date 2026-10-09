"use client";

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public fields?: Record<string, string>,
  ) {
    super(message);
  }
}

/** Calls the backend through this site's own /api/bff proxy (tenant + auth are handled server-side). */
export async function api<T = unknown>(path: string, init: { method?: string; body?: unknown; idempotencyKey?: string } = {}): Promise<T> {
  const headers: Record<string, string> = {};
  if (init.body !== undefined) headers["Content-Type"] = "application/json";
  if (init.idempotencyKey) headers["Idempotency-Key"] = init.idempotencyKey;
  const res = await fetch(`/api/bff/${path.replace(/^\//, "")}`, {
    method: init.method ?? (init.body !== undefined ? "POST" : "GET"),
    headers,
    body: init.body === undefined ? undefined : JSON.stringify(init.body),
  });
  const text = await res.text();
  let data: unknown = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = null;
  }
  if (!res.ok) {
    const d = (data ?? {}) as { code?: string; message?: string; fields?: Record<string, string> };
    if (res.status === 401 && typeof window !== "undefined" && !path.startsWith("auth/")) {
      window.location.assign(`/login?next=${encodeURIComponent(window.location.pathname)}`);
    }
    throw new ApiError(res.status, d.code ?? "ERROR", d.message ?? res.statusText, d.fields);
  }
  if (/^\/?(auth\/(login|activate|reset-password|logout|client\/google|change-password)|shop\/me)/.test(path) && typeof window !== "undefined") {
    window.dispatchEvent(new Event("auth:changed"));   // header/account widgets re-read who is logged in
  }
  return data as T;
}

export function newKey(): string {
  return crypto.randomUUID();
}
