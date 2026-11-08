"use client";

import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import type { ReactNode } from "react";
import { Icon, type IconName } from "@/components/icons";
import { cx } from "@/components/ui";
import { useI18n } from "@/components/i18n-provider";

/**
 * Small layout pieces shared by the settings-style screens (home, AI, settings, billing, staff, domains, notifications, developers).
 * They only compose the tokens from DESIGN.md; the real building blocks stay in components/ui.tsx and components/overlay.tsx.
 */

/** The violet→cyan AI gradient, used only on AI things. */
export const AI_GRADIENT = "linear-gradient(135deg,#7c5cff,#22d3ee)";

/** A titled card for one group of settings: icon, title, one-line description, optional action on the end side. */
export function SettingsCard({ icon, title, description, action, children, className, id, tone = "plain", flush }: {
  icon?: IconName; title: ReactNode; description?: ReactNode; action?: ReactNode; children?: ReactNode; className?: string; id?: string; tone?: "plain" | "ai" | "warn"; flush?: boolean;
}) {
  return (
    <section id={id} className={cx("scroll-mt-24 overflow-hidden rounded-[18px] ring-1",
      tone === "ai" ? "bg-[linear-gradient(135deg,rgb(124_92_255/.07),rgb(34_211_238/.06))] ring-violet-500/20" : tone === "warn" ? "bg-amber-50 ring-amber-600/20" : "bg-surface ring-line", className)}>
      <header className="flex flex-wrap items-start justify-between gap-3 px-5 pb-1 pt-5">
        <div className="flex min-w-0 items-start gap-3">
          {icon && (
            <span className={cx("grid h-9 w-9 shrink-0 place-items-center rounded-[11px]", tone === "ai" ? "text-white" : "bg-surface-2 text-ink-2 ring-1 ring-inset ring-line")} style={tone === "ai" ? { background: AI_GRADIENT } : undefined}>
              <Icon name={icon} className="h-[18px] w-[18px]" />
            </span>
          )}
          <div className="min-w-0">
            <h2 className="font-display text-[15px] font-semibold leading-snug text-ink">{title}</h2>
            {description && <p className="mt-0.5 text-[13px] leading-relaxed text-muted">{description}</p>}
          </div>
        </div>
        {action && <div className="flex shrink-0 flex-wrap items-center gap-2">{action}</div>}
      </header>
      {children !== undefined && <div className={flush ? "mt-4" : "px-5 pb-5 pt-4"}>{children}</div>}
    </section>
  );
}

/** A row with a label + hint on the start side and a control (switch, select) on the end side. */
export function ToggleRow({ title, hint, control, icon }: { title: ReactNode; hint?: ReactNode; control: ReactNode; icon?: IconName }) {
  return (
    <div className="flex items-start justify-between gap-4 py-3 first:pt-0 last:pb-0 [&:not(:first-child)]:border-t [&:not(:first-child)]:border-line">
      <div className="flex min-w-0 items-start gap-2.5">
        {icon && <Icon name={icon} className="mt-0.5 h-4 w-4 shrink-0 text-muted" />}
        <div className="min-w-0">
          <p className="text-[13.5px] font-semibold text-ink">{title}</p>
          {hint && <p className="mt-0.5 text-[12.5px] leading-relaxed text-muted">{hint}</p>}
        </div>
      </div>
      <div className="shrink-0 pt-0.5">{control}</div>
    </div>
  );
}

/** Sticky bar at the bottom of a settings form: shows when there are unsaved changes, with discard and save. */
export function SaveBar({ dirty, saving, onSave, onDiscard, label }: { dirty: boolean; saving?: boolean; onSave?: () => void; onDiscard?: () => void; label?: string }) {
  const { t } = useI18n();
  const reduce = useReducedMotion();
  return (
    <AnimatePresence>
      {dirty && (
        <motion.div initial={reduce ? { opacity: 0 } : { opacity: 0, y: 16 }} animate={{ opacity: 1, y: 0 }} exit={reduce ? { opacity: 0 } : { opacity: 0, y: 12 }} transition={{ duration: 0.2, ease: [0.2, 0.8, 0.2, 1] }}
          className="sticky bottom-[calc(var(--tabbar-h,0px)+0.75rem)] z-30 mt-5 lg:bottom-4">
          <div className="flex flex-wrap items-center justify-between gap-3 rounded-[16px] bg-ink px-4 py-3 text-surface shadow-[var(--shadow-float)]">
            <span className="flex min-w-0 items-center gap-2 text-[13px] font-medium"><span className="h-2 w-2 shrink-0 rounded-full bg-amber-400" />{label ?? t("kit.unsaved")}</span>
            <div className="flex gap-2">
              {onDiscard && <button type="button" onClick={onDiscard} className="h-9 rounded-[10px] px-3 text-[13px] font-semibold opacity-75 transition hover:opacity-100">{t("kit.discard")}</button>}
              <button type={onSave ? "button" : "submit"} onClick={onSave} disabled={saving} className="inline-flex h-9 items-center gap-2 rounded-[10px] bg-surface px-4 text-[13px] font-semibold text-ink transition active:scale-[.97] disabled:opacity-60">
                {saving && <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-current border-t-transparent" />}{t("common.save")}
              </button>
            </div>
          </div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}

/** Choice chip styled as a selectable tile (channels, amounts…). */
export function ChoiceTile({ on, onClick, children, disabled, className }: { on: boolean; onClick: () => void; children: ReactNode; disabled?: boolean; className?: string }) {
  return (
    <button type="button" aria-pressed={on} disabled={disabled} onClick={onClick}
      className={cx("flex items-center gap-2.5 rounded-[12px] px-3 py-2.5 text-start text-[13px] font-semibold ring-1 transition disabled:cursor-not-allowed disabled:opacity-60",
        on ? "bg-brand-soft text-ink ring-brand/40" : "bg-surface text-ink-2 ring-line hover:ring-line-strong", className)}>
      <span className={cx("grid h-[18px] w-[18px] shrink-0 place-items-center rounded-[5px] border transition", on ? "border-brand bg-brand text-white" : "border-line-strong bg-surface")}>{on && <Icon name="check" className="h-3 w-3" />}</span>
      <span className="min-w-0 flex-1">{children}</span>
    </button>
  );
}

/** A pale link-button used for secondary actions in page headers. */
export function HeaderLink({ href, icon, children, external }: { href: string; icon?: IconName; children: ReactNode; external?: boolean }) {
  return (
    <a href={href} target={external ? "_blank" : undefined} rel={external ? "noreferrer" : undefined}
      className="inline-flex h-10 items-center gap-1.5 rounded-[11px] bg-surface px-3.5 text-[13px] font-semibold text-ink-2 ring-1 ring-line-strong transition hover:text-ink hover:ring-[color-mix(in_srgb,var(--ink)_24%,transparent)]">
      {icon && <Icon name={icon} className="h-4 w-4" />}{children}
    </a>
  );
}

/** A small check / not-yet bullet for readiness lists. */
export function CheckItem({ ok, label, action }: { ok: boolean; label: ReactNode; action?: ReactNode }) {
  return (
    <li className="flex items-center gap-3 py-2.5 text-[13.5px] first:pt-0 last:pb-0">
      <span className={cx("grid h-6 w-6 shrink-0 place-items-center rounded-full", ok ? "bg-emerald-500 text-white" : "border border-dashed border-line-strong text-faint")}>
        <Icon name={ok ? "check" : "dot"} className="h-3.5 w-3.5" />
      </span>
      <span className={cx("min-w-0 flex-1", ok ? "text-ink" : "text-muted")}>{label}</span>
      {action}
    </li>
  );
}

