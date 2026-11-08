"use client";

import Link from "next/link";
import type { ReactNode } from "react";
import { motion, useReducedMotion } from "motion/react";
import { useI18n } from "../i18n-provider";
import { Icon, type IconName } from "../icons";
import { cx } from "../ui";

/**
 * Small building blocks shared by the law-office dashboard screens (navy + brass on ivory, classical and calm).
 * They only style things: every screen keeps its own API calls and permissions.
 */

export const NAVY = "#14213d";
export const BRASS = "#b08d57";
export const BRASS_LIGHT = "#d9b97f";
export const IVORY = "#f4ecdc";

/** The locale tag for Intl. */
export function useIntl() {
  const { locale } = useI18n();
  return locale === "ar" ? "ar-EG" : "en-GB";
}

/** Eyebrow for PageHeader: a short brass rule and the section name. */
export function Eyebrow({ children }: { children: ReactNode }) {
  return (
    <span className="inline-flex items-center gap-2" style={{ color: "var(--rail-accent-ink, #8a6a3a)" }}>
      <span className="h-px w-5 rounded-full" style={{ background: "var(--rail-accent, #b08d57)" }} aria-hidden />
      {children}
    </span>
  );
}

/** A link that looks like a Button (primary / secondary / ghost). */
export function LinkButton({ href, children, variant = "secondary", icon, className, external }: { href: string; children: ReactNode; variant?: "primary" | "secondary" | "ghost" | "ink"; icon?: IconName; className?: string; external?: boolean }) {
  const styles = {
    primary: "bg-brand text-white shadow-[inset_0_1px_0_rgb(255_255_255/.18),0_1px_2px_rgb(16_18_24/.18)] hover:bg-brand-dark",
    ink: "bg-ink text-surface hover:opacity-90",
    secondary: "bg-surface text-ink border border-line-strong shadow-[0_1px_2px_rgb(16_18_24/.05)] hover:bg-surface-2",
    ghost: "text-ink-2 hover:bg-[color-mix(in_srgb,var(--ink)_6%,transparent)] hover:text-ink",
  }[variant];
  const cls = cx("inline-flex h-10 shrink-0 items-center justify-center gap-2 whitespace-nowrap rounded-[10px] px-4 text-sm font-semibold transition active:scale-[.97] pointer-coarse:min-h-11", styles, className);
  const body = <>{icon && <Icon name={icon} className="h-4 w-4" />}{children}</>;
  return external ? <a href={href} target="_blank" rel="noopener noreferrer" className={cls}>{body}</a> : <Link href={href} className={cls}>{body}</Link>;
}

/** A court-diary date block: weekday, day number, month. `tone` tints it (brass for today, amber waiting, slate past). */
export function DateTile({ date, tone = "navy", size = "md" }: { date: Date; tone?: "navy" | "brass" | "amber" | "slate" | "emerald"; size?: "sm" | "md" }) {
  const intl = useIntl();
  const tones = {
    navy: "bg-[color-mix(in_srgb,#14213d_8%,transparent)] text-ink ring-[color-mix(in_srgb,#14213d_14%,transparent)]",
    brass: "bg-[color-mix(in_srgb,#b08d57_16%,transparent)] text-ink ring-[color-mix(in_srgb,#b08d57_35%,transparent)]",
    amber: "bg-amber-50 text-amber-900 ring-amber-600/20",
    slate: "bg-surface-2 text-muted ring-line",
    emerald: "bg-emerald-50 text-emerald-800 ring-emerald-600/15",
  }[tone];
  const sm = size === "sm";
  return (
    <span className={cx("grid shrink-0 place-items-center rounded-[12px] text-center ring-1 ring-inset", sm ? "h-12 w-12" : "h-16 w-16", tones)}>
      <span className="leading-none">
        <span className={cx("block font-semibold uppercase opacity-70", sm ? "text-[9.5px]" : "text-[10.5px]")}>{new Intl.DateTimeFormat(intl, { weekday: "short" }).format(date)}</span>
        <span className={cx("block font-display font-semibold tnum", sm ? "my-0.5 text-[17px]" : "my-1 text-[22px]")}>{date.getDate()}</span>
        <span className={cx("block font-semibold opacity-70", sm ? "text-[9.5px]" : "text-[10.5px]")}>{new Intl.DateTimeFormat(intl, { month: "short" }).format(date)}</span>
      </span>
    </span>
  );
}

