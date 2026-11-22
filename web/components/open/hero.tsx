"use client";

import Link from "next/link";
import { motion, useReducedMotion } from "motion/react";
import { Icon } from "../icons";
import { OImg } from "./media";

export type HeroTile = { src?: string | null; name: string; href: string };

/** The home hero: an editorial headline with two actions, and up to three real products as arched frames on the other side. No carousel, nothing auto-plays. */
export function OpenHero({ eyebrow, title, sub, cta, href, secondary, tiles }: { eyebrow: string; title: string; sub: string; cta: string; href: string; secondary: { label: string; href: string }; tiles: HeroTile[] }) {
  const reduce = useReducedMotion();
  const rise = (i: number) => (reduce ? {} : { initial: { opacity: 0, y: 18 }, animate: { opacity: 1, y: 0 }, transition: { duration: 0.6, delay: 0.08 * i, ease: [0.22, 1, 0.36, 1] as const } });
  const pop = (i: number) => (reduce ? {} : { initial: { opacity: 0, y: 30, scale: 0.97 }, animate: { opacity: 1, y: 0, scale: 1 }, transition: { duration: 0.8, delay: 0.15 + 0.1 * i, ease: [0.22, 1, 0.36, 1] as const } });
  const shown = tiles.slice(0, 3);
  return (
    <div className="o-container mt-3 md:mt-6">
      <div className="o-hero-bg relative overflow-hidden rounded-[28px] md:rounded-[36px]">
        <div className="grid items-center gap-8 px-5 py-9 md:grid-cols-[1.05fr_1fr] md:gap-10 md:px-14 md:py-16">
          <div className="min-w-0">
            <motion.p {...rise(0)} className="o-eyebrow">{eyebrow}</motion.p>
            <motion.h1 {...rise(1)} className="o-display o-h1 mt-3 max-w-[16ch] text-balance">{title}</motion.h1>
            <motion.p {...rise(2)} className="mt-4 max-w-md text-[15px] leading-relaxed text-[var(--o-ink-2)] md:text-lg">{sub}</motion.p>
            <motion.div {...rise(3)} className="mt-7 flex flex-wrap items-center gap-3">
              <Link href={href} className="o-btn o-btn-dark">{cta}<Icon name="arrow" className="h-4 w-4 rtl:rotate-180" /></Link>
              <Link href={secondary.href} className="o-btn o-btn-ghost">{secondary.label}</Link>
            </motion.div>
          </div>
          {shown.length > 0 && (
            <div className="relative mx-auto grid w-full max-w-md grid-cols-3 items-end gap-2.5 md:max-w-none md:gap-4" aria-hidden={false}>
              {shown.map((t, i) => (
                <motion.div key={t.href} {...pop(i)} className={i === 1 ? "pb-0" : "pb-6 md:pb-10"}>
                  <Link href={t.href} className={`o-frame o-arch block shadow-[var(--o-shadow-lg)] ${i === 1 ? "aspect-[3/4.4]" : "aspect-[3/4]"}`} aria-label={t.name}>
                    <OImg src={t.src} alt={t.name} name={t.name} eager={i === 1} className="transition duration-700 hover:scale-105" />
                  </Link>
                </motion.div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
