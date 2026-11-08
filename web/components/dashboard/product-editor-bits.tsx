"use client";

import Link from "next/link";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import type { ReactNode } from "react";
import { money, num } from "@/lib/format";
import { useI18n } from "../i18n-provider";
import { Icon, type IconName } from "../icons";
import { Spinner } from "../ui";

/** Building blocks of the product editor (new + edit): titled sections, side cards, a price / stock summary and the sticky save bar. */

export function EditorSection({ title, subtitle, icon, action, children, id, field }: { title: string; subtitle?: ReactNode; icon?: IconName; action?: ReactNode; children: ReactNode; id?: string; field?: string }) {
  return (
    <section id={id} data-field={field} className="scroll-mt-24 overflow-hidden rounded-[18px] bg-surface shadow-card">
      <header className="flex flex-wrap items-start justify-between gap-3 px-5 pb-1 pt-5">
        <div className="flex min-w-0 items-start gap-3">
          {icon && <span className="grid h-9 w-9 shrink-0 place-items-center rounded-[11px] bg-surface-2 text-ink-2 ring-1 ring-line"><Icon name={icon} className="h-[18px] w-[18px]" /></span>}
          <div className="min-w-0">
            <h2 className="font-display text-[15px] font-semibold text-ink">{title}</h2>
            {subtitle && <p className="mt-0.5 text-[12.5px] leading-relaxed text-muted">{subtitle}</p>}
          </div>
        </div>
        {action}
      </header>
      <div className="space-y-4 p-5">{children}</div>
    </section>
  );
}

export function SideCard({ title, children, action }: { title: string; children: ReactNode; action?: ReactNode }) {
  return (
    <section className="rounded-[18px] bg-surface p-4 shadow-card">
      <div className="mb-3 flex items-center justify-between gap-2">
        <h3 className="text-[12px] font-semibold uppercase tracking-wide text-faint">{title}</h3>
        {action}
      </div>
      {children}
    </section>
  );
}

/** Status picker as two/three big tappable rows (ACTIVE shows in the shop, DRAFT is hidden, ARCHIVED is removed from lists). */
export function StatusPicker({ value, onChange, options }: { value: string; onChange: (v: string) => void; options: string[] }) {
  const { t } = useI18n();
  const reduce = useReducedMotion();
  const dot: Record<string, string> = { ACTIVE: "bg-emerald-500", DRAFT: "bg-slate-400", ARCHIVED: "bg-red-500" };
  return (
    <div role="radiogroup" aria-label={t("admin.status")} className="space-y-1.5">
      {options.map((s) => {
        const on = s === value;
        return (
          <button key={s} type="button" role="radio" aria-checked={on} onClick={() => onChange(s)}
            className={`relative flex w-full items-start gap-3 rounded-[12px] px-3 py-2.5 text-start ring-1 transition ${on ? "ring-brand/40" : "ring-line hover:ring-line-strong"}`}>
            {on && <motion.span layoutId="prod-status" transition={reduce ? { duration: 0 } : { type: "spring", stiffness: 500, damping: 40 }} className="absolute inset-0 rounded-[12px] bg-brand-soft" />}
            <span className={`relative mt-1.5 h-2 w-2 shrink-0 rounded-full ${dot[s] ?? "bg-slate-400"}`} />
            <span className="relative min-w-0">
              <span className="block text-[13px] font-semibold text-ink">{t(`status.${s}`)}</span>
              <span className="block text-[12px] leading-snug text-muted">{t(`cat.statusHint.${s}`)}</span>
            </span>
          </button>
        );
      })}
    </div>
  );
}

/** Price range, total pieces and number of variants, from what is typed in the variants table. */
export function PriceSummary({ prices, pieces, variants, currency }: { prices: number[]; pieces: number; variants: number; currency: string }) {
  const { t, locale } = useI18n();
  const valid = prices.filter((p) => isFinite(p) && p >= 0);
  const lo = valid.length ? Math.min(...valid) : null, hi = valid.length ? Math.max(...valid) : null;
  const priceText = lo === null ? "—" : lo === hi ? money(lo, currency, locale) : `${money(lo, currency, locale)} – ${money(hi!, currency, locale)}`;
  return (
    <dl className="space-y-3">
      <div>
        <dt className="text-[12px] font-medium text-muted">{t("products.price")}</dt>
        <dd className="mt-0.5 font-display text-[20px] font-semibold leading-tight text-ink tnum">{priceText}</dd>
      </div>
      <div className="grid grid-cols-2 gap-2">
        <div className="rounded-[12px] bg-surface-2 px-3 py-2.5">
          <dt className="text-[11.5px] font-medium text-muted">{t("products.stock")}</dt>
          <dd className={`mt-0.5 text-[17px] font-semibold tnum ${pieces <= 0 ? "text-red-600" : "text-ink"}`}>{num(pieces, locale)}</dd>
        </div>
        <div className="rounded-[12px] bg-surface-2 px-3 py-2.5">
          <dt className="text-[11.5px] font-medium text-muted">{t("products.variants")}</dt>
          <dd className="mt-0.5 text-[17px] font-semibold text-ink tnum">{num(variants, locale)}</dd>
        </div>
      </div>
    </dl>
  );
}

/** The sticky bar at the bottom of the form: a short state line and the actions. */
export function SaveBar({ dirty, error, saving, children }: { dirty?: boolean; error?: boolean; saving?: boolean; children: ReactNode }) {
  const { t } = useI18n();
  const reduce = useReducedMotion();
  return (
    <div className="sticky bottom-[calc(var(--tabbar-h,0px)+0.75rem)] z-20 lg:bottom-4">
      <motion.div initial={reduce ? false : { opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.24 }}
        className="flex flex-wrap items-center gap-2 rounded-[16px] bg-surface/95 p-2 ps-4 shadow-[var(--shadow-float)] ring-1 ring-line backdrop-blur">
        <span className="me-auto flex min-w-0 items-center gap-2 text-[12.5px] font-medium text-muted">
          <AnimatePresence mode="wait" initial={false}>
            <motion.span key={saving ? "s" : error ? "e" : dirty ? "d" : "c"} initial={reduce ? false : { opacity: 0, y: 4 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.16 }} className="flex items-center gap-2">
              {saving ? <><Spinner className="h-3.5 w-3.5" />{t("cat.saving")}</>
                : error ? <><span className="h-2 w-2 rounded-full bg-red-500" /><span className="text-red-600">{t("form.fixMarked")}</span></>
                  : dirty ? <><span className="h-2 w-2 rounded-full bg-amber-500" />{t("cat.unsaved")}</>
                    : <><Icon name="check" className="h-3.5 w-3.5 text-emerald-600" />{t("cat.allSaved")}</>}
            </motion.span>
          </AnimatePresence>
        </span>
        {children}
      </motion.div>
    </div>
  );
}

export function BackLink({ href, label }: { href: string; label: string }) {
  return (
    <Link href={href} className="inline-flex items-center gap-1 text-[12.5px] font-semibold text-muted transition hover:text-ink">
      <Icon name="chevron" className="h-3.5 w-3.5 rotate-180 flip-rtl" />{label}
    </Link>
  );
}