/** The three-dots trigger for a row action Menu. */
export function DotsButton({ onClick, label, open }: { onClick: () => void; label: string; open?: boolean }) {
  return (
    <button type="button" onClick={(e) => { e.stopPropagation(); e.preventDefault(); onClick(); }} aria-label={label} aria-expanded={open} title={label}
      className={cx("grid h-8 w-8 shrink-0 place-items-center rounded-[9px] text-muted transition hover:bg-[color-mix(in_srgb,var(--ink)_6%,transparent)] hover:text-ink pointer-coarse:h-11 pointer-coarse:w-11", open && "bg-[color-mix(in_srgb,var(--ink)_6%,transparent)] text-ink")}>
      <svg viewBox="0 0 24 24" className="h-4 w-4" fill="currentColor" aria-hidden><circle cx="5" cy="12" r="1.7" /><circle cx="12" cy="12" r="1.7" /><circle cx="19" cy="12" r="1.7" /></svg>
    </button>
  );
}

/** A fade/rise wrapper for panels that appear (respects reduced motion). */
export function Rise({ children, className, delay = 0 }: { children: ReactNode; className?: string; delay?: number }) {
  const reduce = useReducedMotion();
  return (
    <motion.div className={className} initial={reduce ? false : { opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.22, delay, ease: [0.2, 0.8, 0.2, 1] }}>
      {children}
    </motion.div>
  );
}

/** A labelled fact (dt/dd) for detail pages. */
export function Fact({ label, children, mono }: { label: string; children: ReactNode; mono?: boolean }) {
  return (
    <div className="min-w-0">
      <dt className="text-[11.5px] font-medium text-muted">{label}</dt>
      <dd className={cx("mt-0.5 break-words text-[13.5px] font-semibold text-ink", mono && "font-mono tnum")}>{children}</dd>
    </div>
  );
}

/** A thin paid/billed progress bar (emerald on a hairline track). */
export function PaidBar({ pct, className }: { pct: number; className?: string }) {
  const reduce = useReducedMotion();
  return (
    <div className={cx("h-1.5 overflow-hidden rounded-full bg-[color-mix(in_srgb,var(--ink)_8%,transparent)]", className)} role="progressbar" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100}>
      <motion.div className="h-full rounded-full bg-emerald-500" initial={reduce ? false : { width: 0 }} animate={{ width: `${pct}%` }} transition={{ duration: 0.6, ease: [0.2, 0.8, 0.2, 1] }} />
    </div>
  );
}

/** Colour of a case's state: won emerald, lost rose, open navy, under study amber, closed slate. */
export function caseAccent(status: string, outcome: string) {
  if (outcome === "WON") return "#10b981";
  if (outcome === "LOST") return "#e11d48";
  if (outcome === "SETTLED") return "#0ea5e9";
  if (status === "OPEN") return BRASS;
  if (status === "UNDER_STUDY") return "#f59e0b";
  return "#94a3b8";
}

/** Phone / WhatsApp quick links as small round buttons. */
export function ContactButtons({ phone, waText, size = "md" }: { phone?: string | null; waText?: string; size?: "sm" | "md" }) {
  const { t } = useI18n();
  if (!phone) return null;
  const dim = size === "sm" ? "h-8 w-8" : "h-9 w-9";
  const wa = `https://wa.me/${phone.replace(/[^0-9]/g, "")}${waText ? `?text=${encodeURIComponent(waText)}` : ""}`;
  return (
    <span className="inline-flex shrink-0 gap-1.5">
      <a href={`tel:${phone}`} aria-label={t("lw.client.call")} title={t("lw.client.call")} className={cx("grid place-items-center rounded-full bg-surface text-ink-2 ring-1 ring-line-strong transition hover:text-ink pointer-coarse:h-11 pointer-coarse:w-11", dim)}><Icon name="phone" className="h-4 w-4" /></a>
      <a href={wa} target="_blank" rel="noopener noreferrer" aria-label="WhatsApp" title="WhatsApp" className={cx("grid place-items-center rounded-full bg-emerald-600 text-white transition hover:bg-emerald-700 pointer-coarse:h-11 pointer-coarse:w-11", dim)}><Icon name="chat" className="h-4 w-4" /></a>
    </span>
  );
}

/** Skeleton rows for a card list. */
export function ListSkeleton({ rows = 5, className }: { rows?: number; className?: string }) {
  return (
    <div className={cx("overflow-hidden rounded-[18px] bg-surface shadow-card", className)} aria-busy="true">
      {Array.from({ length: rows }).map((_, i) => (
        <div key={i} className="flex items-center gap-3 border-b border-line px-4 py-3.5 last:border-0" style={{ opacity: 1 - i * 0.13 }}>
          <span className="skeleton h-12 w-12 !rounded-[12px]" /><span className="flex-1 space-y-2"><span className="skeleton block h-3 w-2/5" /><span className="skeleton block h-2.5 w-1/4" /></span><span className="skeleton h-5 w-16 !rounded-full" />
        </div>
      ))}
    </div>
  );
}
