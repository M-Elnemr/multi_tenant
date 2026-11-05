import { NextResponse, type NextRequest } from "next/server";
import { resolveHost } from "@/lib/tenant";
import { SESSION_COOKIE } from "@/lib/session";

/**
 * Host-based routing: one deployment serves the platform site, every store and every clinic.
 * The visitor's host decides which internal route group renders (URLs stay clean):
 *   platform host -> /t-platform/*,  store host -> /t-store/*,  clinic host -> /t-clinic/*,  lawyer host -> /t-lawyer/*,  open.<root> -> /t-open/* (the marketplace)
 * Every business signs its staff in on its own address: <its domain>/dashboard/login (no trip through the platform site).
 */
const TPL_COOKIE = "site-tpl";
const INTERNAL = /^\/t-(platform|store|clinic|lawyer|open|unknown)(\/|$)/;
const PRIVATE_PREFIXES = ["/dashboard", "/account", "/portal", "/admin", "/orders"];   // /checkout is open: guests can order without an account

export async function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;
  if (INTERNAL.test(pathname)) return new NextResponse("Not found", { status: 404 }); // internal groups are not addressable directly

  const host = (request.headers.get("x-forwarded-host") ?? request.headers.get("host") ?? "").split(",")[0].trim();
  const info = await resolveHost(host);
  if (info.kind === "UNKNOWN") return NextResponse.rewrite(new URL("/t-unknown", request.url), { status: 404 });

  // A disabled business (unpaid subscription) is closed to visitors, patients and customers; only its staff can sign in to the pay screen.
  if (info.kind === "TENANT" && ["SUSPENDED", "CANCELLED", "ARCHIVED"].includes(info.status) && !(pathname === "/login" || pathname === "/dashboard" || pathname.startsWith("/dashboard/"))) {
    return NextResponse.rewrite(new URL("/t-unknown?reason=disabled", request.url), { status: 403 });
  }

  const staffLogin = info.kind === "TENANT" && (pathname === "/dashboard/login" || pathname === "/admin/login");
  if (!staffLogin && PRIVATE_PREFIXES.some((p) => pathname === p || pathname.startsWith(p + "/")) && !request.cookies.has(SESSION_COOKIE)) {
    // the dashboard has its own sign-in page on the business's address; customers and patients keep theirs
    const login = new URL(info.kind === "TENANT" && (pathname === "/dashboard" || pathname.startsWith("/dashboard/")) ? "/dashboard/login" : "/login", request.url);
    login.searchParams.set("next", pathname + request.nextUrl.search);
    return NextResponse.redirect(login);
  }

  // Design preview: "?tpl=<id>" shows the site in another template without saving it (the owner's template gallery uses it, also inside an iframe with
  // "&preview=1"). The choice rides along in a session cookie so the owner can click through the whole site; "?tpl=off" ends it. The server renders the
  // previewed template for real (layout and sections), it is read from the x-site-tpl request header (validated again where it is used).
  const reqHeaders = new Headers(request.headers);
  reqHeaders.delete("x-site-tpl"); reqHeaders.delete("x-site-preview");   // never trust these from the visitor
  const qTpl = request.nextUrl.searchParams.get("tpl");
  const qPreview = request.nextUrl.searchParams.get("preview") === "1";
  const cookieTpl = request.cookies.get(TPL_COOKIE)?.value;
  const valid = (v?: string | null) => !!v && /^[a-z]{2,16}$/.test(v);
  const tpl = qTpl === "off" ? null : valid(qTpl) ? qTpl : !qPreview && valid(cookieTpl) ? cookieTpl! : null;
  // inside the gallery's iframes ("&preview=1") nothing is remembered: the frame's links carry ?tpl= themselves (see PreviewLinks)
  const preview = qPreview;
  if (info.kind === "TENANT" && tpl) reqHeaders.set("x-site-tpl", tpl);
  if (info.kind === "TENANT" && preview) reqHeaders.set("x-site-preview", "1");

  const prefix = info.kind === "PLATFORM" ? "/t-platform" : info.kind === "OPEN" ? "/t-open" : info.type === "STORE" ? "/t-store" : info.type === "LAWYER" ? "/t-lawyer" : "/t-clinic";
  const url = request.nextUrl.clone();
  url.pathname = staffLogin ? `${prefix}/staff-login` : pathname === "/" ? prefix : prefix + pathname;
  const res = NextResponse.rewrite(url, { request: { headers: reqHeaders } });
  if (info.kind === "TENANT") {
    if (qTpl === "off") res.cookies.delete(TPL_COOKIE);
    // a session cookie: the preview lasts until the tab closes or "?tpl=off"
    else if (qTpl && tpl && !qPreview) res.cookies.set(TPL_COOKIE, tpl, { path: "/", sameSite: "lax", httpOnly: true });
  }
  return res;
}

export const config = {
  // everything except API routes, Next internals and files with an extension
  matcher: ["/((?!api/|_next/|.*\\..*).*)"],
};
