"use client";

import { useState } from "react";

const TINTS = ["#ecdfcb", "#dfe6e0", "#ead8d0", "#dde0ea", "#efe6c7", "#d9e4e8"];
const hash = (s: string) => { let h = 0; for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0; return h; };

/** The fallback when a picture is missing or fails to load: a quiet tinted tile with the first letter of the name (never an emoji, never a fake photo). */
export function Monogram({ name, className = "" }: { name: string; className?: string }) {
  const letter = [...name.trim()][0]?.toUpperCase() ?? "·";
  return (
    <span className={`o-mono ${className}`} style={{ background: TINTS[hash(name) % TINTS.length], fontSize: "3rem" }} aria-hidden>
      {letter}
    </span>
  );
}

/** A picture inside a frame that fills its parent (the parent sets the aspect ratio): lazy, decoded off-thread, and replaced by the monogram on error. */
export function OImg({ src, alt, name, eager = false, className = "", width = 480, height = 600 }: { src?: string | null; alt?: string; name: string; eager?: boolean; className?: string; width?: number; height?: number }) {
  const [bad, setBad] = useState(false);
  if (!src || bad) return <Monogram name={name} />;
  return (
    // eslint-disable-next-line @next/next/no-img-element
    <img src={src} alt={alt ?? ""} width={width} height={height} loading={eager ? "eager" : "lazy"} decoding="async" onError={() => setBad(true)} className={className} />
  );
}
