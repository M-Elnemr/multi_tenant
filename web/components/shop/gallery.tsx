"use client";

import { useState } from "react";
import { mediaUrl } from "@/lib/media";

type M = { url?: string | null; altText?: string; mediaBase?: string | null; mediaExt?: string | null };

/** Large image with hover/touch zoom (the image follows the pointer), thumbnails below, swipe-friendly on phones. */
export function Gallery({ media, name, badge }: { media: M[]; name: string; badge?: React.ReactNode }) {
  const [i, setI] = useState(0);
  const [zoom, setZoom] = useState<{ x: number; y: number } | null>(null);
  const cur = media[i];
  return (
    <div className="space-y-3 lg:sticky lg:top-32">
      <div className="relative aspect-square overflow-hidden rounded-[28px] border border-[var(--s-line)] bg-[var(--s-soft)]"
        onMouseMove={(e) => { const r = e.currentTarget.getBoundingClientRect(); setZoom({ x: ((e.clientX - r.left) / r.width) * 100, y: ((e.clientY - r.top) / r.height) * 100 }); }}
        onMouseLeave={() => setZoom(null)}>
        {cur ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={mediaUrl(cur, "medium") ?? ""} alt={cur.altText ?? name} className="h-full w-full object-cover transition-transform duration-200 ease-out"
            style={zoom ? { transform: "scale(1.9)", transformOrigin: `${zoom.x}% ${zoom.y}%` } : undefined} />
        ) : <div className="grid h-full place-items-center text-7xl opacity-25">🛍️</div>}
        <div className="absolute start-4 top-4">{badge}</div>
        {media.length > 1 && (
          <>
            <button onClick={() => setI((i - 1 + media.length) % media.length)} aria-label="previous" className="absolute start-3 top-1/2 grid h-10 w-10 -translate-y-1/2 place-items-center rounded-full bg-white/90 shadow transition hover:bg-white"><span className="flip-rtl">‹</span></button>
            <button onClick={() => setI((i + 1) % media.length)} aria-label="next" className="absolute end-3 top-1/2 grid h-10 w-10 -translate-y-1/2 place-items-center rounded-full bg-white/90 shadow transition hover:bg-white"><span className="flip-rtl">›</span></button>
          </>
        )}
      </div>
      {media.length > 1 && (
        <div className="s-scroll-x">
          {media.map((m, idx) => (
            <button key={idx} onClick={() => setI(idx)} className={`h-20 w-20 overflow-hidden rounded-2xl border-2 transition ${idx === i ? "border-[var(--brand)]" : "border-transparent opacity-70 hover:opacity-100"}`}>
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={mediaUrl(m, "thumb") ?? ""} alt="" className="h-full w-full object-cover" />
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
