/* Storefront service worker: static files load from the phone's cache, pages always try the network first and fall back to the last copy or a short offline page.
   Nothing private is ever stored: the dashboard, account, checkout, orders and every API call go straight to the network. */
const CACHE = "shop-v1";
const PRIVATE = [/^\/api\//, /^\/dashboard/, /^\/account/, /^\/checkout/, /^\/orders/, /^\/login/, /^\/portal/, /^\/admin/];
const OFFLINE = '<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Offline</title><body style="font-family:system-ui,sans-serif;display:grid;place-items:center;min-height:100vh;margin:0;text-align:center;padding:24px"><div><div style="font-size:48px">📡</div><h1>لا يوجد اتصال بالإنترنت / You are offline</h1><p>افتح الصفحة مرة أخرى عند عودة الاتصال.<br>Open the page again when you are back online.</p></div></body>';

self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});

self.addEventListener("fetch", (e) => {
  const req = e.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin || PRIVATE.some((p) => p.test(url.pathname)) || req.headers.get("range")) return;
  if (url.pathname.startsWith("/_next/static/") || /\.(?:png|jpg|jpeg|webp|svg|ico|woff2?)$/.test(url.pathname)) {
    e.respondWith(caches.match(req).then((hit) => hit || fetch(req).then((res) => { if (res.ok) { const copy = res.clone(); caches.open(CACHE).then((c) => c.put(req, copy)); } return res; })));
    return;
  }
  if (req.mode === "navigate") {
    e.respondWith(
      Promise.race([fetch(req), new Promise((_, rej) => setTimeout(() => rej(new Error("slow")), 6000))])
        .then((res) => { if (res.ok && !res.redirected) { const copy = res.clone(); caches.open(CACHE).then((c) => c.put(req, copy)); } return res; })
        .catch(() => caches.match(req).then((hit) => hit || new Response(OFFLINE, { headers: { "Content-Type": "text/html; charset=utf-8" } }))),
    );
  }
});
