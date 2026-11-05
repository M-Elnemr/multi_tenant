"use client";

import { useEffect } from "react";

/**
 * Inside the template gallery's preview frame the design isn't remembered anywhere, so every same-site link is rewritten on click to carry
 * ?tpl=<id>&preview=1 and the next page renders in the same design. Runs in the capture phase, before the router sees the click.
 */
export function PreviewLinks({ tpl }: { tpl: string }) {
  useEffect(() => {
    const on = (e: MouseEvent) => {
      const a = (e.target as HTMLElement | null)?.closest?.("a");
      if (!a || a.target === "_blank" || e.metaKey || e.ctrlKey) return;
      const url = new URL(a.href, location.href);
      if (url.origin !== location.origin || url.pathname.startsWith("/api/")) return;
      e.preventDefault();
      e.stopPropagation();
      url.searchParams.set("tpl", tpl);
      url.searchParams.set("preview", "1");
      location.assign(url.toString());
    };
    window.addEventListener("click", on, true);
    return () => window.removeEventListener("click", on, true);
  }, [tpl]);
  return null;
}
