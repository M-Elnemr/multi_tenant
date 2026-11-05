"use client";

import Link from "next/link";
import { AnimatePresence, motion, useMotionValueEvent, useScroll } from "motion/react";
import { useState } from "react";
import { LogoMark } from "@/components/brand";
import { Icon } from "@/components/icons";
import { useMe } from "@/components/hooks";
import { ScrollBar, useActiveSection } from "./kit";

export type NavLink = { href: string; label: string };

/**
 * The landing pages' header: transparent over the hero, a glass bar once the page scrolls. `tone` picks the vertical's look
 * ("shop" is dark/AI, "clinic" is calm paper). Section links highlight while that section is on screen.
 */
export function LandingNav({ tone, brand, links, other, login, cta, ctaHref, locale }: {
  tone: "shop" | "clinic"; brand: string; links: NavLink[]; other: NavLink; login: string; cta: string; ctaHref: string; locale: string;
}) {
  const { scrollY } = useScroll();
  const [solid, setSolid] = useState(false);
  const [open, setOpen] = useState(false);
  useMotionValueEvent(scrollY, "change", (v) => setSolid(v > 24));
  const [ids] = useState(() => links.map((l) => l.href.replace("#", "")));
  const active = useActiveSection(ids);
  const { me, ready } = useMe();
  const dark = tone === "shop";
  const lang = <a href={`/api/lang?l=${locale === "ar" ? "en" : "ar"}`} className="lp-nav-link inline-flex items-center gap-1.5"><Icon name="globe" className="h-4 w-4" />{locale === "ar" ? "EN" : "عربي"}</a>;
  const account = !ready ? null : me
    ? <Link href="/portal" className="lp-nav-link">{locale === "ar" ? "أماكني" : "My places"}</Link>
    : <Link href="/login" className="lp-nav-link">{login}</Link>;
  return (
    <>
      <ScrollBar className={dark ? "bg-[linear-gradient(90deg,#7c5cff,#22d3ee,#a3e635)]" : "bg-[#0f5e57]"} />
      <header data-solid={solid || open} className={`lp-nav fixed inset-x-0 top-0 z-50 ${dark ? "lp-nav-dark" : "lp-nav-light"}`}>
        <div className="mx-auto flex h-16 max-w-7xl items-center justify-between gap-4 px-4 sm:px-6">
          <Link href="/" className="flex items-center gap-2.5" aria-label={brand}>
            <LogoMark className="h-9 w-9" />
            <span className="font-display text-lg font-bold tracking-tight">{brand}</span>
          </Link>
          <nav className="hidden items-center gap-1 lg:flex" aria-label="Sections">
            {links.map((l) => (
              <a key={l.href} href={l.href} className="lp-nav-link relative">
                {active === l.href.slice(1) && <motion.span layoutId={`lp-nav-${tone}`} className="lp-nav-pill absolute inset-0 -z-10 rounded-full" transition={{ type: "spring", stiffness: 400, damping: 34 }} />}
                {l.label}
              </a>
            ))}
            <Link href={other.href} className="lp-nav-link opacity-70">{other.label}</Link>
          </nav>
          <div className="flex items-center gap-1.5">
            <span className="hidden sm:contents">{lang}{account}</span>
            <Link href={ctaHref} className="lp-cta lp-cta-sm">{cta}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Link>
            <button type="button" onClick={() => setOpen(!open)} className="lp-nav-link grid h-10 w-10 place-items-center lg:hidden" aria-label="Menu" aria-expanded={open}>
              <Icon name={open ? "close" : "menu"} className="h-5 w-5" />
            </button>
          </div>
        </div>
        <AnimatePresence>
          {open && (
            <motion.nav initial={{ height: 0, opacity: 0 }} animate={{ height: "auto", opacity: 1 }} exit={{ height: 0, opacity: 0 }} transition={{ duration: 0.3, ease: [0.2, 0.8, 0.2, 1] }} className="overflow-hidden lg:hidden">
              <div className="flex flex-col gap-1 px-4 pb-5 pt-1">
                {links.map((l) => <a key={l.href} href={l.href} onClick={() => setOpen(false)} className="lp-nav-link py-3 text-base">{l.label}</a>)}
                <Link href={other.href} className="lp-nav-link py-3 text-base">{other.label}</Link>
                <div className="mt-2 flex items-center gap-2 border-t border-current/10 pt-3">{lang}{account}</div>
              </div>
            </motion.nav>
          )}
        </AnimatePresence>
      </header>
    </>
  );
}
