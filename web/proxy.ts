import { NextResponse, type NextRequest } from "next/server";
import { resolveHost } from "@/lib/tenant";
import { SESSION_COOKIE } from "@/lib/session";

/**
 * Host-based routing: one deployment serves the platform site, every store and every clinic.
 * The visitor's host decides which internal route group renders (URLs stay clean):
 *   platform host -> /t-platform/*,  store host -> /t-store/*,  clinic host -> /t-clinic/*
 */
const INTERNAL = /^\/t-(platform|store|clinic|unknown)(\/|$)/;
const PRIVATE_PREFIXES = ["/dashboard", "/account", "/portal", "/admin", "/orders", "/checkout"];

export async function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;
  if (INTERNAL.test(pathname)) return new NextResponse("Not found", { status: 404 }); // internal groups are not addressable directly

  const host = (request.headers.get("x-forwarded-host") ?? request.headers.get("host") ?? "").split(",")[0].trim();
  const info = await resolveHost(host);
  if (info.kind === "UNKNOWN") return NextResponse.rewrite(new URL("/t-unknown", request.url), { status: 404 });

  if (PRIVATE_PREFIXES.some((p) => pathname === p || pathname.startsWith(p + "/")) && !request.cookies.has(SESSION_COOKIE)) {
    const login = new URL("/login", request.url);
    login.searchParams.set("next", pathname + request.nextUrl.search);
    return NextResponse.redirect(login);
  }

  const prefix = info.kind === "PLATFORM" ? "/t-platform" : info.type === "STORE" ? "/t-store" : "/t-clinic";
  const url = request.nextUrl.clone();
  url.pathname = pathname === "/" ? prefix : prefix + pathname;
  return NextResponse.rewrite(url);
}

export const config = {
  // everything except API routes, Next internals and files with an extension
  matcher: ["/((?!api/|_next/|.*\\..*).*)"],
};
