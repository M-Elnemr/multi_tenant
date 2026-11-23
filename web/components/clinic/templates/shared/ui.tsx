"use client";

import Link from "next/link";
import { motion, useInView, useReducedMotion } from "motion/react";
import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { useMe } from "@/components/hooks";
import { useT } from "@/components/i18n-provider";
import { fileUrl, type Variant } from "@/lib/media";
import { initials } from "./data";
import { cnTFrom } from "./dict";

/* eslint-disable @next/next/no-img-element */

/** A stored photo, never recoloured. With no file id (or a file that fails to load) it shows `fallback` (default: the person's initials). */
export function Photo({ id, alt = "", variant = "medium", className = "", name, fallback, eager }: { id?: string | null; alt?: string; variant?: Variant; className?: string; name?: string; fallback?: React.ReactNode; eager?: boolean }) {
  const [bad, setBad] = useState(false);
  if (!id || bad) return <div className={`${className} cn-ph`} role={alt ? "img" : undefined} aria-label={alt || undefined}>{fallback ?? (name ? <span>{initials(name)}</span> : null)}</div>;
  return <img src={fileUrl(id, variant)} alt={alt} className={className} loading={eager ? "eager" : "lazy"} decoding="async" onError={() => setBad(true)} />;
}

/** Fades content up when it scrolls into view. Visible without JS, and with reduced motion it never moves. */
export function Fade({ children, className = "", delay = 0, y = 18, as = "div" }: { children: React.ReactNode; className?: string; delay?: number; y?: number; as?: "div" | "li" | "section" }) {
  const reduce = useReducedMotion();
  const ref = useRef<HTMLDivElement | null>(null);
  const seen = useInView(ref, { once: true, margin: "0px 0px -6% 0px" });
  const armed = useSyncExternalStore(() => () => {}, () => true, () => false);
  const hidden = armed && !reduce && !seen;
  const M = motion[as] as typeof motion.div;
  return <M ref={ref} className={className} initial={false} animate={hidden ? { opacity: 0, y } : { opacity: 1, y: 0 }} transition={hidden ? { duration: 0 } : { duration: 0.6, delay: delay / 1000, ease: [0.22, 1, 0.36, 1] }}>{children}</M>;
}

/** Sign-in / portal link in the visitor's state, styled by the template through `className`. */
export function AuthLink({ className = "", portal = true }: { className?: string; portal?: boolean }) {
  const t = useT();
  const { me, ready } = useMe();
  if (!ready) return null;
  if (!me) return <Link href="/login" className={className}>{portal ? t("nav.login") : t("nav.staffLogin")}</Link>;
  const patient = me.roles.includes("PATIENT");
  return <Link href={patient ? "/portal" : "/dashboard"} className={className}>{patient ? t("nav.portal") : t("nav.dashboard")}</Link>;
}

/** Locks page scroll and closes on Escape while a menu overlay is open. */
export function useOverlay(open: boolean, close: () => void) {
  useEffect(() => {
    if (!open) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    const on = (e: KeyboardEvent) => { if (e.key === "Escape") close(); };
    window.addEventListener("keydown", on);
    return () => { document.body.style.overflow = prev; window.removeEventListener("keydown", on); };
  }, [open, close]);
}

/** Adds data-scrolled to its child wrapper once the page has scrolled (for headers that change on scroll). */
export function useScrolled(offset = 24) {
  const [s, setS] = useState(false);
  useEffect(() => {
    const on = () => setS(window.scrollY > offset);
    on();
    window.addEventListener("scroll", on, { passive: true });
    return () => window.removeEventListener("scroll", on);
  }, [offset]);
  return s;
}

/** Client-side `t` for cn components: the server passes the dictionary down, the app's own keys still resolve through the provider. */
export function useCnT(dict: Record<string, string>) {
  const base = useT();
  return cnTFrom(dict, base);
}
