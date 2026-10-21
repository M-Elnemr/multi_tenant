import { NextRequest, NextResponse } from "next/server";
import { API_BASE } from "@/lib/backend";
import { OPEN_COOKIE } from "@/lib/open";

/**
 * Backend-for-frontend for the Open Store. The browser talks to its own origin; this forwards to /api/v1/open with the visitor's host and the
 * sign-in token from the httpOnly cookie, and keeps the token the API returns on register/login out of JavaScript's reach.
 */
const FORWARD_HEADERS = ["content-type", "idempotency-key"];
const YEAR = 60 * 60 * 24 * 365;

async function handle(req: NextRequest, ctx: { params: Promise<{ path: string[] }> }) {
  const { path } = await ctx.params;
  if (path.some((p) => p === ".." || p === ".")) return NextResponse.json({ code: "NOT_FOUND" }, { status: 404 });
  const upstreamPath = path.join("/");
  const host = (req.headers.get("x-forwarded-host") ?? req.headers.get("host") ?? "").split(",")[0].trim();

  if (!["GET", "HEAD", "OPTIONS"].includes(req.method)) {
    const origin = req.headers.get("origin");
    if (!origin || new URL(origin).host !== host) return NextResponse.json({ code: "FORBIDDEN", message: "Cross-site request blocked" }, { status: 403 });
  }

  const token = req.cookies.get(OPEN_COOKIE)?.value;
  const headers = new Headers({ "X-Forwarded-Host": host, Accept: "application/json" });
  const xff = req.headers.get("x-forwarded-for");
  if (xff) headers.set("X-Forwarded-For", xff);
  for (const h of FORWARD_HEADERS) {
    const v = req.headers.get(h);
    if (v) headers.set(h, v);
  }
  if (token) headers.set("X-Open-Token", token);

  const upstream = await fetch(`${API_BASE}/api/v1/open/${upstreamPath}${req.nextUrl.search}`, {
    method: req.method,
    headers,
    body: ["GET", "HEAD"].includes(req.method) ? undefined : await req.arrayBuffer(),
    cache: "no-store",
    redirect: "manual",
  });
  const text = await upstream.text();
  let data: Record<string, unknown> | null = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = null; }

  const secure = process.env.NODE_ENV === "production" && process.env.SESSION_SECURE !== "false";
  const set = (res: NextResponse, value: string, maxAge: number) => res.cookies.set(OPEN_COOKIE, value, { httpOnly: true, sameSite: "lax", secure, path: "/", maxAge });

  if (upstream.ok && data && typeof data.token === "string") {
    const res = NextResponse.json({ account: data.account }, { status: upstream.status });
    set(res, data.token, YEAR);
    return res;
  }
  const res = NextResponse.json(data ?? {}, { status: upstream.status });
  // signed out here, or the token is no longer valid on the server: forget it
  if (upstreamPath === "auth/logout" || (upstream.status === 401 && token) || (upstreamPath === "me" && req.method === "DELETE" && upstream.ok)) set(res, "", 0);
  return res;
}

export const GET = handle;
export const POST = handle;
export const PUT = handle;
export const PATCH = handle;
export const DELETE = handle;
