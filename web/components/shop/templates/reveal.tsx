"use client";

import { motion, useReducedMotion } from "motion/react";
import type { ReactNode } from "react";
import { reveal } from "@/lib/motion";

/** Scroll-triggered entrance for storefront blocks: rises in once, never re-animates, and stays still for reduced-motion visitors. */
export function Reveal({ children, className, delay = 0 }: { children: ReactNode; className?: string; delay?: number }) {
  const reduce = useReducedMotion();
  if (reduce) return <div className={className}>{children}</div>;
  return (
    <motion.div className={className} initial={reveal.initial} whileInView={reveal.whileInView} viewport={reveal.viewport} transition={{ ...reveal.transition, delay }}>
      {children}
    </motion.div>
  );
}
