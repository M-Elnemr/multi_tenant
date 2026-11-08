"use client";

import { motion, useReducedMotion } from "motion/react";
import type { ReactNode } from "react";
import { useT } from "../i18n-provider";
import { Icon, type IconName } from "../icons";
import { cx } from "../ui";

/**
 * Small building blocks for the clinic dashboard only (calm deep teal + sage, soft paper cards, roomy spacing; see components/dashboard/DESIGN.md).
 * Everything else comes from components/ui.tsx and components/overlay.tsx.
 */

/** Calm status colours for a doctor's day: no harsh reds; a missed visit is a soft rose, a cancelled one is slate. */
const SAGE_BADGE = "bg-[color-mix(in_srgb,var(--rail-accent)_11%,transparent)] text-[var(--rail-accent-ink)] ring-[color-mix(in_srgb,var(--rail-accent)_22%,transparent)]";
export const CALM: Record<string, { dot: string; badge: string }> = {
  REQUESTED: { dot: "#c9963f", badge: "bg-amber-50 text-amber-800 ring-amber-600/20" },
  PENDING_CONFIRMATION: { dot: "#c9963f", badge: "bg-amber-50 text-amber-800 ring-amber-600/20" },
  CONFIRMED: { dot: "var(--rail-accent)", badge: SAGE_BADGE },
  CHECKED_IN: { dot: "#4f8fd6", badge: "bg-sky-50 text-sky-700 ring-sky-600/15" },
  IN_PROGRESS: { dot: "var(--brand)", badge: "bg-brand-soft text-brand ring-brand/20" },
  COMPLETED: { dot: "#3fa37a", badge: "bg-emerald-50 text-emerald-700 ring-emerald-600/15" },
  DONE: { dot: "#3fa37a", badge: "bg-emerald-50 text-emerald-700 ring-emerald-600/15" },
  PAID: { dot: "#3fa37a", badge: "bg-emerald-50 text-emerald-700 ring-emerald-600/15" },
  ISSUED: { dot: "#3fa37a", badge: "bg-emerald-50 text-emerald-700 ring-emerald-600/15" },
  REVIEWED: { dot: "#3fa37a", badge: "bg-emerald-50 text-emerald-700 ring-emerald-600/15" },
  PATIENT_UPLOADED: { dot: "#4f8fd6", badge: "bg-sky-50 text-sky-700 ring-sky-600/15" },
  NO_SHOW: { dot: "#d9776b", badge: "bg-rose-50 text-rose-700 ring-rose-600/15" },
  CANCELLED: { dot: "#a3a8b1", badge: "bg-slate-100 text-slate-600 ring-slate-500/10" },
  REJECTED: { dot: "#a3a8b1", badge: "bg-slate-100 text-slate-600 ring-slate-500/10" },
  DRAFT: { dot: "#a3a8b1", badge: "bg-slate-100 text-slate-600 ring-slate-500/10" },
  ORDERED: { dot: "#a3a8b1", badge: "bg-slate-100 text-slate-600 ring-slate-500/10" },
};
const FALLBACK = { dot: "#a3a8b1", badge: "bg-slate-100 text-slate-600 ring-slate-500/10" };
export const calm = (status: string) => CALM[status] ?? FALLBACK;

/** Status pill in the calm clinic palette; the label comes from `status.<CODE>`. */
export function CalmStatus({ status, className }: { status: string; className?: string }) {
  const t = useT();
  const label = t(`status.${status}`);
  return (
    <span className={cx("inline-flex items-center gap-1.5 whitespace-nowrap rounded-full px-2 py-0.5 text-[11.5px] font-semibold ring-1 ring-inset", calm(status).badge, className)}>
      <span className="h-1.5 w-1.5 rounded-full" style={{ background: calm(status).dot }} />
      {label === `status.${status}` ? status : label}
    </span>
  );
}

/** A soft pill for small facts (calm, sage, amber, sky, rose, slate). */
export function Pill({ tone = "slate", children, className }: { tone?: "sage" | "amber" | "sky" | "rose" | "slate" | "brand"; children: ReactNode; className?: string }) {
  const tones = {
    sage: SAGE_BADGE,
    amber: "bg-amber-50 text-amber-800 ring-amber-600/20",
    sky: "bg-sky-50 text-sky-700 ring-sky-600/15",
    rose: "bg-rose-50 text-rose-700 ring-rose-600/15",
    slate: "bg-slate-100 text-slate-600 ring-slate-500/10",
    brand: "bg-brand-soft text-brand ring-brand/20",
  };
  return <span className={cx("inline-flex items-center gap-1 whitespace-nowrap rounded-full px-2 py-0.5 text-[11.5px] font-semibold ring-1 ring-inset tnum", tones[tone], className)}>{children}</span>;
}

