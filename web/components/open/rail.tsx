"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Icon } from "../icons";
import { useI18n } from "../i18n-provider";

/** A swipeable row (scroll-snap) with previous/next buttons on devices that have a pointer. The buttons only show when there is more to scroll to. */
export function Rail({ children, className = "" }: { children: React.ReactNode; className?: string }) {
  const { t } = useI18n();
  const ref = useRef<HTMLDivElement>(null);
  const [can, setCan] = useState({ prev: false, next: false });
  const measure = useCallback(() => {
    const el = ref.current;
    if (!el) return;
    const max = el.scrollWidth - el.clientWidth;
    const pos = Math.abs(el.scrollLeft);   // RTL scrollLeft is negative or positive depending on the browser: use the distance from the start edge
    setCan({ prev: pos > 4, next: pos < max - 4 });
  }, []);
  useEffect(() => {
    const id = requestAnimationFrame(measure);
    window.addEventListener("resize", measure);
    return () => { cancelAnimationFrame(id); window.removeEventListener("resize", measure); };
  }, [measure]);
  const go = (dir: 1 | -1) => {
    const el = ref.current;
    if (!el) return;
    const rtl = getComputedStyle(el).direction === "rtl";
    el.scrollBy({ left: (rtl ? -dir : dir) * el.clientWidth * 0.85, behavior: "smooth" });
  };
  const btn = "absolute top-[38%] z-10 hidden h-11 w-11 -translate-y-1/2 place-items-center rounded-full border border-[var(--o-line-2)] bg-[var(--o-paper)] shadow-[var(--o-shadow)] transition hover:scale-105 hover:border-[var(--o-ink)] [@media(hover:hover)]:grid";
  return (
    <div className="relative">
      <div ref={ref} onScroll={measure} className={`o-rail ${className}`}>{children}</div>
      {can.prev && <button type="button" aria-label={t("open.rail.prev")} onClick={() => go(-1)} className={`${btn} start-1`}><Icon name="arrow" className="h-4 w-4 rotate-180 rtl:rotate-0" /></button>}
      {can.next && <button type="button" aria-label={t("open.rail.next")} onClick={() => go(1)} className={`${btn} end-1`}><Icon name="arrow" className="h-4 w-4 rtl:rotate-180" /></button>}
    </div>
  );
}
