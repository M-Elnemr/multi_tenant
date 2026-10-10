"use client";

import { AnimatePresence, motion, useReducedMotion, useScroll, useSpring, useTransform } from "motion/react";
import { useEffect, useRef, useState } from "react";

const EASE = [0.22, 1, 0.36, 1] as const;

/** Fades and lifts its content in on load (hero text, buttons). Renders visible without JS thanks to SSR of the final state being the animate target only after hydration. */
export function Rise({ children, delay = 0, className = "", y = 22, style }: { children: React.ReactNode; delay?: number; className?: string; y?: number; style?: React.CSSProperties }) {
  const reduce = useReducedMotion();
  return <motion.div className={className} style={style} initial={reduce ? false : { opacity: 0, y }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.8, delay: delay / 1000, ease: EASE }}>{children}</motion.div>;
}

/** Hero background that drifts slower than the page while scrolling (parallax) and zooms slightly. */
export function ParallaxBg({ children, className = "" }: { children: React.ReactNode; className?: string }) {
  const reduce = useReducedMotion();
  const { scrollY } = useScroll();
  const y = useTransform(scrollY, [0, 600], [0, reduce ? 0 : 120]);
  const scale = useTransform(scrollY, [0, 600], [1.04, reduce ? 1.04 : 1.14]);
  return <motion.div style={{ y, scale }} className={className}>{children}</motion.div>;
}

/** Soft floating light orbs behind the hero content. */
export function Orbs() {
  const reduce = useReducedMotion();
  const orb = (cls: string, dx: number, dy: number, d: number) => (
    <motion.span aria-hidden className={`pointer-events-none absolute rounded-full blur-3xl ${cls}`} animate={reduce ? undefined : { x: [0, dx, 0], y: [0, dy, 0], scale: [1, 1.15, 1] }} transition={{ duration: d, repeat: Infinity, ease: "easeInOut" }} />
  );
  return <>{orb("-start-24 top-10 h-80 w-80 bg-white/20", 60, 30, 16)}{orb("end-0 top-1/3 h-96 w-96 bg-white/15", -50, -40, 20)}{orb("bottom-0 start-1/3 h-64 w-64 bg-black/15", 40, -30, 18)}</>;
}

/** Wraps the sticky header: gains a shadow and a tighter height once the page scrolls. */
export function ScrollHeader({ children }: { children: React.ReactNode }) {
  const [scrolled, setScrolled] = useState(false);
  useEffect(() => {
    const on = () => setScrolled(window.scrollY > 24);
    on();
    window.addEventListener("scroll", on, { passive: true });
    return () => window.removeEventListener("scroll", on);
  }, []);
  return <header className={`c-header sticky top-0 z-30 transition-all duration-300 ${scrolled ? "shadow-[0_8px_30px_-12px_rgb(0_0_0/.25)]" : ""}`} data-scrolled={scrolled || undefined}>{children}</header>;
}

/** Thin reading-progress bar in the brand colour under the header. */
export function ScrollProgress() {
  const { scrollYProgress } = useScroll();
  const x = useSpring(scrollYProgress, { stiffness: 140, damping: 28, mass: 0.2 });
  return <motion.div aria-hidden style={{ scaleX: x, transformOrigin: "0 50%" }} className="fixed inset-x-0 top-0 z-50 h-[3px] bg-[var(--brand)] rtl:origin-right" />;
}

/** A row of chips that scrolls sideways endlessly (pauses on hover). */
export function Marquee({ children }: { children: React.ReactNode }) {
  const reduce = useReducedMotion();
  const ref = useRef<HTMLDivElement>(null);
  return (
    <div className="relative overflow-hidden [mask-image:linear-gradient(to_right,transparent,#000_8%,#000_92%,transparent)]" dir="ltr">
      <motion.div ref={ref} className="flex w-max gap-3 hover:[animation-play-state:paused]" animate={reduce ? undefined : { x: ["0%", "-50%"] }} transition={{ duration: 32, ease: "linear", repeat: Infinity }}>
        <div className="flex shrink-0 gap-3" dir="auto">{children}</div>
        <div className="flex shrink-0 gap-3" aria-hidden dir="auto">{children}</div>
      </motion.div>
    </div>
  );
}

/** Gallery with a full-screen viewer: click an image to open, arrows / Escape to navigate. */
export function Gallery({ ids }: { ids: { thumb: string; full: string }[] }) {
  const [open, setOpen] = useState<number | null>(null);
  const reduce = useReducedMotion();
  useEffect(() => {
    if (open === null) return;
    const h = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(null);
      if (e.key === "ArrowRight") setOpen((i) => (i === null ? i : (i + 1) % ids.length));
      if (e.key === "ArrowLeft") setOpen((i) => (i === null ? i : (i - 1 + ids.length) % ids.length));
    };
    window.addEventListener("keydown", h);
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => { window.removeEventListener("keydown", h); document.body.style.overflow = prev; };
  }, [open, ids.length]);
  return (
    <>
      <div className="grid auto-rows-[9rem] grid-cols-2 gap-3 sm:auto-rows-[11rem] sm:gap-4 md:grid-cols-4">
        {ids.map((g, i) => (
          <motion.button key={g.full} type="button" onClick={() => setOpen(i)} whileHover={reduce ? undefined : { scale: 1.015 }} className={`c-gal group relative block overflow-hidden ${i === 0 ? "col-span-2 row-span-2" : i % 5 === 3 ? "row-span-2" : ""}`} aria-label="Open photo">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={g.thumb} alt="" className="h-full w-full object-cover" loading="lazy" />
            <span className="absolute inset-0 bg-gradient-to-t from-black/40 to-transparent opacity-0 transition-opacity duration-300 group-hover:opacity-100" />
          </motion.button>
        ))}
      </div>
      <AnimatePresence>
        {open !== null && (
          <motion.div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/85 p-4 backdrop-blur-sm" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onClick={() => setOpen(null)} role="dialog" aria-modal="true">
            <motion.img key={open} src={ids[open].full} alt="" className="max-h-[88vh] max-w-full rounded-2xl object-contain shadow-2xl" initial={reduce ? false : { opacity: 0, scale: 0.94 }} animate={{ opacity: 1, scale: 1 }} transition={{ duration: 0.3, ease: EASE }} onClick={(e) => e.stopPropagation()} />
            <button type="button" aria-label="Close" onClick={() => setOpen(null)} className="absolute end-4 top-4 rounded-full bg-white/15 p-3 text-white backdrop-blur transition hover:bg-white/25">✕</button>
            {ids.length > 1 && (<>
              <button type="button" aria-label="Previous" onClick={(e) => { e.stopPropagation(); setOpen((open - 1 + ids.length) % ids.length); }} className="absolute start-4 top-1/2 -translate-y-1/2 rounded-full bg-white/15 p-3 text-white backdrop-blur transition hover:bg-white/25 rtl:rotate-180">‹</button>
              <button type="button" aria-label="Next" onClick={(e) => { e.stopPropagation(); setOpen((open + 1) % ids.length); }} className="absolute end-4 top-1/2 -translate-y-1/2 rounded-full bg-white/15 p-3 text-white backdrop-blur transition hover:bg-white/25 rtl:rotate-180">›</button>
            </>)}
          </motion.div>
        )}
      </AnimatePresence>
    </>
  );
}