/** Icon on a soft sage tile, the clinic's accent. */
export function SageTile({ icon, className }: { icon: IconName; className?: string }) {
  return (
    <span className={cx("grid shrink-0 place-items-center rounded-[11px] bg-[color-mix(in_srgb,var(--rail-accent)_12%,transparent)] text-[var(--rail-accent-ink)]", className ?? "h-9 w-9")}>
      <Icon name={icon} className="h-[18px] w-[18px]" />
    </span>
  );
}

/** Soft paper card (18px radius, hairline shadow). */
export function Paper({ className, children, id }: { className?: string; children: ReactNode; id?: string }) {
  return <section id={id} className={cx("scroll-mt-24 rounded-[18px] bg-surface shadow-card", className)}>{children}</section>;
}

/** The title row of a Paper: sage icon, display title, a one-line hint and an action on the end side. */
export function PaperHead({ icon, title, subtitle, action, className }: { icon?: IconName; title: ReactNode; subtitle?: ReactNode; action?: ReactNode; className?: string }) {
  return (
    <div className={cx("flex flex-wrap items-center justify-between gap-3 px-5 pb-3 pt-5", className)}>
      <div className="flex min-w-0 items-center gap-3">
        {icon && <SageTile icon={icon} />}
        <div className="min-w-0">
          <h2 className="truncate font-display text-[15.5px] font-semibold text-ink">{title}</h2>
          {subtitle && <p className="mt-0.5 text-xs text-muted">{subtitle}</p>}
        </div>
      </div>
      {action && <div className="flex shrink-0 flex-wrap items-center gap-2">{action}</div>}
    </div>
  );
}

/** Label / value list for a side column of facts. */
export function Facts({ rows }: { rows: { label: string; value: ReactNode; ltr?: boolean }[] }) {
  return (
    <dl className="divide-y divide-line">
      {rows.map((r) => (
        <div key={r.label} className="flex items-start justify-between gap-4 py-2.5 text-[13.5px]">
          <dt className="shrink-0 text-muted">{r.label}</dt>
          <dd className="min-w-0 break-words text-end font-medium text-ink" dir={r.ltr ? "ltr" : undefined}>{r.value}</dd>
        </div>
      ))}
    </dl>
  );
}

/** Fades and rises in once (skipped with reduced motion). */
export function Rise({ children, className, delay = 0 }: { children: ReactNode; className?: string; delay?: number }) {
  const reduce = useReducedMotion();
  return (
    <motion.div className={className} initial={reduce ? false : { opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.24, delay, ease: [0.2, 0.8, 0.2, 1] }}>
      {children}
    </motion.div>
  );
}

/** Class names for links that look like buttons (Link / <a>). */
export const linkBtn = {
  primary: "inline-flex h-10 shrink-0 items-center justify-center gap-2 rounded-[10px] bg-brand px-4 text-sm font-semibold text-white transition hover:bg-brand-dark active:scale-[.97] pointer-coarse:min-h-11",
  secondary: "inline-flex h-10 shrink-0 items-center justify-center gap-2 rounded-[10px] border border-line-strong bg-surface px-4 text-sm font-semibold text-ink transition hover:bg-surface-2 active:scale-[.97] pointer-coarse:min-h-11",
  ghost: "inline-flex h-8 shrink-0 items-center justify-center gap-1.5 rounded-[9px] px-2.5 text-[13px] font-semibold text-ink-2 transition hover:bg-[color-mix(in_srgb,var(--ink)_6%,transparent)] hover:text-ink pointer-coarse:min-h-11",
};

/** "More actions" icon trigger for a Menu. */
export function MoreTrigger({ toggle, label }: { toggle: () => void; label: string }) {
  return (
    <button type="button" onClick={toggle} aria-label={label} title={label} className="grid h-8 w-8 shrink-0 place-items-center rounded-[9px] text-muted transition hover:bg-[color-mix(in_srgb,var(--ink)_6%,transparent)] hover:text-ink pointer-coarse:h-11 pointer-coarse:w-11">
      <Icon name="menu" className="h-4 w-4" />
    </button>
  );
}
