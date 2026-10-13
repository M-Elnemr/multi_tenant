import { cookies } from "next/headers";
import { SESSION_COOKIE } from "./session";

/**
 * Is the public site opened for editing (`?edit=1`) by someone with a session? The permission itself is checked in the browser
 * (EditProvider -> /auth/me through the BFF, which renews an expired token); the server can only see the cookie, whose access token may be old.
 */
export async function wantsEdit(searchParams: Promise<Record<string, string | string[] | undefined>> | undefined): Promise<boolean> {
  const sp = searchParams ? await searchParams : {};
  if (sp.edit !== "1") return false;
  return (await cookies()).has(SESSION_COOKIE);
}
