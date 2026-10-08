import { NextRequest, NextResponse } from "next/server";
import { API_BASE } from "@/lib/backend";
import { getSession } from "@/lib/session";

/**
 * One sign-in for every place a person belongs to. The platform host asked the API for a one-time ticket for THIS clinic/shop; the browser
 * lands here (on the clinic/shop's own host, where its session cookie must be set), the ticket is swapped for tokens, and the visitor continues
 * to the page they asked for. The ticket works once, for a minute, only for this host's tenant; on any problem the normal login page is shown.
 */
export async function GET(req: NextRequest) {
  const host = (req.headers.get("x-forwarded-host") ?? req.headers.get("host") ?? "").split(",")[0].trim();
  const proto = (req.headers.get("x-forwarded-proto") ?? "http").split(",")[0].trim();
  const origin = `${proto === "https" ? "https" : "http"}://${host}`;
  const ticket = req.nextUrl.searchParams.get("ticket") ?? "";
  const asked = req.nextUrl.searchParams.get("next") ?? "/";
  const next = /^\/(?!\/)[^\s\\]*$/.test(asked) ? asked : "/";   // same-site paths only: never an absolute URL or "//host"

  const headers = new Headers({ "Referrer-Policy": "no-referrer", "Cache-Control": "no-store" });
  const toLogin = () => NextResponse.redirect(`${origin}/login?next=${encodeURIComponent(next)}`, { status: 303, headers });
  if (!ticket) return toLogin();

  const fwd = req.headers.get("x-forwarded-for");
  const res = await fetch(`${API_BASE}/api/v1/auth/handoff/redeem`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json", "X-Forwarded-Host": host, ...(fwd ? { "X-Forwarded-For": fwd } : {}) },
    body: JSON.stringify({ ticket }),
    cache: "no-store",
  }).catch(() => null);
  if (!res || !res.ok) return toLogin();
  const t = (await res.json()) as { accessToken?: string; refreshToken?: string; expiresIn?: number };
  if (!t.accessToken || !t.refreshToken) return toLogin();

  const session = await getSession();
  session.accessToken = t.accessToken;
  session.refreshToken = t.refreshToken;
  session.accessExpiresAt = Date.now() + (t.expiresIn ?? 900) * 1000;
  await session.save();
  return NextResponse.redirect(`${origin}${next}`, { status: 303, headers });
}
