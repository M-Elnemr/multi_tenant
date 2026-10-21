"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { Icon } from "../icons";

export type HeroSlide = { id: string; eyebrow: string; title: string; sub: string; cta: string; href: string; from: string; to: string; image?: string | null; emoji?: string };

/** Auto-advancing banner carousel that is also a native swipeable scroller (scroll-snap), so it feels right with a thumb and with a mouse. */
export function OpenHero({ slides }: { slides: HeroSlide[] }) {
  const ref = useRef<HTMLDivElement>(null);
  const [i, setI] = useState(0);
  const [paused, setPaused] = useState(false);

  const go = useCallback((n: number) => {
    const el = ref.current;
    if (!el) return;
    const idx = (n + slides.length) % slides.length;
    const rtl = getComputedStyle(el).direction === "rtl";
    el.scrollTo({ left: (rtl ? -1 : 1) * idx * el.clientWidth, behavior: "smooth" });
    setI(idx);
  }, [slides.length]);

  useEffect(() => {
    if (paused || slides.length < 2) return;
    const id = setInterval(() => go(i + 1), 5500);
    return () => clearInterval(id);
  }, [i, paused, go, slides.length]);

  const onScroll = () => {
    const el = ref.current;
    if (!el) return;
    const idx = Math.round(Math.abs(el.scrollLeft) / el.clientWidth);
    if (idx !== i) setI(idx);
  };

  return (
    <div className="o-hero o-container mt-3 md:mt-5" onMouseEnter={() => setPaused(true)} onMouseLeave={() => setPaused(false)} onTouchStart={() => setPaused(true)}>
      <div className="relative overflow-hidden rounded-[26px]">
        <div ref={ref} onScroll={onScroll} className="o-slides">
          {slides.map((s) => (
            <Link key={s.id} href={s.href} className="o-slide o-grain flex items-center text-white" style={{ background: `linear-gradient(120deg, ${s.from}, ${s.to})` }}>
              <div className="relative z-[1] flex w-full items-center justify-between gap-4 px-6 py-8 md:px-14">
                <div className="max-w-[26rem] md:max-w-xl">
                  <p className="mb-2 inline-block rounded-full bg-white/15 px-3 py-1 text-[11px] font-extrabold uppercase tracking-[.14em] backdrop-blur md:text-xs">{s.eyebrow}</p>
                  <h2 className="text-[1.65rem] font-black leading-[1.15] md:text-5xl">{s.title}</h2>
                  <p className="mt-2 line-clamp-2 text-sm text-white/85 md:mt-3 md:text-lg">{s.sub}</p>
                  <span className="mt-4 inline-flex items-center gap-2 rounded-full bg-white px-5 py-2.5 text-sm font-extrabold text-[var(--o-navy)] shadow-lg transition group-hover:scale-105 md:mt-6 md:px-7 md:py-3">{s.cta}<Icon name="arrow" className="h-4 w-4 rtl:rotate-180" /></span>
                </div>
                <div className="relative hidden shrink-0 sm:block">
                  {s.image ? (
                    // eslint-disable-next-line @next/next/no-img-element
                    <img src={s.image} alt="" width={340} height={340} className="h-44 w-44 rotate-3 rounded-[2rem] border-4 border-white/70 object-cover shadow-2xl md:h-72 md:w-72" />
                  ) : (
                    <span className="grid h-40 w-40 place-items-center text-8xl drop-shadow-2xl md:h-64 md:w-64 md:text-[10rem]">{s.emoji ?? "🛍️"}</span>
                  )}
                </div>
              </div>
            </Link>
          ))}
        </div>
        {slides.length > 1 && (
          <>
            <button type="button" aria-label="previous" onClick={() => go(i - 1)} className="absolute start-3 top-1/2 hidden h-11 w-11 -translate-y-1/2 place-items-center rounded-full bg-white/90 text-[var(--o-navy)] shadow-lg transition hover:scale-110 md:grid"><Icon name="arrow" className="h-5 w-5 rotate-180 rtl:rotate-0" /></button>
            <button type="button" aria-label="next" onClick={() => go(i + 1)} className="absolute end-3 top-1/2 hidden h-11 w-11 -translate-y-1/2 place-items-center rounded-full bg-white/90 text-[var(--o-navy)] shadow-lg transition hover:scale-110 md:grid"><Icon name="arrow" className="h-5 w-5 rtl:rotate-180" /></button>
            <div className="absolute inset-x-0 bottom-3 flex justify-center gap-1.5">
              {slides.map((s, n) => <button key={s.id} type="button" aria-label={`${n + 1}`} onClick={() => go(n)} className={`h-1.5 rounded-full transition-all ${n === i ? "w-7 bg-white" : "w-1.5 bg-white/50"}`} />)}
            </div>
          </>
        )}
      </div>
    </div>
  );
}
