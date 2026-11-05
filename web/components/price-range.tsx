"use client";

import { useState } from "react";
import { money } from "@/lib/format";
import type { Locale } from "@/lib/i18n";

/**
 * Dual-thumb price slider bounded by the real price range of the results (facets), with the two values shown live.
 * Values are minor units; `onCommit` fires when a thumb is released (not on every pixel), so the URL / query updates once.
 * Built on two native range inputs: keyboard, screen readers and touch work without extra code.
 */
export function PriceRange({ min, max, value, onCommit, currency, locale, labels, accent = "var(--brand)" }: {
  min: number; max: number; value: [number | null, number | null]; onCommit: (lo: number | null, hi: number | null) => void;
  currency: string; locale: Locale; labels: { min: string; max: string }; accent?: string;
}) {
  const step = Math.max(100, Math.round((max - min) / 100 / 100) * 100);
  const [lo, setLo] = useState(value[0] ?? min);
  const [hi, setHi] = useState(value[1] ?? max);
  const span = Math.max(1, max - min);
  const pLo = ((Math.min(lo, hi) - min) / span) * 100, pHi = ((Math.max(lo, hi) - min) / span) * 100;
  const commit = () => { const a = Math.min(lo, hi), b = Math.max(lo, hi); onCommit(a <= min ? null : a, b >= max ? null : b); };
  if (max <= min) return null;
  return (
    <div className="select-none" dir="ltr">
      <div className="mb-3 flex items-center justify-between gap-2 text-sm font-bold tabular-nums">
        <span className="rounded-full border border-current/15 px-3 py-1" style={{ color: accent }} aria-label={labels.min}>{money(Math.min(lo, hi), currency, locale)}</span>
        <span className="h-px flex-1 bg-current opacity-15" />
        <span className="rounded-full border border-current/15 px-3 py-1" style={{ color: accent }} aria-label={labels.max}>{money(Math.max(lo, hi), currency, locale)}</span>
      </div>
      <div className="relative h-8">
        <div className="absolute inset-x-0 top-1/2 h-1.5 -translate-y-1/2 rounded-full bg-current opacity-10" />
        <div className="absolute top-1/2 h-1.5 -translate-y-1/2 rounded-full transition-[left,right] duration-75" style={{ left: `${pLo}%`, right: `${100 - pHi}%`, background: accent }} />
        {[[lo, setLo, labels.min], [hi, setHi, labels.max]].map(([v, set, label], i) => (
          <input key={i} type="range" min={min} max={max} step={step} value={v as number} aria-label={label as string}
            onChange={(e) => (set as (n: number) => void)(Number(e.target.value))} onPointerUp={commit} onKeyUp={commit} onTouchEnd={commit}
            className="price-thumb pointer-events-none absolute inset-0 h-8 w-full appearance-none bg-transparent" style={{ ["--accent" as string]: accent }} />
        ))}
      </div>
    </div>
  );
}
