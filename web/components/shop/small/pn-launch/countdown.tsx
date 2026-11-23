"use client";
import { useEffect, useState } from "react";

/** Counts down to a REAL offer end date (from the offer itself). Nothing is drawn once it has passed. */
export function Countdown({ endsAt, labels }: { endsAt: string; labels: { d: string; h: string; m: string; s: string } }) {
  const end = new Date(endsAt).getTime();
  const [now, setNow] = useState<number | null>(null);
  useEffect(() => { const tick = () => setNow(Date.now()); tick(); const id = setInterval(tick, 1000); return () => clearInterval(id); }, []);
  if (!Number.isFinite(end) || now === null || end <= now) return null;
  const s = Math.floor((end - now) / 1000);
  const parts: [number, string][] = [[Math.floor(s / 86400), labels.d], [Math.floor((s % 86400) / 3600), labels.h], [Math.floor((s % 3600) / 60), labels.m], [s % 60, labels.s]];
  return <div className="pnl-clock" role="timer">{parts.map(([n, l]) => <span key={l}><b className="tnum">{String(n).padStart(2, "0")}</b><i>{l}</i></span>)}</div>;
}
