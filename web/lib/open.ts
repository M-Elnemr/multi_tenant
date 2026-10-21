import { cookies } from "next/headers";
import { API_BASE, BackendError, currentHost } from "./backend";

/** Marketplace sign-in token (opaque, httpOnly). Set by app/api/open/[...path]/route.ts, read here for server-rendered pages. */
export const OPEN_COOKIE = "open_token";

export async function openToken(): Promise<string | undefined> {
  return (await cookies()).get(OPEN_COOKIE)?.value;
}

export async function openFetch(path: string, opts: { method?: string; body?: unknown } = {}): Promise<Response> {
  const headers: Record<string, string> = { "X-Forwarded-Host": await currentHost(), Accept: "application/json" };
  const token = await openToken();
  if (token) headers["X-Open-Token"] = token;
  if (opts.body !== undefined) headers["Content-Type"] = "application/json";
  return fetch(`${API_BASE}/api/v1/open${path}`, { method: opts.method ?? "GET", headers, body: opts.body === undefined ? undefined : JSON.stringify(opts.body), cache: "no-store" });
}

export async function openJson<T>(path: string, opts: { method?: string; body?: unknown } = {}): Promise<T> {
  const res = await openFetch(path, opts);
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) throw new BackendError(res.status, data?.code ?? "ERROR", data?.message ?? res.statusText, data?.fields);
  return data as T;
}

export * from "./open-types";
