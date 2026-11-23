"use client";

import { motion, useReducedMotion } from "motion/react";
import type { ReactNode } from "react";

export type RevealKind = "rise" | "clip" | "fade" | "pop" | "slow" | "side";

const START: Record<RevealKind, Record<string, string | number>> = {
  rise: { opacity: 0, y: 22 },
  clip: { opacity: 0, clipPath: "inset(0 0 100% 0)" },
  fade: { opacity: 0 },
  pop: { opacity: 0, scale: 0.94, y: 12 },
  slow: { opacity: 0, y: 10 },
  side: { opacity: 0, x: -24 },
};
const END: Record<RevealKind, Record<string, string | number>> = {
  rise: { opacity: 1, y: 0 }, clip: { opacity: 1, clipPath: "inset(0 0 0% 0)" }, fade: { opacity: 1 }, pop: { opacity: 1, scale: 1, y: 0 }, slow: { opacity: 1, y: 0 }, side: { opacity: 1, x: 0 },
};

/** Scroll-in reveal. With "reduce motion" on, content is simply shown (no transform, no fade). `side` mirrors in RTL through the `rtl` flag. */
export function Reveal({ kind = "rise", delay = 0, className, children, as = "div", rtl = false }: { kind?: RevealKind; delay?: number; className?: string; children: ReactNode; as?: "div" | "li" | "section" | "article"; rtl?: boolean }) {
  const reduce = useReducedMotion();
  const M = motion[as];
  if (reduce) return <M className={className}>{children}</M>;
  const start = kind === "side" && rtl ? { ...START.side, x: 24 } : START[kind];
  return (
    <M className={className} initial={start} whileInView={END[kind]} viewport={{ once: true, margin: "0px 0px -8% 0px" }}
      transition={{ duration: kind === "slow" ? 1 : kind === "pop" ? 0.55 : 0.6, delay, ease: kind === "pop" ? [0.34, 1.3, 0.64, 1] : [0.2, 0.8, 0.2, 1] }}>
      {children}
    </M>
  );
}

/** Number that counts up once when seen (only for real figures from the profile). Falls back to the plain text when reduced motion is on or the text is not numeric. */
export function CountUp({ text, className }: { text: string; className?: string }) {
  const reduce = useReducedMotion();
  const m = text.match(/^([^\d٠-٩]*)([\d٠-٩]+)(.*)$/);
  if (reduce || !m) return <span className={className}>{text}</span>;
  const ar = /[٠-٩]/.test(m[2]);
  const n = parseInt(ar ? m[2].replace(/[٠-٩]/g, (d) => String("٠١٢٣٤٥٦٧٨٩".indexOf(d))) : m[2], 10);
  return (
    <span className={className}>
      {m[1]}
      <motion.span initial={{ opacity: 0.2 }} whileInView={{ opacity: 1 }} viewport={{ once: true }} transition={{ duration: 0.8 }}
        onViewportEnter={(e) => {
          const el = e?.target as HTMLElement | undefined; if (!el) return;
          const t0 = performance.now();
          const tick = (now: number) => { const k = Math.min(1, (now - t0) / 900); const v = Math.round(n * (1 - Math.pow(1 - k, 3))); el.textContent = ar ? String(v).replace(/\d/g, (d) => "٠١٢٣٤٥٦٧٨٩"[+d]) : String(v); if (k < 1) requestAnimationFrame(tick); };
          requestAnimationFrame(tick);
        }}>{m[2]}</motion.span>
      {m[3]}
    </span>
  );
}
