import { NextRequest, NextResponse } from "next/server";

/** Language switch: remembers the choice in a cookie and returns to the page the visitor was on. */
export async function GET(req: NextRequest) {
  const l = req.nextUrl.searchParams.get("l") === "en" ? "en" : "ar";
  const back = req.headers.get("referer");
  const target = back && new URL(back).host === (req.headers.get("host") ?? "") ? back : "/";
  const res = NextResponse.redirect(new URL(target, req.url));
  res.cookies.set("lang", l, { path: "/", maxAge: 60 * 60 * 24 * 365, sameSite: "lax" });
  return res;
}
