"use client";

import { useId } from "react";

/** A small area chart with a few grid lines and the first, middle and last dates. Pure SVG, no library. */
export function TrendChart({ points, format, labels }: { points: number[]; format: (n: number) => string; labels: string[] }) {
  const id = useId();
  const w = 640, h = 180, pad = 8;
  const max = Math.max(1, ...points);
  const step = points.length > 1 ? (w - pad * 2) / (points.length - 1) : 0;
  const xy = points.map((v, i) => [pad + i * step, h - pad - (v / max) * (h - pad * 2)] as const);
  const line = xy.map(([x, y], i) => `${i ? "L" : "M"}${x.toFixed(1)},${y.toFixed(1)}`).join(" ");
  const area = xy.length ? `${line} L${xy[xy.length - 1][0].toFixed(1)},${h - pad} L${xy[0][0].toFixed(1)},${h - pad} Z` : "";
  const mid = Math.floor(points.length / 2);
  return (
    <div className="w-full" dir="ltr">
      <svg viewBox={`0 0 ${w} ${h}`} className="h-44 w-full" role="img" aria-label="trend">
        <defs><linearGradient id={id} x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor="var(--color-brand, #4f46e5)" stopOpacity="0.28" /><stop offset="100%" stopColor="var(--color-brand, #4f46e5)" stopOpacity="0" /></linearGradient></defs>
        {[0.25, 0.5, 0.75].map((g) => <line key={g} x1={pad} x2={w - pad} y1={h - pad - g * (h - pad * 2)} y2={h - pad - g * (h - pad * 2)} stroke="currentColor" strokeOpacity="0.08" />)}
        {area && <path d={area} fill={`url(#${id})`} />}
        {line && <path d={line} fill="none" stroke="var(--color-brand, #4f46e5)" strokeWidth="2.5" strokeLinejoin="round" strokeLinecap="round" />}
        {xy.length <= 31 && xy.map(([x, y], i) => <circle key={i} cx={x} cy={y} r="3" fill="var(--color-brand, #4f46e5)"><title>{`${labels[i]}: ${format(points[i])}`}</title></circle>)}
      </svg>
      <div className="mt-1 flex justify-between text-[11px] text-slate-500"><span>{labels[0]}</span><span>{labels[mid]}</span><span>{labels[labels.length - 1]}</span></div>
      <div className="mt-1 text-end text-[11px] text-slate-400">{format(max)}</div>
    </div>
  );
}

/** Horizontal bars for a split (channel, payment, area): the label, a bar sized by share, the value. */
export function Bars({ rows }: { rows: { key: string; label: string; value: number; text: string; sub?: string }[] }) {
  const max = Math.max(1, ...rows.map((r) => r.value));
  if (rows.length === 0) return null;
  return (
    <ul className="space-y-2.5">
      {rows.map((r) => (
        <li key={r.key} className="text-sm">
          <div className="flex items-baseline justify-between gap-3"><span className="min-w-0 truncate">{r.label}</span><span className="shrink-0 font-semibold">{r.text}{r.sub ? <span className="ms-2 text-xs font-normal text-slate-500">{r.sub}</span> : null}</span></div>
          <div className="mt-1 h-2 overflow-hidden rounded-full bg-slate-100"><div className="h-full rounded-full bg-brand" style={{ width: `${Math.max(2, (r.value / max) * 100)}%` }} /></div>
        </li>
      ))}
    </ul>
  );
}

/** Downloads rows as a CSV that Excel opens correctly in Arabic (BOM). */
export function downloadCsv(name: string, head: string[], rows: (string | number)[][]) {
  const cell = (v: string | number) => { const s = String(v); return /^[=+\-@]/.test(s) ? `'${s}` : s; };
  const esc = (v: string | number) => `"${cell(v).replace(/"/g, '""')}"`;
  const body = [head, ...rows].map((r) => r.map(esc).join(",")).join("\r\n");
  const url = URL.createObjectURL(new Blob(["﻿" + body], { type: "text/csv;charset=utf-8" }));
  const a = document.createElement("a");
  a.href = url; a.download = name; a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
