"use client";

import { motion, useReducedMotion } from "motion/react";
import { useState, type ReactNode } from "react";
import { ApiError } from "@/lib/client";
import { Icon, type IconName } from "@/components/icons";
import { Switch } from "@/components/overlay";
import { useI18n } from "@/components/i18n-provider";
import { cx } from "@/components/ui";

/**
 * Small building blocks shared by the marketing, growth, carts, reviews, questions and marketplace screens
 * (built on the dashboard design system: ink pills, hairline cards, AI gradient only for AI things).
 */

export const AI_GRADIENT = "bg-[linear-gradient(135deg,#7c5cff,#22d3ee)]";
export const AI_SOFT = "bg-[linear-gradient(135deg,rgb(124_92_255/.08),rgb(34_211_238/.08))]";

/** The error text of a failed call, for a toast. */
export function errText(e: unknown, fallback: string) {
  return e instanceof ApiError && e.message ? e.message : fallback;
}

/** Hairline card. */
export function Panel({ children, className, as: Tag = "div" }: { children: ReactNode; className?: string; as?: "div" | "section" | "li" | "article" }) {
  return <Tag className={cx("rounded-[18px] bg-surface ring-1 ring-line", className)}>{children}</Tag>;
}

/** Card with a title row: icon tile, title, one-line hint and an action on the end side. */
export function PanelHead({ icon, title, hint, action, ai }: { icon?: IconName; title: ReactNode; hint?: ReactNode; action?: ReactNode; ai?: boolean }) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-3">
      <div className="flex min-w-0 items-start gap-3">
        {icon && <span className={cx("grid h-9 w-9 shrink-0 place-items-center rounded-[11px]", ai ? `${AI_GRADIENT} text-white` : "bg-brand-soft text-brand")}><Icon name={icon} className="h-[18px] w-[18px]" /></span>}
        <div className="min-w-0">
          <h2 className="font-display text-[15px] font-semibold text-ink">{title}</h2>
          {hint && <p className="mt-0.5 max-w-2xl text-[13px] leading-relaxed text-muted">{hint}</p>}
        </div>
      </div>
      {action}
    </div>
  );
}

/** Ink pill tabs with a sliding indicator (the followups style). Scrolls sideways on a phone. */
export function PillTabs<T extends string>({ tabs, value, onChange, id }: { tabs: { key: T; label: string; count?: number; icon?: IconName }[]; value: T; onChange: (k: T) => void; id: string }) {
  const reduce = useReducedMotion();
  return (
    <div role="tablist" className="-mx-1 max-w-full overflow-x-auto px-1 [scrollbar-width:none]">
      <div className="flex w-fit gap-0.5 rounded-[12px] bg-surface p-1 ring-1 ring-line">
        {tabs.map((tb) => {
          const on = tb.key === value;
          return (
            <button key={tb.key} role="tab" type="button" aria-selected={on} onClick={() => onChange(tb.key)}
              className={cx("relative flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-[9px] px-3.5 py-2 text-[13px] font-semibold transition", on ? "text-surface" : "text-muted hover:text-ink")}>
              {on && <motion.span layoutId={`pill-${id}`} className="absolute inset-0 rounded-[9px] bg-ink" transition={reduce ? { duration: 0 } : { type: "spring", stiffness: 500, damping: 38 }} />}
              {tb.icon && <Icon name={tb.icon} className="relative h-4 w-4" />}
              <span className="relative">{tb.label}</span>
              {tb.count !== undefined && <span className={cx("relative rounded-full px-1.5 text-[11px] tnum", on ? "bg-white/20" : "bg-[color-mix(in_srgb,var(--ink)_7%,transparent)]")}>{tb.count}</span>}
            </button>
          );
        })}
      </div>
    </div>
  );
}

/** A selectable card (radio-like) with an icon, title and hint. */
export function ChoiceCard({ active, onClick, icon, title, hint, badge, disabled, tone = "brand" }: { active: boolean; onClick: () => void; icon: IconName; title: ReactNode; hint?: ReactNode; badge?: ReactNode; disabled?: boolean; tone?: "brand" | "green" | "slate" }) {
  const tile = tone === "green" ? "bg-emerald-50 text-emerald-600" : tone === "slate" ? "bg-slate-100 text-slate-600" : "bg-brand-soft text-brand";
  return (
    <button type="button" role="radio" aria-checked={active} disabled={disabled} onClick={onClick}
      className={cx("relative flex w-full items-start gap-3 rounded-[14px] p-3.5 text-start ring-1 transition disabled:opacity-50", active ? "bg-brand-soft/60 ring-2 ring-brand" : "bg-surface ring-line hover:ring-line-strong")}>
      <span className={cx("grid h-9 w-9 shrink-0 place-items-center rounded-[10px]", tile)}><Icon name={icon} className="h-[18px] w-[18px]" /></span>
      <span className="min-w-0 flex-1">
        <span className="flex flex-wrap items-center gap-1.5 text-[13.5px] font-semibold text-ink">{title}{badge}</span>
        {hint && <span className="mt-0.5 block text-[12px] leading-relaxed text-muted">{hint}</span>}
      </span>
      <span className={cx("mt-0.5 grid h-[18px] w-[18px] shrink-0 place-items-center rounded-full ring-2", active ? "bg-brand ring-brand" : "ring-line-strong")} aria-hidden>{active && <span className="h-1.5 w-1.5 rounded-full bg-white" />}</span>
    </button>
  );
}

