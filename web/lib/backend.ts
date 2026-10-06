import { headers } from "next/headers";
import { getSession, type SessionData } from "./session";

export const API_BASE = process.env.API_BASE_URL ?? "http://localhost:8080";

export class BackendError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public fields?: Record<string, string>,
  ) {
    super(message);
  }
}

/** The public host the visitor used. The backend derives the tenant from it (never from anything the browser sends). */
export async function currentHost(): Promise<string> {
  const h = await headers();
  return (h.get("x-forwarded-host") ?? h.get("host") ?? "").split(",")[0].trim();
}

type Opts = { method?: string; body?: unknown; token?: string; host?: string; idempotencyKey?: string };

export async function backendFetch(path: string, opts: Opts = {}): Promise<Response> {
  const host = opts.host ?? (await currentHost());
  const headers: Record<string, string> = { "X-Forwarded-Host": host, Accept: "application/json" };
  if (opts.body !== undefined) headers["Content-Type"] = "application/json";
  if (opts.token) headers.Authorization = `Bearer ${opts.token}`;
  if (opts.idempotencyKey) headers["Idempotency-Key"] = opts.idempotencyKey;
  return fetch(`${API_BASE}/api/v1${path}`, {
    method: opts.method ?? "GET",
    headers,
    body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
    cache: "no-store",
  });
}

export async function backendJson<T>(path: string, opts: Opts = {}): Promise<T> {
  const res = await backendFetch(path, opts);
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) throw new BackendError(res.status, data?.code ?? "ERROR", data?.message ?? res.statusText, data?.fields);
  return data as T;
}

/** For server components: call the backend as the logged-in visitor (reads the session cookie, never writes it). */
export async function backendAsUser<T>(path: string, opts: Omit<Opts, "token"> = {}): Promise<T> {
  const s = await getSession();
  return backendJson<T>(path, { ...opts, token: s.accessToken });
}

export async function refreshTokens(s: SessionData, host: string): Promise<SessionData | null> {
  if (!s.refreshToken) return null;
  try {
    const res = await backendFetch("/auth/refresh", { method: "POST", body: { refreshToken: s.refreshToken }, host });
    if (!res.ok) return null;
    const j = await res.json();
    return { accessToken: j.accessToken, refreshToken: j.refreshToken, accessExpiresAt: Date.now() + (j.expiresIn ?? 900) * 1000 };
  } catch {
    return null;
  }
}
