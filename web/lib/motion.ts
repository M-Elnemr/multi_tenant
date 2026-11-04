// Motion language: one set of durations and curves for every screen (mirrors --dur-* / --ease-* in globals.css).
// Dashboards use the fast ones; public sites may use `slow` for scroll storytelling.
import type { Transition, Variants } from "motion/react";

export const ease = {
  out: [0.2, 0.8, 0.2, 1] as const,
  inOut: [0.65, 0, 0.35, 1] as const,
};

export const dur = { fast: 0.12, base: 0.2, calm: 0.32, slow: 0.56 };

export const spring: Transition = { type: "spring", stiffness: 520, damping: 40, mass: 0.8 };
export const softSpring: Transition = { type: "spring", stiffness: 260, damping: 30 };

export const fadeRise: Variants = {
  hidden: { opacity: 0, y: 10 },
  show: { opacity: 1, y: 0, transition: { duration: dur.calm, ease: ease.out } },
};

export const stagger = (gap = 0.04, delay = 0): Variants => ({
  hidden: {},
  show: { transition: { staggerChildren: gap, delayChildren: delay } },
});

export const sheet: Variants = {
  hidden: { y: "100%" },
  show: { y: 0, transition: { duration: dur.calm, ease: ease.out } },
  exit: { y: "100%", transition: { duration: dur.base, ease: ease.inOut } },
};

export const pop: Variants = {
  hidden: { opacity: 0, scale: 0.96, y: 6 },
  show: { opacity: 1, scale: 1, y: 0, transition: { duration: dur.base, ease: ease.out } },
  exit: { opacity: 0, scale: 0.98, y: 4, transition: { duration: dur.fast } },
};

/** Scroll reveal for public sites: rise in once when the block reaches the viewport. */
export const reveal = {
  initial: { opacity: 0, y: 24 },
  whileInView: { opacity: 1, y: 0 },
  viewport: { once: true, margin: "-60px" },
  transition: { duration: dur.slow, ease: ease.out },
} as const;