/** A setting row with a switch on the end side. */
export function SwitchRow({ title, hint, checked, onChange, disabled }: { title: ReactNode; hint?: ReactNode; checked: boolean; onChange: (v: boolean) => void; disabled?: boolean }) {
  return (
    <div className="flex items-start justify-between gap-4 rounded-[14px] bg-canvas/60 p-3.5 ring-1 ring-line">
      <div className="min-w-0">
        <p className="text-[13.5px] font-semibold text-ink">{title}</p>
        {hint && <p className="mt-0.5 text-[12px] leading-relaxed text-muted">{hint}</p>}
      </div>
      <Switch checked={checked} onChange={onChange} disabled={disabled} label={typeof title === "string" ? title : undefined} />
    </div>
  );
}

/** Five stars, the first `n` filled. */
export function Stars({ n, className = "h-4 w-4" }: { n: number; className?: string }) {
  return (
    <span className="inline-flex items-center gap-0.5" aria-label={`${n}/5`}>
      {[1, 2, 3, 4, 5].map((i) => <Icon key={i} name="star" className={cx(className, i <= n ? "fill-amber-400 text-amber-400" : "text-line-strong")} />)}
    </span>
  );
}

/** A picture or a quiet placeholder tile. */
export function Thumb({ src, icon = "bag", className = "h-11 w-11" }: { src?: string | null; icon?: IconName; className?: string }) {
  // eslint-disable-next-line @next/next/no-img-element
  return src ? <img src={src} alt="" className={cx("shrink-0 rounded-[10px] object-cover ring-1 ring-line", className)} /> : <span className={cx("grid shrink-0 place-items-center rounded-[10px] bg-canvas text-faint ring-1 ring-line", className)}><Icon name={icon} className="h-5 w-5" /></span>;
}

/** A read-only value with a copy button. */
export function CopyField({ value, href }: { value: string; href?: string }) {
  const { t } = useI18n();
  const [done, setDone] = useState(false);
  const copy = async () => { try { await navigator.clipboard.writeText(value); setDone(true); setTimeout(() => setDone(false), 1600); } catch { setDone(false); } };
  return (
    <div className="flex min-w-0 items-center gap-1 rounded-[11px] bg-canvas py-1 pe-1 ps-3 ring-1 ring-line">
      {href ? <a href={href} target="_blank" rel="noreferrer" dir="ltr" className="min-w-0 flex-1 truncate text-[12.5px] font-medium text-brand hover:underline">{value}</a> : <span dir="ltr" className="min-w-0 flex-1 truncate text-[12.5px] font-medium text-ink-2">{value}</span>}
      <button type="button" onClick={() => void copy()} className="inline-flex h-8 shrink-0 items-center gap-1 rounded-[9px] px-2.5 text-[12px] font-semibold text-ink-2 transition hover:bg-surface hover:text-ink">
        <Icon name={done ? "check" : "file"} className="h-3.5 w-3.5" />{done ? t("common.copied") : t("common.copy")}
      </button>
    </div>
  );
}

/** Small "label over value" figure. */
export function Figure({ label, value, className }: { label: string; value: ReactNode; className?: string }) {
  return (
    <div className={cx("min-w-0", className)}>
      <p className="truncate font-display text-[17px] font-semibold leading-tight text-ink tnum">{value}</p>
      <p className="mt-0.5 truncate text-[11.5px] text-muted">{label}</p>
    </div>
  );
}

/** A thin progress bar. */
export function Bar({ value, max, tone = "brand" }: { value: number; max: number; tone?: "brand" | "green" | "amber" | "red" }) {
  const reduce = useReducedMotion();
  const pct = max > 0 ? Math.min(100, (value / max) * 100) : 0;
  const color = tone === "green" ? "bg-emerald-500" : tone === "amber" ? "bg-amber-500" : tone === "red" ? "bg-red-500" : "bg-brand";
  return (
    <span className="block h-1.5 w-full overflow-hidden rounded-full bg-[color-mix(in_srgb,var(--ink)_7%,transparent)]">
      <motion.span className={cx("block h-full rounded-full", color)} initial={reduce ? false : { width: 0 }} animate={{ width: `${pct}%` }} transition={{ duration: reduce ? 0 : 0.5, ease: [0.2, 0.8, 0.2, 1] }} />
    </span>
  );
}

/** Fade/rise wrapper for a section that appears (respects reduced motion). */
export function Rise({ children, className, delay = 0 }: { children: ReactNode; className?: string; delay?: number }) {
  const reduce = useReducedMotion();
  return <motion.div className={className} initial={reduce ? false : { opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.22, delay, ease: [0.2, 0.8, 0.2, 1] }}>{children}</motion.div>;
}

/** Skeleton cards in a grid. */
export function CardSkeletons({ n = 4, className = "grid gap-3 md:grid-cols-2", h = "h-44" }: { n?: number; className?: string; h?: string }) {
  return <div className={className} aria-busy="true">{Array.from({ length: n }).map((_, i) => <div key={i} className={cx("skeleton rounded-[18px]", h)} style={{ opacity: 1 - i * 0.12 }} />)}</div>;
}
