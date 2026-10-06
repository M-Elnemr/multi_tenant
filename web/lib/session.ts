import { getIronSession, type SessionOptions } from "iron-session";
import { cookies } from "next/headers";

/** Tokens live only in this httpOnly, encrypted cookie - never in JavaScript. One cookie per site host. */
export type SessionData = {
  accessToken?: string;
  refreshToken?: string;
  /** epoch ms when the access token expires */
  accessExpiresAt?: number;
};

export const SESSION_COOKIE = "ps";

export function getSessionOptions(): SessionOptions {
  const password = process.env.SESSION_SECRET ?? "";
  if (password.length < 32) {
    if (process.env.NODE_ENV === "production") throw new Error("SESSION_SECRET must be at least 32 characters");
    return baseOptions("dev-only-session-secret-change-me-0123456789");
  }
  return baseOptions(password);
}

function baseOptions(password: string): SessionOptions {
  return {
    password,
    cookieName: SESSION_COOKIE,
    cookieOptions: {
      httpOnly: true,
      sameSite: "lax",
      secure: process.env.NODE_ENV === "production",
      path: "/",
      maxAge: 60 * 60 * 24 * 30,
    },
  };
}

export async function getSession() {
  return getIronSession<SessionData>(await cookies(), getSessionOptions());
}
