"use client";

import { useEffect, useRef } from "react";

/**
 * Calls `tick` every `ms` (never faster than 3 seconds) while `active`. Pauses while the tab is hidden and fires once when it becomes visible again.
 * The only dependencies are the boolean `active` and `ms`, so the interval is not recreated on every render.
 * The backend allows 600 requests a minute per IP: use one poller per screen.
 */
export function usePoll(active: boolean, tick: () => void, ms = 3000) {
  const ref = useRef(tick);
  useEffect(() => { ref.current = tick; });
  useEffect(() => {
    if (!active) return;
    const id = setInterval(() => { if (!document.hidden) ref.current(); }, Math.max(3000, ms));
    const onVis = () => { if (!document.hidden) ref.current(); };
    document.addEventListener("visibilitychange", onVis);
    return () => { clearInterval(id); document.removeEventListener("visibilitychange", onVis); };
  }, [active, ms]);
}
