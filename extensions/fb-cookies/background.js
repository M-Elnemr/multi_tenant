// One-click connect: the Elmanassa dashboard asks for the Facebook session of this browser.
// Only pages of the dashboard (path /dashboard/...) on the allowed origins (see manifest) are answered.
const NEEDED = ["c_user", "xs"];
const WANTED = ["c_user", "xs", "datr", "fr", "sb", "wd"];

async function facebookCookies() {
  const all = await chrome.cookies.getAll({ domain: "facebook.com" });
  const byName = new Map();
  for (const c of all) if (!byName.has(c.name)) byName.set(c.name, c.value);
  if (NEEDED.some((n) => !byName.get(n))) return { ok: false, error: "NOT_LOGGED_IN" };
  // i_user is added by the server from the Page id, so it is not included.
  const cookies = WANTED.filter((n) => byName.get(n)).map((n) => `${n}=${byName.get(n)}`).join("; ") + ";";
  return { ok: true, cookies, account: byName.get("c_user") };
}

chrome.runtime.onMessageExternal.addListener((msg, sender, sendResponse) => {
  let path = "";
  try { path = new URL(sender.url || "").pathname; } catch { /* ignore */ }
  if (!path.startsWith("/dashboard/")) { sendResponse({ ok: false, error: "FORBIDDEN" }); return false; }
  if (msg && msg.type === "ping") { sendResponse({ ok: true, version: chrome.runtime.getManifest().version }); return false; }
  if (msg && msg.type === "getFbCookies") {
    facebookCookies().then(sendResponse).catch(() => sendResponse({ ok: false, error: "FAILED" }));
    return true;
  }
  sendResponse({ ok: false, error: "UNKNOWN" });
  return false;
});
