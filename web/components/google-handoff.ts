/** Shared Google sign-in. Google only trusts the origins we list, so shops (one subdomain each) send visitors to the platform address,
 *  which signs them in with Google and hands the Google token back to the shop in the URL fragment (never sent to any server or logged).
 *  The shop then exchanges it for its own session. `state` (kept in this browser's sessionStorage) stops anyone from pushing a stranger's token onto a visitor. */
const KEY = "google-handoff";

export function startGoogleHandoff(signInHost: string, next: string | null) {
  const state = crypto.randomUUID();
  try { sessionStorage.setItem(KEY, JSON.stringify({ state, next })); } catch { /* private mode: the handoff cannot complete */ }
  const back = encodeURIComponent(window.location.origin);
  window.location.assign(`${window.location.protocol}//${signInHost}/google?return=${back}&state=${state}`);
}

export function readGoogleHandoff(): { state: string; next: string | null } | null {
  try { return JSON.parse(sessionStorage.getItem(KEY) ?? "null"); } catch { return null; }
}

export function clearGoogleHandoff() { try { sessionStorage.removeItem(KEY); } catch { /* ignore */ }; }

export function safeNext(next: string | null | undefined): string {
  return next && next.startsWith("/") && !next.startsWith("//") ? next : "/";
}
