/* One service worker for the whole site.
   - Storefront: static files load from the phone's cache; pages try the network first and fall back to the last copy or a short offline page. Nothing private is stored.
   - Till terminal (/pos): the page and every script it needs are kept, so the till opens and sells with no internet (its data lives in the browser's IndexedDB).
   - Dashboard: pages and their GET data are kept as last seen, so a manager can still read them offline (writes need the internet). This private copy is wiped on logout. */
const CACHE = "shop-v2";
const POS = "pos-v2";
const DASH = "dash-v2";
const KEEP = [CACHE, POS, DASH];
const NEVER = [/^\/api\/bff\/auth\//, /^\/api\/bff\/store\/pos\/(terminal|sync)\//, /^\/api\/sso/, /^\/api\/lang/];
const PRIVATE = [/^\/api\//, /^\/dashboard/, /^\/account/, /^\/checkout/, /^\/orders/, /^\/login/, /^\/portal/, /^\/admin/, /^\/pos/];
const OFFLINE = '<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Offline</title><body style="font-family:system-ui,sans-serif;display:grid;place-items:center;min-height:100vh;margin:0;text-align:center;padding:24px"><div><div style="font-size:48px">📡</div><h1>لا يوجد اتصال بالإنترنت / You are offline</h1><p>افتح الصفحة مرة أخرى عند عودة الاتصال.<br>Open the page again when you are back online.</p></div></body>';

self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => !KEEP.includes(k)).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});

/** The page asks to keep what it needs (the till: its own address and every script and style it loaded), or to forget the private copy (logout). */
self.addEventListener("message", (e) => {
  const m = e.data || {};
  if (m.type === "keep" && Array.isArray(m.urls)) {
    e.waitUntil(caches.open(m.cache === "pos" ? POS : CACHE).then((c) => Promise.all(m.urls.filter((u) => typeof u === "string" && u.startsWith("/")).map((u) =>
      fetch(u, { credentials: "same-origin" }).then((res) => (res.ok && !res.redirected ? c.put(u, res) : null)).catch(() => null)))));
  }
  if (m.type === "forget-private") e.waitUntil(caches.delete(DASH));
});

const withTimeout = (p, ms) => Promise.race([p, new Promise((_, rej) => setTimeout(() => rej(new Error("slow")), ms))]);

/** Network first; a good answer is kept; with no network (or a very slow one) the kept copy answers. */
function networkFirst(req, cacheName, fallback, ms) {
  return (ms ? withTimeout(fetch(req), ms) : fetch(req))
    .then((res) => { if (res.ok && !res.redirected) { const copy = res.clone(); caches.open(cacheName).then((c) => c.put(req, copy)); } return res; })
    .catch(() => caches.open(cacheName).then((c) => c.match(req, { ignoreVary: true })).then((hit) => hit || fallback()));
}

self.addEventListener("fetch", (e) => {
  const req = e.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin || req.headers.get("range")) return;
  const path = url.pathname;

  // scripts, styles, fonts and pictures: from the cache when there, kept the first time they load
  if (path.startsWith("/_next/static/") || /\.(?:png|jpg|jpeg|webp|svg|ico|woff2?)$/.test(path)) {
    e.respondWith(caches.match(req).then((hit) => hit || fetch(req).then((res) => { if (res.ok) { const copy = res.clone(); caches.open(CACHE).then((c) => c.put(req, copy)); } return res; })));
    return;
  }
  // product pictures served by id never change: kept, so the till's product grid shows them offline too
  if (/^\/api\/bff\/files\/[^/]+\/content$/.test(path)) {
    e.respondWith(caches.match(req).then((hit) => hit || fetch(req).then((res) => { if (res.ok) { const copy = res.clone(); caches.open(CACHE).then((c) => c.put(req, copy)); } return res; })));
    return;
  }
  if (NEVER.some((p) => p.test(path))) return;

  // the till: always opens, from the network when it answers fast, else from its kept copy
  if (req.mode === "navigate" && (path === "/pos" || path.startsWith("/pos/"))) {
    e.respondWith(networkFirst(new Request("/pos", { credentials: "same-origin" }), POS, () => new Response(OFFLINE, { headers: { "Content-Type": "text/html; charset=utf-8" } }), 4000));
    return;
  }

  // the dashboard, read-only offline: pages, their data (GET) and the router's page data, as last seen
  const dashData = path.startsWith("/api/bff/") && !path.includes("/content");
  const dashPage = path === "/dashboard" || path.startsWith("/dashboard/");
  if (dashData || (dashPage && (req.mode === "navigate" || req.headers.get("RSC") === "1"))) {
    e.respondWith(networkFirst(req, DASH, () => (req.mode === "navigate"
      ? new Response(OFFLINE, { headers: { "Content-Type": "text/html; charset=utf-8" } })
      : new Response(JSON.stringify({ code: "NETWORK", message: "Offline" }), { status: 503, headers: { "Content-Type": "application/json" } })), dashData ? 0 : 8000));   // data waits for a slow server; only no network uses the copy
    return;
  }

  if (PRIVATE.some((p) => p.test(path))) return;
  if (req.mode === "navigate") {
    e.respondWith(networkFirst(req, CACHE, () => new Response(OFFLINE, { headers: { "Content-Type": "text/html; charset=utf-8" } }), 6000));
  }
});
