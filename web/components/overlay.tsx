"use client";

import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { createPortal } from "react-dom";
import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState, useSyncExternalStore } from "react";
import { Icon } from "./icons";

/**
 * Floating layers for the dashboard (no extra library): Popover (anchored panel), Menu (a popover of actions), Sheet (side drawer on desktop, bottom sheet on phones),
 * Tooltip, ConfirmDialog, plus Switch and Checkbox. Everything closes on Escape and outside click, flips for RTL, traps nothing it should not, and respects reduced motion.
 */

const E = [0.2, 0.8, 0.2, 1] as const;
const noop = () => () => {};
const useMounted = () => useSyncExternalStore(noop, () => true, () => false);

function useEscape(open: boolean, onClose: () => void) {
  useEffect(() => {
    if (!open) return;
    const h = (e: KeyboardEvent) => { if (e.key === "Escape") { e.stopPropagation(); onClose(); } };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [open, onClose]);
}

type Place = "bottom-start" | "bottom-end" | "top-start" | "top-end" | "right" | "left";

/** Where to draw a panel of size w×h next to the anchor rectangle, kept on screen; "start"/"end" follow the page direction. */
function place(r: DOMRect, w: number, h: number, where: Place, rtl: boolean, gap = 8) {
  const vw = window.innerWidth, vh = window.innerHeight;
  let top: number, left: number;
  if (where === "right" || where === "left") {
    const toRight = (where === "right") !== rtl;
    left = toRight ? r.right + gap : r.left - gap - w;
    if (left + w > vw - 8) left = r.left - gap - w;
    if (left < 8) left = r.right + gap;
    top = Math.min(Math.max(8, r.top + r.height / 2 - h / 2), vh - h - 8);
    return { top, left };
  }
  const atStart = where.endsWith("start") !== rtl;
  left = atStart ? r.left : r.right - w;
  top = where.startsWith("bottom") ? r.bottom + gap : r.top - gap - h;
  if (top + h > vh - 8 && where.startsWith("bottom")) top = Math.max(8, r.top - gap - h);
  if (top < 8) top = r.bottom + gap;
  left = Math.min(Math.max(8, left), vw - w - 8);
  return { top, left };
}

export function Popover({ open, onClose, anchor, children, placement = "bottom-start", className = "", width, label }: {
  open: boolean; onClose: () => void; anchor: React.RefObject<HTMLElement | null>; children: React.ReactNode; placement?: Place; className?: string; width?: number; label?: string;
}) {
  const mounted = useMounted();
  const reduce = useReducedMotion();
  const panel = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState<{ top: number; left: number } | null>(null);
  useEscape(open, onClose);
  useLayoutEffect(() => {
    if (!open) return;
    const measure = () => {
      const a = anchor.current, p = panel.current;
      if (!a || !p) return;
      const rtl = getComputedStyle(document.documentElement).direction === "rtl";
      setPos(place(a.getBoundingClientRect(), p.offsetWidth, p.offsetHeight, placement, rtl));
    };
    measure();
    window.addEventListener("resize", measure);
    window.addEventListener("scroll", measure, true);
    return () => { window.removeEventListener("resize", measure); window.removeEventListener("scroll", measure, true); };
  }, [open, anchor, placement]);
  useEffect(() => {
    if (!open) return;
    const h = (e: PointerEvent) => {
      const t = e.target as Node;
      if (panel.current?.contains(t) || anchor.current?.contains(t)) return;
      onClose();
    };
    document.addEventListener("pointerdown", h);
    return () => document.removeEventListener("pointerdown", h);
  }, [open, onClose, anchor]);
  if (!mounted) return null;
  return createPortal(
    <AnimatePresence>
      {open && (
        <motion.div ref={panel} role="dialog" aria-label={label} initial={reduce ? false : { opacity: 0, scale: 0.97, y: -4 }} animate={{ opacity: 1, scale: 1, y: 0 }} exit={{ opacity: 0, scale: 0.98 }}
          transition={{ duration: 0.16, ease: E }} style={{ position: "fixed", top: pos?.top ?? -9999, left: pos?.left ?? -9999, width, zIndex: 70 }}
          className={`origin-top rounded-[14px] bg-surface p-1.5 text-ink shadow-[var(--shadow-float)] ring-1 ring-line ${className}`}>
          {children}
        </motion.div>
      )}
    </AnimatePresence>,
    document.body,
  );
}

export type MenuItem = { label: string; icon?: string; onSelect: () => void; danger?: boolean; disabled?: boolean; hint?: string; checked?: boolean } | "divider";

/** A button that opens a list of actions. */
export function Menu({ trigger, items, placement = "bottom-end", label, className = "" }: { trigger: (p: { open: boolean; toggle: () => void }) => React.ReactNode; items: MenuItem[]; placement?: Place; label?: string; className?: string }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLSpanElement>(null);
  const close = useCallback(() => setOpen(false), []);
  return (
    <>
      <span ref={ref} className={`inline-flex ${className}`}>{trigger({ open, toggle: () => setOpen((v) => !v) })}</span>
      <Popover open={open} onClose={close} anchor={ref} placement={placement} label={label} className="min-w-[13rem]">
        <div role="menu" className="flex flex-col">
          {items.map((it, i) => it === "divider"
            ? <span key={`d${i}`} className="my-1 h-px bg-line" />
            : (
              <button key={it.label} role="menuitem" type="button" disabled={it.disabled} onClick={() => { setOpen(false); it.onSelect(); }}
                className={`flex items-center gap-2.5 rounded-[9px] px-2.5 py-2 text-start text-[13px] font-medium transition disabled:opacity-40 ${it.danger ? "text-red-600 hover:bg-red-50" : "text-ink-2 hover:bg-[color-mix(in_srgb,var(--ink)_6%,transparent)] hover:text-ink"}`}>
                {it.icon && <Icon name={it.icon} className="h-4 w-4 shrink-0 opacity-80" />}
                <span className="min-w-0 flex-1 truncate">{it.label}</span>
                {it.checked && <Icon name="check" className="h-4 w-4 text-brand" />}
                {it.hint && <span className="kbd">{it.hint}</span>}
              </button>
            ))}
        </div>
      </Popover>
    </>
  );
}

/** A panel that slides in from the end side (desktop) or up from the bottom (phones, under 640px). */
export function Sheet({ open, onClose, title, children, footer, side = "end", width = "26rem", label }: {
  open: boolean; onClose: () => void; title?: React.ReactNode; children: React.ReactNode; footer?: React.ReactNode; side?: "end" | "start"; width?: string; label?: string;
}) {
  const mounted = useMounted();
  const reduce = useReducedMotion();
  const id = useId();
  useEscape(open, onClose);
  const phone = useSyncExternalStore((cb) => { const m = window.matchMedia("(max-width: 639px)"); m.addEventListener("change", cb); return () => m.removeEventListener("change", cb); },
    () => window.matchMedia("(max-width: 639px)").matches, () => false);
  const rtl = useSyncExternalStore(noop, () => getComputedStyle(document.documentElement).direction === "rtl", () => false);
  useEffect(() => {
    if (!open) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => { document.body.style.overflow = prev; };
  }, [open]);
  if (!mounted) return null;
  const fromRight = (side === "end") !== rtl;
  const hidden = phone ? { y: "100%" } : { x: fromRight ? "100%" : "-100%" };
  return createPortal(
    <AnimatePresence>
      {open && (
        <div className="fixed inset-0 z-[65]" role="dialog" aria-modal="true" aria-labelledby={title ? id : undefined} aria-label={title ? undefined : label}>
          <motion.div className="absolute inset-0 bg-[rgb(10_12_16/.45)] backdrop-blur-[2px]" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onClick={onClose} />
          <motion.div initial={reduce ? { opacity: 0 } : hidden} animate={reduce ? { opacity: 1 } : { x: 0, y: 0 }} exit={reduce ? { opacity: 0 } : hidden} transition={{ duration: reduce ? 0.12 : 0.32, ease: E }}
            style={phone ? undefined : { width }}
            className={`absolute flex flex-col bg-surface text-ink shadow-[var(--shadow-float)] ${phone ? "inset-x-0 bottom-0 max-h-[88dvh] rounded-t-[22px]" : `inset-y-0 ${fromRight ? "right-0" : "left-0"} max-w-[100vw]`}`}>
            {phone && <span className="mx-auto mt-2.5 h-1.5 w-10 shrink-0 rounded-full bg-line-strong" aria-hidden />}
            {title && (
              <div className="flex items-center justify-between gap-3 px-5 pb-3 pt-4 hairline-b">
                <h2 id={id} className="font-display text-[17px] font-semibold">{title}</h2>
                <button type="button" onClick={onClose} aria-label="Close" className="grid h-9 w-9 place-items-center rounded-[10px] text-muted transition hover:bg-[color-mix(in_srgb,var(--ink)_6%,transparent)] hover:text-ink"><Icon name="close" className="h-5 w-5" /></button>
              </div>
            )}
            <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-5 py-4">{children}</div>
            {footer && <div className="border-t border-line px-5 py-3 pb-[max(0.75rem,env(safe-area-inset-bottom))]">{footer}</div>}
          </motion.div>
        </div>
      )}
    </AnimatePresence>,
    document.body,
  );
}

/** A small label next to an element on hover / focus (for icon-only buttons). */
export function Tooltip({ label, children, placement = "right", disabled }: { label: React.ReactNode; children: React.ReactElement; placement?: Place; disabled?: boolean }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLSpanElement>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const show = () => { if (disabled) return; timer.current = setTimeout(() => setOpen(true), 250); };
  const hide = () => { if (timer.current) clearTimeout(timer.current); setOpen(false); };
  const mounted = useMounted();
  const [pos, setPos] = useState<{ top: number; left: number } | null>(null);
  const tip = useRef<HTMLSpanElement>(null);
  useLayoutEffect(() => {
    if (!open || !ref.current || !tip.current) return;
    const rtl = getComputedStyle(document.documentElement).direction === "rtl";
    setPos(place(ref.current.getBoundingClientRect(), tip.current.offsetWidth, tip.current.offsetHeight, placement, rtl, 10));
  }, [open, placement]);
  return (
    <span ref={ref} className="inline-flex" onMouseEnter={show} onMouseLeave={hide} onFocus={show} onBlur={hide}>
      {children}
      {mounted && open && createPortal(
        <span ref={tip} role="tooltip" style={{ position: "fixed", top: pos?.top ?? -9999, left: pos?.left ?? -9999, zIndex: 80 }}
          className="pointer-events-none animate-fade-in whitespace-nowrap rounded-[8px] bg-[#0d1117] px-2.5 py-1.5 text-[12px] font-medium text-white shadow-lg">{label}</span>,
        document.body)}
    </span>
  );
}

/** "Are you sure?" with one primary action. */
export function ConfirmDialog({ open, onClose, onConfirm, title, body, confirmLabel, cancelLabel, danger, loading }: {
  open: boolean; onClose: () => void; onConfirm: () => void; title: string; body?: React.ReactNode; confirmLabel: string; cancelLabel: string; danger?: boolean; loading?: boolean;
}) {
  const mounted = useMounted();
  const reduce = useReducedMotion();
  useEscape(open, onClose);
  if (!mounted) return null;
  return createPortal(
    <AnimatePresence>
      {open && (
        <div className="fixed inset-0 z-[75] grid place-items-center p-4" role="alertdialog" aria-modal="true" aria-label={title}>
          <motion.div className="absolute inset-0 bg-[rgb(10_12_16/.45)]" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onClick={onClose} />
          <motion.div initial={reduce ? false : { opacity: 0, scale: 0.96, y: 8 }} animate={{ opacity: 1, scale: 1, y: 0 }} exit={{ opacity: 0, scale: 0.97 }} transition={{ duration: 0.2, ease: E }}
            className="relative w-full max-w-sm rounded-[18px] bg-surface p-6 text-ink shadow-[var(--shadow-float)]">
            <span className={`mb-4 grid h-11 w-11 place-items-center rounded-[13px] ${danger ? "bg-red-50 text-red-600" : "bg-brand-soft text-brand"}`}><Icon name={danger ? "alert" : "check"} className="h-5 w-5" /></span>
            <h2 className="font-display text-[17px] font-semibold">{title}</h2>
            {body && <div className="mt-1.5 text-sm leading-relaxed text-muted">{body}</div>}
            <div className="mt-6 flex justify-end gap-2">
              <button type="button" onClick={onClose} className="h-10 rounded-[11px] px-4 text-sm font-semibold text-ink-2 ring-1 ring-line-strong transition hover:bg-[color-mix(in_srgb,var(--ink)_5%,transparent)]">{cancelLabel}</button>
              <button type="button" onClick={onConfirm} disabled={loading} className={`h-10 rounded-[11px] px-4 text-sm font-semibold text-surface transition active:scale-[.97] disabled:opacity-60 ${danger ? "bg-red-600 hover:bg-red-700" : "bg-ink hover:bg-ink-2"}`}>{confirmLabel}</button>
            </div>
          </motion.div>
        </div>
      )}
    </AnimatePresence>,
    document.body,
  );
}

export function Switch({ checked, onChange, label, disabled, size = "md" }: { checked: boolean; onChange: (v: boolean) => void; label?: string; disabled?: boolean; size?: "sm" | "md" }) {
  const w = size === "sm" ? "w-8 h-[18px]" : "w-10 h-6", k = size === "sm" ? "h-3.5 w-3.5" : "h-5 w-5", move = size === "sm" ? "ltr:translate-x-[14px] rtl:-translate-x-[14px]" : "ltr:translate-x-4 rtl:-translate-x-4";
  return (
    <button type="button" role="switch" aria-checked={checked} aria-label={label} disabled={disabled} onClick={() => onChange(!checked)}
      className={`relative inline-flex shrink-0 items-center rounded-full p-0.5 transition-colors duration-200 disabled:opacity-50 ${w} ${checked ? "bg-brand" : "bg-line-strong"}`}>
      <span className={`${k} rounded-full bg-white shadow-sm transition-transform duration-200 ${checked ? move : ""}`} />
    </button>
  );
}

export function Checkbox({ checked, onChange, label, indeterminate }: { checked: boolean; onChange: (v: boolean) => void; label?: string; indeterminate?: boolean }) {
  return (
    <button type="button" role="checkbox" aria-checked={indeterminate ? "mixed" : checked} aria-label={label} onClick={(e) => { e.stopPropagation(); onChange(!checked); }}
      className={`grid h-[18px] w-[18px] shrink-0 place-items-center rounded-[5px] border transition ${checked || indeterminate ? "border-brand bg-brand text-white" : "border-line-strong bg-surface hover:border-faint"}`}>
      {indeterminate ? <span className="h-0.5 w-2 rounded bg-white" /> : checked ? <Icon name="check" className="h-3 w-3" /> : null}
    </button>
  );
}
