"use client";

import { usePathname } from "next/navigation";
import { useEffect, useRef } from "react";

/** Areas for signed-in people; never reported (the server also drops them). */
const PRIVATE = ["/dashboard", "/admin", "/account", "/portal", "/orders", "/checkout", "/login"];

const uuid = () => (typeof crypto !== "undefined" && "randomUUID" in crypto ? crypto.randomUUID() : "10000000-1000-4000-8000-100000000000".replace(/[018]/g, (c) => (Number(c) ^ (Math.random() * 16) >> (Number(c) / 4)).toString(16)));

/** A random id that lives in a first-party cookie for a year (no personal data, no link to any account). */
function visitorId(): string | null {
  try {
    const m = document.cookie.match(/(?:^|; )vid=([0-9a-f-]{36})/);
    if (m) return m[1];
    const id = uuid();
    document.cookie = `vid=${id}; Max-Age=31536000; Path=/; SameSite=Lax`;
    return id;
  } catch { return null; }
}

function sessionId(): string {
  try {
    const k = "sid";
    let s = sessionStorage.getItem(k);
    if (!s) { s = uuid(); sessionStorage.setItem(k, s); }
    return s;
  } catch { return uuid(); }
}

function send(body: unknown) {
  try {
    void fetch("/api/bff/analytics/collect", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body), keepalive: true });
  } catch { /* analytics must never break a page */ }
}

/** Records a funnel step (e.g. sign-up started) as a pseudo-page under the real one, so it shows in the admin Visitors timeline. */
export function trackStep(path: string) {
  try {
    if (navigator.doNotTrack === "1") return;
    const vid = visitorId();
    if (!vid) return;
    send({ type: "view", id: uuid(), visitorId: vid, sessionId: sessionId(), path, query: "", referrer: "", language: navigator.language, timezone: Intl.DateTimeFormat().resolvedOptions().timeZone, screenW: window.screen.width, screenH: window.screen.height, userAgent: navigator.userAgent });
  } catch { /* analytics must never break a page */ }
}

/** Reports each public page the visitor opens and how long it stayed in view. Renders nothing. */
export function VisitTracker() {
  const path = usePathname();
  const current = useRef<{ id: string; shown: number; since: number | null } | null>(null);

  const leave = (c: NonNullable<typeof current.current>) => {
    const vid = visitorId();
    if (!vid) return;
    const total = c.shown + (c.since ? Date.now() - c.since : 0);
    c.shown = total;
    c.since = null;
    send({ type: "leave", id: c.id, visitorId: vid, durationMs: total });
  };

  useEffect(() => {
    if (navigator.doNotTrack === "1" || PRIVATE.some((p) => path === p || path.startsWith(p + "/"))) return;
    const vid = visitorId();
    if (!vid) return;
    const c = { id: uuid(), shown: 0, since: document.visibilityState === "visible" ? Date.now() : null };
    current.current = c;
    send({
      type: "view", id: c.id, visitorId: vid, sessionId: sessionId(), path, query: window.location.search.slice(0, 300),
      referrer: document.referrer, language: navigator.language, timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
      screenW: window.screen.width, screenH: window.screen.height, userAgent: navigator.userAgent,
    });
    const onVisibility = () => {
      if (document.visibilityState === "hidden") leave(c);
      else c.since = Date.now();
    };
    document.addEventListener("visibilitychange", onVisibility);
    window.addEventListener("pagehide", onVisibility);
    return () => {
      document.removeEventListener("visibilitychange", onVisibility);
      window.removeEventListener("pagehide", onVisibility);
      leave(c);   // moving to another page of the site
    };
  }, [path]);

  return null;
}
