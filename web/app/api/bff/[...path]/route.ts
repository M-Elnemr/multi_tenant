import { NextRequest, NextResponse } from "next/server";
import { getSession } from "@/lib/session";
import { API_BASE, refreshTokens } from "@/lib/backend";

/**
 * Backend-for-frontend. The browser only ever talks to its own origin; this route forwards to the API with the
 * visitor's host (tenant) and the access token from the httpOnly session cookie, and stores tokens the API returns.
 */
const FORWARD_HEADERS = ["content-type", "idempotency-key"];

async function handle(req: NextRequest, ctx: { params: Promise<{ path: string[] }> }) {
  const { path } = await ctx.params;
  const upstreamPath = path.join("/");
  if (path.some((p) => p === ".." || p === ".")) return NextResponse.json({ code: "NOT_FOUND" }, { status: 404 });
  const host = (req.headers.get("x-forwarded-host") ?? req.headers.get("host") ?? "").split(",")[0].trim();

  // CSRF defence for cookie auth: state-changing calls must come from this very site.
  if (!["GET", "HEAD", "OPTIONS"].includes(req.method)) {
    const origin = req.headers.get("origin");
    if (!origin || new URL(origin).host !== host) return NextResponse.json({ code: "FORBIDDEN", message: "Cross-site request blocked" }, { status: 403 });
  }

  const session = await getSession();
  if (session.accessToken && session.refreshToken && (session.accessExpiresAt ?? 0) - Date.now() < 60_000) {
    const fresh = await refreshTokens(session, host);
    if (fresh) {
      Object.assign(session, fresh);
      await session.save();
    } else {
      session.destroy();
    }
  }

  const headers = new Headers({ "X-Forwarded-Host": host, Accept: "application/json" });
  const xff = req.headers.get("x-forwarded-for") ?? "";
  if (xff) headers.set("X-Forwarded-For", xff);
  for (const h of FORWARD_HEADERS) {
    const v = req.headers.get(h);
    if (v) headers.set(h, v);
  }
  if (session.accessToken) headers.set("Authorization", `Bearer ${session.accessToken}`);

  const hasBody = !["GET", "HEAD"].includes(req.method);
  // Logout revokes the server-side session: the refresh token never leaves the cookie, so inject it here.
  const body = upstreamPath === "auth/logout" ? JSON.stringify({ refreshToken: session.refreshToken ?? "none" }) : hasBody ? await req.arrayBuffer() : undefined;
  if (upstreamPath === "auth/logout") headers.set("content-type", "application/json");
  const upstream = await fetch(`${API_BASE}/api/v1/${upstreamPath}${req.nextUrl.search}`, {
    method: req.method,
    headers,
    body,
    cache: "no-store",
    redirect: "manual",
  });

  const type = upstream.headers.get("content-type") ?? "";
  if (!type.includes("json")) {
    // files and other binary responses pass through untouched (including their cache/privacy headers)
    const out = new NextResponse(upstream.status === 204 ? null : await upstream.arrayBuffer(), { status: upstream.status });
    for (const h of ["content-type", "content-disposition", "cache-control", "x-content-type-options"]) {
      const v = upstream.headers.get(h);
      if (v) out.headers.set(h, v);
    }
    return out;
  }

  const text = await upstream.text();
  let data: Record<string, unknown> | null = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = null;
  }

  if (upstream.ok && data) {
    // Auth responses carry tokens: keep them server-side and hand the browser only the profile.
    const tokens = (data.accessToken ? data : (data.tokens as Record<string, unknown> | null)) as { accessToken?: string; refreshToken?: string; expiresIn?: number; user?: unknown } | null;
    if (tokens?.accessToken && tokens.refreshToken) {
      session.accessToken = tokens.accessToken;
      session.refreshToken = tokens.refreshToken;
      session.accessExpiresAt = Date.now() + (tokens.expiresIn ?? 900) * 1000;
      await session.save();
      if (data.accessToken) data = { user: tokens.user };
      else data = { ...data, tokens: undefined, user: tokens.user };
    }
  }
  if (upstreamPath === "auth/logout") {
    session.destroy();
  }
  if (upstream.status === 401 && session.accessToken && upstreamPath !== "auth/login") {
    // token no longer valid server-side: drop the cookie so the UI sends the visitor to log in
    session.destroy();
  }
  return NextResponse.json(data ?? {}, { status: upstream.status });
}

export const GET = handle;
export const POST = handle;
export const PUT = handle;
export const PATCH = handle;
export const DELETE = handle;
