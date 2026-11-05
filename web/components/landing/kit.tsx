"use client";

import { animate, motion, useInView, useMotionValue, useReducedMotion, useScroll, useSpring, useTransform } from "motion/react";
import { useEffect, useRef, useState } from "react";
import { ease } from "@/lib/motion";

/** Rises into view once, when the block reaches the viewport. `delay` in seconds. */
export function Reveal({ children, delay = 0, y = 28, className, as = "div" }: { children: React.ReactNode; delay?: number; y?: number; className?: string; as?: "div" | "li" | "section" | "span" }) {
  const reduce = useReducedMotion();
  const C = motion[as];
  return (
    <C className={className} initial={reduce ? false : { opacity: 0, y, filter: "blur(6px)" }} whileInView={{ opacity: 1, y: 0, filter: "blur(0px)" }}
      viewport={{ once: true, margin: "0px 0px -12% 0px" }} transition={{ duration: 0.8, ease: ease.out, delay }}>
      {children}
    </C>
  );
}

/** Words of a headline rising one after another. */
export function SplitWords({ text, className, delay = 0 }: { text: string; className?: string; delay?: number }) {
  const reduce = useReducedMotion();
  return (
    <span className={className} aria-label={text}>
      {text.split(" ").map((w, i) => (
        <span key={i} className="inline-block overflow-hidden pb-[0.12em] align-bottom" aria-hidden>
          <motion.span className="inline-block" initial={reduce ? false : { y: "110%" }} animate={{ y: 0 }} transition={{ duration: 0.9, ease: ease.out, delay: delay + i * 0.06 }}>{w}{" "}</motion.span>
        </span>
      ))}
    </span>
  );
}

/** Counts up to `value` when it comes into view; digits follow the locale. */
export function Counter({ value, locale, prefix = "", suffix = "", className }: { value: number; locale: string; prefix?: string; suffix?: string; className?: string }) {
  const ref = useRef<HTMLSpanElement>(null);
  const seen = useInView(ref, { once: true, margin: "-10% 0px" });
  const reduce = useReducedMotion();
  const fmt = (n: number) => `${prefix}${Math.round(n).toLocaleString(locale === "ar" ? "ar-EG" : "en-US")}${suffix}`;
  useEffect(() => {
    const el = ref.current;
    if (!el || !seen) return;
    if (reduce) { el.textContent = fmt(value); return; }
    const c = animate(0, value, { duration: 1.6, ease: ease.out, onUpdate: (v) => { el.textContent = fmt(v); } });
    return () => c.stop();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [seen, value, reduce]);
  return <span ref={ref} className={className}>{fmt(reduce ? value : 0)}</span>;
}

/** Moves its child along y as the page scrolls past it (subtle depth). */
export function Parallax({ children, distance = 60, className }: { children: React.ReactNode; distance?: number; className?: string }) {
  const ref = useRef<HTMLDivElement>(null);
  const reduce = useReducedMotion();
  const { scrollYProgress } = useScroll({ target: ref, offset: ["start end", "end start"] });
  const y = useTransform(scrollYProgress, [0, 1], reduce ? [0, 0] : [distance, -distance]);
  return <motion.div ref={ref} style={{ y }} className={className}>{children}</motion.div>;
}

/** Tilts toward the pointer (desktop only; still for touch and reduced motion). */
export function Tilt({ children, className, max = 6 }: { children: React.ReactNode; className?: string; max?: number }) {
  const reduce = useReducedMotion();
  const rx = useMotionValue(0), ry = useMotionValue(0);
  const sx = useSpring(rx, { stiffness: 140, damping: 18 }), sy = useSpring(ry, { stiffness: 140, damping: 18 });
  return (
    <motion.div className={className} style={{ rotateX: sx, rotateY: sy, transformPerspective: 1200 }}
      onPointerMove={(e) => {
        if (reduce || e.pointerType !== "mouse") return;
        const r = e.currentTarget.getBoundingClientRect();
        ry.set(((e.clientX - r.left) / r.width - 0.5) * max * 2);
        rx.set(-((e.clientY - r.top) / r.height - 0.5) * max * 2);
      }}
      onPointerLeave={() => { rx.set(0); ry.set(0); }}>
      {children}
    </motion.div>
  );
}

/** True once the element has been on screen (starts scripted demos only when someone can see them). */
export function useSeen<T extends Element>(margin = "-15% 0px") {
  const ref = useRef<T>(null);
  const seen = useInView(ref, { once: false, margin: margin as `${number}px` });
  return [ref, seen] as const;
}

/** A thin progress bar across the top of the page. */
export function ScrollBar({ className = "bg-current" }: { className?: string }) {
  const { scrollYProgress } = useScroll();
  const x = useSpring(scrollYProgress, { stiffness: 200, damping: 30 });
  return <motion.div aria-hidden style={{ scaleX: x }} className={`fixed inset-x-0 top-0 z-[60] h-[2px] origin-[0%] rtl:origin-[100%] ${className}`} />;
}

/** The section the visitor is reading, for highlighting the nav. */
export function useActiveSection(ids: string[]) {
  const [active, setActive] = useState<string | null>(null);
  useEffect(() => {
    const els = ids.map((id) => document.getElementById(id)).filter(Boolean) as HTMLElement[];
    const io = new IntersectionObserver((entries) => {
      for (const e of entries) if (e.isIntersecting) setActive(e.target.id);
    }, { rootMargin: "-45% 0px -50% 0px" });
    els.forEach((el) => io.observe(el));
    return () => io.disconnect();
  }, [ids]);
  return active;
}
