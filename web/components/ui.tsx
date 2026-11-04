"use client";

import { useEffect, useId, useRef, useState, type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from "react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { ApiError } from "@/lib/client";
import { errorMessage, fieldLines } from "@/lib/error-text";
import { ease, spring } from "@/lib/motion";
import { num } from "@/lib/format";
import { Icon, type IconName } from "./icons";
import { useI18n, useT } from "./i18n-provider";

export const cx = (...c: (string | false | null | undefined)[]) => c.filter(Boolean).join(" ");

/* ───────────── Buttons ───────────── */

export function Button({ variant = "primary", size = "md", loading, className, children, disabled, ...rest }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: "primary" | "secondary" | "danger" | "ghost" | "ink"; size?: "sm" | "md" | "lg"; loading?: boolean }) {
  const styles = {
    primary: "bg-brand text-white shadow-[inset_0_1px_0_rgb(255_255_255/.18),0_1px_2px_rgb(16_18_24/.18),0_6px_16px_-8px_var(--brand)] hover:bg-brand-dark",
    ink: "bg-ink text-surface shadow-[inset_0_1px_0_rgb(255_255_255/.12),0_1px_2px_rgb(16_18_24/.2)] hover:opacity-90",
    secondary: "bg-surface text-ink border border-line-strong shadow-[0_1px_2px_rgb(16_18_24/.05)] hover:bg-surface-2 hover:border-[color-mix(in_srgb,var(--ink)_24%,transparent)]",
    danger: "bg-red-600 text-white shadow-[inset_0_1px_0_rgb(255_255_255/.15),0_1px_2px_rgb(16_18_24/.18)] hover:bg-red-700",
    ghost: "text-ink-2 hover:bg-[color-mix(in_srgb,var(--ink)_6%,transparent)] hover:text-ink",
  }[variant];
  const sizes = { sm: "h-8 px-3 text-[13px] rounded-[9px] pointer-coarse:min-h-11", md: "h-10 px-4 text-sm rounded-[10px] pointer-coarse:min-h-11", lg: "h-12 px-6 text-[15px] rounded-[12px]" }[size];
  return (
    <button
      {...rest}
      disabled={disabled || loading}
      className={cx("inline-flex shrink-0 select-none items-center justify-center gap-2 whitespace-nowrap font-semibold transition-[background,color,box-shadow,opacity,transform] duration-200 active:scale-[.97] disabled:cursor-not-allowed disabled:opacity-45 disabled:active:scale-100", sizes, styles, className)}
    >
      {loading && <Spinner />}
      {children}
    </button>
  );
}

export function Spinner({ className }: { className?: string }) {
  return <span className={cx("inline-block h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent", className)} aria-hidden />;
}

/** Square icon-only button with an accessible label. */
export function IconButton({ icon, label, className, badge, ...rest }: ButtonHTMLAttributes<HTMLButtonElement> & { icon: IconName; label: string; badge?: number }) {
  return (
    <button {...rest} aria-label={label} title={label} className={cx("relative grid h-9 w-9 shrink-0 place-items-center rounded-[10px] text-muted transition hover:bg-[color-mix(in_srgb,var(--ink)_6%,transparent)] hover:text-ink active:scale-95 pointer-coarse:h-11 pointer-coarse:w-11", className)}>
      <Icon name={icon} className="h-[18px] w-[18px]" />
      {!!badge && badge > 0 && <span className="absolute -end-0.5 -top-0.5 grid h-4 min-w-4 animate-pop place-items-center rounded-full bg-red-500 px-1 text-[10px] font-bold text-white ring-2 ring-surface">{badge > 99 ? "99+" : badge}</span>}
    </button>
  );
}

/* ───────────── Form fields ───────────── */

/** `name` marks the field for scroll-to-error (useFieldErrors); with `error` the box turns red and the message shows under it. */
export function Field({ label, hint, error, children, name, className }: { label: string; hint?: string; error?: string; children: ReactNode; name?: string; className?: string }) {
  return (
    <label className={cx("block space-y-1.5 scroll-mt-24", error && "field-error", className)} data-field={name}>
      <span className="text-[13px] font-semibold text-ink-2">{label}</span>
      {children}
      {hint && !error && <span className="block text-xs text-muted">{hint}</span>}
      {error && <span role="alert" className="flex animate-fade-in items-start gap-1 text-xs font-medium text-red-600"><Icon name="alert" className="mt-px h-3.5 w-3.5 shrink-0" />{error}</span>}
    </label>
  );
}

const inputCls = "w-full rounded-[10px] border border-line-strong bg-surface px-3.5 py-2.5 text-sm text-ink shadow-[0_1px_2px_rgb(16_18_24/.04)] outline-none transition duration-200 placeholder:text-faint hover:border-[color-mix(in_srgb,var(--ink)_24%,transparent)] focus:border-brand focus:ring-4 focus:ring-brand/12 disabled:bg-surface-2 disabled:text-muted";

export function Input(props: InputHTMLAttributes<HTMLInputElement>) {
  return <input {...props} className={cx(inputCls, props.className)} />;
}

export function Textarea(props: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea rows={3} {...props} className={cx(inputCls, props.className)} />;
}

export function Select(props: SelectHTMLAttributes<HTMLSelectElement>) {
  return <select {...props} className={cx(inputCls, "pe-9", props.className)} />;
}

/* ───────────── Surfaces ───────────── */

/** Hairline panel. `hover` lifts it slightly (use for clickable tiles). */
export function Card({ className, children, hover }: { className?: string; children: ReactNode; hover?: boolean }) {
  return <div className={cx("rounded-[14px] bg-surface p-5 shadow-card", hover && "hover-lift", className)}>{children}</div>;
}

const badgeTones: Record<string, string> = {
  green: "bg-emerald-50 text-emerald-700 ring-emerald-600/15",
  amber: "bg-amber-50 text-amber-800 ring-amber-600/20",
  red: "bg-red-50 text-red-700 ring-red-600/15",
  blue: "bg-sky-50 text-sky-700 ring-sky-600/15",
  slate: "bg-slate-100 text-slate-600 ring-slate-500/10",
  violet: "bg-violet-50 text-violet-700 ring-violet-600/15",
};

export const STATUS_TONE: Record<string, string> = {
  CONFIRMED: "green", COMPLETED: "green", DELIVERED: "green", ARRIVED: "green", DONE: "green", SHIPPED: "blue", PREPARING: "amber", PAID: "green", ACTIVE: "green", REVIEWED: "green", ISSUED: "green", SENT: "green", TRIAL: "blue", TRIALING: "blue",
  PENDING: "amber", PENDING_CONFIRMATION: "amber", REQUESTED: "amber", UNPAID: "amber", PROCESSING: "blue", PACKED: "blue", OUT_FOR_DELIVERY: "blue", CHECKED_IN: "blue", IN_PROGRESS: "blue", UNDER_REVIEW: "amber", ORDERED: "slate", DRAFT: "slate",
  CANCELLED: "red", REJECTED: "red", NO_SHOW: "red", SUSPENDED: "red", PAST_DUE: "red", REFUNDED: "slate", PARTIALLY_REFUNDED: "amber", RETURN_REQUESTED: "amber", RETURNED: "slate", PATIENT_UPLOADED: "blue", NEW: "amber", CONTACTED: "blue", EXPIRED: "red",
};

/** Hex for a tone, for charts and dots. */
export const TONE_HEX: Record<string, string> = { green: "#16a34a", amber: "#d97706", red: "#dc2626", blue: "#0284c7", slate: "#94a3b8", violet: "#7c3aed", brand: "var(--brand)" };

export function Badge({ tone, children }: { tone?: keyof typeof badgeTones; children: ReactNode }) {
  return <span className={cx("inline-flex items-center gap-1.5 whitespace-nowrap rounded-full px-2 py-0.5 text-[11.5px] font-semibold ring-1 ring-inset", badgeTones[tone ?? "slate"])}><span className="h-1.5 w-1.5 rounded-full bg-current opacity-70" />{children}</span>;
}

/** Colour-coded status chip; the label is translated from `status.<CODE>` when available. */
export function StatusBadge({ status }: { status: string }) {
  const t = useT();
  const label = t(`status.${status}`);
  return <Badge tone={STATUS_TONE[status] ?? "slate"}>{label === `status.${status}` ? status : label}</Badge>;
}

export function Alert({ tone = "red", children }: { tone?: "red" | "green" | "amber" | "blue"; children: ReactNode }) {
  const tones = { red: "bg-red-50 text-red-800 ring-red-600/15", green: "bg-emerald-50 text-emerald-800 ring-emerald-600/15", amber: "bg-amber-50 text-amber-900 ring-amber-600/20", blue: "bg-sky-50 text-sky-900 ring-sky-600/15" };
  const icon: Record<string, IconName> = { red: "alert", green: "check", amber: "alert", blue: "sparkles" };
  return (
    <div role="alert" className={cx("flex animate-fade-up items-start gap-2.5 rounded-[12px] px-4 py-3 text-sm ring-1 ring-inset", tones[tone])}>
      <Icon name={icon[tone]} className="mt-0.5 h-4 w-4 shrink-0 opacity-80" />
      <div className="min-w-0 flex-1">{children}</div>
    </div>
  );
}

/** One clear sentence for a failed call, in the visitor's language (see lib/error-text.ts); a rejected form lists what to fix. */
export function ErrorText({ error }: { error: unknown }) {
  const { t, locale } = useI18n();
  if (!error) return null;
  const e = error as ApiError;
  const lines = fieldLines(t, e.fields);
  return <Alert>{errorMessage(t, locale, error)}{lines.length > 0 && <ul className="mt-1 list-disc ps-5">{lines.map((l, i) => <li key={i}>{l}</li>)}</ul>}</Alert>;
}

export function PageHeader({ title, subtitle, actions, badge, eyebrow }: { title: string; subtitle?: string; actions?: ReactNode; badge?: ReactNode; eyebrow?: ReactNode }) {
  return (
    <div className="mb-6 flex animate-fade-up flex-wrap items-end justify-between gap-x-4 gap-y-3 sm:mb-8">
      <div className="min-w-0">
        {eyebrow && <p className="eyebrow mb-1.5">{eyebrow}</p>}
        <h1 className="font-display text-[1.6rem] font-semibold leading-tight text-ink sm:text-[2rem]">{title}</h1>
        {subtitle && <p className="mt-1.5 flex flex-wrap items-center gap-2 text-sm text-muted">{subtitle}{badge}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}

/** Empty state: an icon, an optional title, the explanation and an optional action. */
export function Empty({ children, icon = "inbox", title, action, compact }: { children?: ReactNode; icon?: IconName; title?: ReactNode; action?: ReactNode; compact?: boolean }) {
  return (
    <div className={cx("animate-fade-up rounded-[14px] border border-dashed border-line-strong bg-surface-2/70 text-center", compact ? "px-6 py-7" : "px-6 py-12")}>
      <span className="relative mx-auto mb-4 grid h-12 w-12 place-items-center">
        <span className="absolute inset-0 rotate-6 rounded-[14px] bg-brand-soft-2" />
        <span className="absolute inset-0 grid place-items-center rounded-[14px] bg-surface text-brand shadow-[0_0_0_1px_var(--line),0_6px_16px_-8px_rgb(16_18_24/.2)]"><Icon name={icon} className="h-5 w-5" /></span>
      </span>
      {title && <p className="font-display text-[15px] font-semibold text-ink">{title}</p>}
      {children && <div className={cx("mx-auto max-w-md text-sm text-muted", !!title && "mt-1")}>{children}</div>}
      {action && <div className="mt-5 flex justify-center gap-2">{action}</div>}
    </div>
  );
}

export function Skeleton({ className }: { className?: string }) {
  return <div className={cx("skeleton", className)} aria-hidden />;
}

/** A number that eases up from 0 when it first shows. */
export function AnimatedNumber({ value, format }: { value: number; format?: (n: number) => string }) {
  const { locale } = useI18n();
  const [shown, setShown] = useState(value);
  const first = useRef(true);
  useEffect(() => {
    if (first.current) { first.current = false; if (typeof window === "undefined" || window.matchMedia("(prefers-reduced-motion: reduce)").matches) return; }
    let raf = 0;
    const t0 = performance.now();
    const tick = (t: number) => {
      const k = Math.min(1, (t - t0) / 900);
      setShown(value * (1 - Math.pow(1 - k, 4)));
      if (k < 1) raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [value]);
  const n = Math.round(shown);
  return <span className="tnum">{format ? format(n) : num(n, locale)}</span>;
}

/** A small "+12%" / "−4%" chip. */
export function Delta({ value, suffix = "%", invert, className }: { value: number | null | undefined; suffix?: string; invert?: boolean; className?: string }) {
  if (value == null || !isFinite(value)) return null;
  const up = value >= 0;
  const good = invert ? !up : up;
  return (
    <span className={cx("inline-flex items-center gap-0.5 rounded-full px-1.5 py-0.5 text-[11px] font-semibold tnum", good ? "bg-emerald-50 text-emerald-700" : "bg-red-50 text-red-700", className)} dir="ltr">
      <Icon name="trending" className={cx("h-3 w-3", !up && "scale-y-[-1]")} />
      {up ? "+" : "−"}{Math.abs(Math.round(value))}{suffix}
    </span>
  );
}

const toneTile = { brand: "bg-brand-soft text-brand", green: "bg-emerald-50 text-emerald-600", amber: "bg-amber-50 text-amber-600", red: "bg-red-50 text-red-600", slate: "bg-slate-100 text-slate-600", violet: "bg-violet-50 text-violet-600" };

export function IconTile({ icon, tone = "brand", className }: { icon: IconName; tone?: keyof typeof toneTile; className?: string }) {
  return <span className={cx("grid shrink-0 place-items-center rounded-[10px]", toneTile[tone], className ?? "h-9 w-9")}><Icon name={icon} className="h-[18px] w-[18px]" /></span>;
}

/** KPI tile: label, big tabular value, optional trend, hint, icon and sparkline. Numeric `value` counts up. */
export function Stat({ label, value, hint, icon, tone = "brand", trend, spark }: { label: string; value: ReactNode; hint?: ReactNode; icon?: IconName; tone?: "brand" | "green" | "amber" | "red"; trend?: { text: string; up?: boolean }; spark?: number[] }) {
  return (
    <Card hover className="relative flex flex-col overflow-hidden !p-4 sm:!p-5">
      <div className="flex items-center justify-between gap-3">
        <p className="truncate text-[13px] font-medium text-muted">{label}</p>
        {icon && <IconTile icon={icon} tone={tone} className="h-8 w-8" />}
      </div>
      <p className="mt-3 truncate font-display text-[1.7rem] font-semibold leading-none text-ink tnum">{typeof value === "number" ? <AnimatedNumber value={value} /> : value}</p>
      {(hint || trend) && (
        <p className="mt-2.5 flex flex-wrap items-center gap-2 text-xs text-muted">
          {trend && <span className={cx("inline-flex items-center gap-1 rounded-full px-1.5 py-0.5 font-semibold", trend.up === false ? "bg-red-50 text-red-600" : "bg-emerald-50 text-emerald-700")}><Icon name="trending" className={cx("h-3 w-3", trend.up === false && "scale-y-[-1]")} />{trend.text}</span>}
          {hint}
        </p>
      )}
      {spark && spark.length > 1 && <div className="-mx-1 mt-3"><Sparkline values={spark} height={30} /></div>}
    </Card>
  );
}

/** A row of KPIs in ONE hairline panel, separated by dividers (reads calmer than a grid of boxes). */
export function KpiStrip({ items, className }: { items: { label: string; value: ReactNode; hint?: ReactNode; delta?: number | null; icon?: IconName; tone?: keyof typeof toneTile; href?: string }[]; className?: string }) {
  return (
    <div className={cx("grid overflow-hidden rounded-[14px] bg-surface shadow-card", items.length >= 4 ? "grid-cols-2 lg:grid-cols-4" : items.length === 3 ? "grid-cols-1 sm:grid-cols-3" : "grid-cols-2", className)}>
      {items.map((k, i) => {
        const body = (
          <>
            <div className="flex items-center gap-2 text-[12.5px] font-medium text-muted">
              {k.icon && <IconTile icon={k.icon} tone={k.tone ?? "slate"} className="h-6 w-6 !rounded-[7px] [&_svg]:!h-3.5 [&_svg]:!w-3.5" />}
              <span className="truncate">{k.label}</span>
            </div>
            <div className="mt-2.5 flex flex-wrap items-baseline gap-2">
              <span className="font-display text-[1.45rem] font-semibold leading-none text-ink tnum sm:text-[1.6rem]">{typeof k.value === "number" ? <AnimatedNumber value={k.value} /> : k.value}</span>
              <Delta value={k.delta} />
            </div>
            {k.hint && <p className="mt-1.5 truncate text-xs text-faint">{k.hint}</p>}
          </>
        );
        const cls = "relative block min-w-0 p-4 sm:p-5 [&:not(:first-child)]:before:absolute [&:not(:first-child)]:before:inset-y-4 [&:not(:first-child)]:before:start-0 [&:not(:first-child)]:before:w-px [&:not(:first-child)]:before:bg-line";
        return k.href
          ? <a key={i} href={k.href} className={cx(cls, "transition hover:bg-surface-2")} style={{ ["--i" as string]: i }}>{body}</a>
          : <div key={i} className={cls}>{body}</div>;
      })}
    </div>
  );
}

/** Section card with a title row (and optional action) used to group dashboard content. */
export function Section({ title, action, children, className, subtitle, icon, flush }: { title: string; action?: ReactNode; children: ReactNode; className?: string; subtitle?: ReactNode; icon?: IconName; flush?: boolean }) {
  return (
    <section className={cx("overflow-hidden rounded-[14px] bg-surface shadow-card", className)}>
      <div className="flex min-h-14 items-center justify-between gap-3 px-5 py-3.5 hairline-b">
        <div className="flex min-w-0 items-center gap-2.5">
          {icon && <IconTile icon={icon} tone="slate" className="h-7 w-7 !rounded-[8px] [&_svg]:!h-4 [&_svg]:!w-4" />}
          <div className="min-w-0">
            <h2 className="truncate text-[14.5px] font-semibold text-ink">{title}</h2>
            {subtitle && <p className="truncate text-xs text-muted">{subtitle}</p>}
          </div>
        </div>
        {action}
      </div>
      <div className={flush ? "" : "p-5"}>{children}</div>
    </section>
  );
}

/** Alias with toolbar semantics: a Section whose body touches the edges (for tables and lists). */
export function Panel(props: Parameters<typeof Section>[0]) {
  return <Section flush {...props} />;
}

/** "View all →" link used in section headers. */
export function SectionLink({ href, children }: { href: string; children: ReactNode }) {
  return <a href={href} className="group inline-flex shrink-0 items-center gap-1 text-[13px] font-semibold text-muted transition hover:text-ink">{children}<Icon name="arrow" className="h-3.5 w-3.5 transition-transform group-hover:translate-x-0.5 flip-rtl rtl:group-hover:-translate-x-0.5" /></a>;
}

/** Segmented tabs with a sliding indicator. Controlled: pass the active key and a handler. */
export function Tabs<T extends string>({ tabs, value, onChange, size = "md" }: { tabs: { key: T; label: string; count?: number }[]; value: T; onChange: (k: T) => void; size?: "sm" | "md" }) {
  const id = useId();
  const reduce = useReducedMotion();
  return (
    <div role="tablist" className="inline-flex max-w-full gap-0.5 overflow-x-auto rounded-[11px] bg-[color-mix(in_srgb,var(--ink)_5%,transparent)] p-[3px]">
      {tabs.map((tb) => {
        const on = tb.key === value;
        return (
          <button key={tb.key} role="tab" aria-selected={on} onClick={() => onChange(tb.key)} className={cx("relative whitespace-nowrap rounded-[8px] font-semibold transition-colors", size === "sm" ? "px-2.5 py-1 text-[12.5px]" : "px-3.5 py-1.5 text-[13px]", on ? "text-ink" : "text-muted hover:text-ink")}>
            {on && <motion.span layoutId={`tab-${id}`} transition={reduce ? { duration: 0 } : spring} className="absolute inset-0 rounded-[8px] bg-surface shadow-[0_0_0_1px_var(--line),0_1px_3px_rgb(16_18_24/.08)]" />}
            <span className="relative">{tb.label}{tb.count !== undefined && <span className={cx("ms-1.5 rounded-full px-1.5 text-[11px] tnum", on ? "bg-brand-soft text-brand" : "bg-[color-mix(in_srgb,var(--ink)_7%,transparent)]")}>{tb.count}</span>}</span>
          </button>
        );
      })}
    </div>
  );
}

/** Same look as Tabs, for picking a mode (chart metric, view). */
export const Segmented = Tabs;

/** Row that holds search + filters above a table. */
export function FilterBar({ children }: { children: ReactNode }) {
  return <div className="mb-4 flex flex-wrap items-center gap-2.5 rounded-[14px] bg-surface p-2.5 shadow-card">{children}</div>;
}
export const Toolbar = FilterBar;

/* ───────────── Charts (no library) ───────────── */

/** Area chart: the line draws in, hover shows a crosshair and the value. */
export function AreaChart({ points, height = 180, format, axis = true }: { points: { label: string; value: number }[]; height?: number; format?: (n: number) => string; axis?: boolean }) {
  const id = useId().replace(/:/g, "");
  const [hover, setHover] = useState<number | null>(null);
  if (points.length < 2) return null;
  const W = 600, H = height, top = 14, bottom = axis ? 22 : 6;
  const max = Math.max(...points.map((p) => p.value), 1) * 1.08;
  const x = (i: number) => (i * W) / (points.length - 1);
  const y = (v: number) => top + (1 - v / max) * (H - top - bottom);
  // smooth (monotone-ish) path
  const pts = points.map((p, i) => [x(i), y(p.value)] as const);
  let line = `M${pts[0][0]},${pts[0][1]}`;
  for (let i = 1; i < pts.length; i++) { const [x0, y0] = pts[i - 1], [x1, y1] = pts[i], cx1 = x0 + (x1 - x0) / 2; line += ` C${cx1},${y0} ${cx1},${y1} ${x1},${y1}`; }
  const area = `${line} L${W},${H - bottom} L0,${H - bottom} Z`;
  const act = hover ?? points.length - 1;
  const ticks = axis ? [0, Math.floor((points.length - 1) / 2), points.length - 1] : [];
  return (
    <div className="relative overflow-x-clip" dir="ltr">
      <svg viewBox={`0 0 ${W} ${H}`} className="block w-full" style={{ height }} preserveAspectRatio="none" onMouseLeave={() => setHover(null)}>
        <defs><linearGradient id={id} x1="0" x2="0" y1="0" y2="1"><stop offset="0%" stopColor="var(--brand)" stopOpacity="0.22" /><stop offset="100%" stopColor="var(--brand)" stopOpacity="0" /></linearGradient></defs>
        {[0.25, 0.5, 0.75].map((g) => <line key={g} x1="0" x2={W} y1={top + (H - top - bottom) * g} y2={top + (H - top - bottom) * g} stroke="var(--line)" vectorEffect="non-scaling-stroke" />)}
        <path d={area} fill={`url(#${id})`} className="animate-fade-in" />
        <path d={line} pathLength={1} strokeDasharray="1" fill="none" stroke="var(--brand)" strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" vectorEffect="non-scaling-stroke" className="animate-draw" />
        {points.map((p, i) => <rect key={i} x={x(i) - W / points.length / 2} y={0} width={W / points.length} height={H} fill="transparent" onMouseEnter={() => setHover(i)} onTouchStart={() => setHover(i)} />)}
        <line x1={x(act)} x2={x(act)} y1={top} y2={H - bottom} stroke="var(--ink)" strokeOpacity="0.18" strokeDasharray="3 3" vectorEffect="non-scaling-stroke" />
      </svg>
      <span className="pointer-events-none absolute h-2.5 w-2.5 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-surface bg-brand shadow transition-all duration-150" style={{ left: `${(x(act) / W) * 100}%`, top: `${(y(points[act].value) / H) * 100}%` }} />
      {axis && <div className="pointer-events-none absolute inset-x-0 bottom-0 flex justify-between text-[10.5px] text-faint">{ticks.map((i) => <span key={i}>{points[i].label}</span>)}</div>}
      {/* centred on its point in the middle, flush with the chart's own edge at either end */}
      <div className="pointer-events-none absolute top-0 max-w-full -translate-x-[var(--p)] whitespace-nowrap rounded-[8px] bg-ink px-2.5 py-1 text-xs font-semibold text-surface shadow-lift tnum" style={{ left: `${(x(act) / W) * 100}%`, ["--p" as string]: `${(x(act) / W) * 100}%` }}>
        <span className="opacity-60">{points[act].label}</span> · {format ? format(points[act].value) : points[act].value.toLocaleString()}
      </div>
    </div>
  );
}

/** Tiny trend line for KPI tiles and table rows. */
export function Sparkline({ values, height = 28, color = "var(--brand)", className }: { values: number[]; height?: number; color?: string; className?: string }) {
  const id = useId().replace(/:/g, "");
  if (values.length < 2) return null;
  const W = 100, H = height, max = Math.max(...values, 1), min = Math.min(...values, 0);
  const x = (i: number) => (i * W) / (values.length - 1);
  const y = (v: number) => H - 2 - ((v - min) / (max - min || 1)) * (H - 4);
  const line = values.map((v, i) => `${i ? "L" : "M"}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(" ");
  return (
    <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" className={cx("block w-full", className)} style={{ height }} aria-hidden>
      <defs><linearGradient id={id} x1="0" x2="0" y1="0" y2="1"><stop offset="0%" stopColor={color} stopOpacity="0.25" /><stop offset="100%" stopColor={color} stopOpacity="0" /></linearGradient></defs>
      <path d={`${line} L${W},${H} L0,${H} Z`} fill={`url(#${id})`} />
      <path d={line} pathLength={1} strokeDasharray="1" fill="none" stroke={color} strokeWidth="1.6" vectorEffect="non-scaling-stroke" strokeLinejoin="round" className="animate-draw" />
    </svg>
  );
}

/** Vertical bars with hover values (e.g. orders per day). */
export function BarChart({ points, height = 160, format, color = "var(--brand)" }: { points: { label: string; value: number }[]; height?: number; format?: (n: number) => string; color?: string }) {
  const { locale } = useI18n();
  const [hover, setHover] = useState<number | null>(null);
  if (!points.length) return null;
  const max = Math.max(...points.map((p) => p.value), 1);
  const act = hover ?? points.length - 1;
  return (
    <div className="relative" dir="ltr">
      <div className="flex items-end gap-[3px] sm:gap-1.5" style={{ height }} onMouseLeave={() => setHover(null)}>
        {points.map((p, i) => (
          <div key={i} className="group flex h-full flex-1 cursor-default items-end" onMouseEnter={() => setHover(i)} onTouchStart={() => setHover(i)}>
            <div className="grow-y w-full rounded-t-[4px] transition-opacity" style={{ height: `${Math.max(2, (p.value / max) * 100)}%`, background: color, opacity: i === act ? 1 : 0.28, ["--i" as string]: i }} />
          </div>
        ))}
      </div>
      <div className="mt-1.5 flex justify-between text-[10.5px] text-faint"><span>{points[0].label}</span><span>{points[points.length - 1].label}</span></div>
      <div className="pointer-events-none absolute -top-8 rounded-[8px] bg-ink px-2 py-1 text-xs font-semibold whitespace-nowrap text-surface tnum" style={{ left: `clamp(0px, calc(${((act + 0.5) / points.length) * 100}% - 3rem), calc(100% - 6rem))` }}>
        <span className="opacity-60">{points[act].label}</span> · {format ? format(points[act].value) : num(points[act].value, locale)}
      </div>
    </div>
  );
}

/** Ring chart with a legend. Segments animate in. */
export function DonutChart({ segments, size = 140, center }: { segments: { label: string; value: number; color: string }[]; size?: number; center?: ReactNode }) {
  const { locale } = useI18n();
  const [hover, setHover] = useState<number | null>(null);
  const total = segments.reduce((s, x) => s + x.value, 0);
  const R = 42, C = 2 * Math.PI * R;
  let acc = 0;
  return (
    <div className="flex flex-wrap items-center gap-5">
      <div className="relative shrink-0" style={{ width: size, height: size }}>
        <svg viewBox="0 0 100 100" className="-rotate-90" style={{ width: size, height: size }}>
          <circle cx="50" cy="50" r={R} fill="none" stroke="var(--line)" strokeWidth="11" />
          {total > 0 && segments.map((s, i) => {
            const len = (s.value / total) * C;
            const el = <circle key={i} cx="50" cy="50" r={R} fill="none" stroke={s.color} strokeWidth={hover === i ? 13 : 11} strokeDasharray={`${Math.max(0, len - 1.2)} ${C}`} strokeDashoffset={-acc} className="transition-[stroke-width] duration-200" style={{ animation: `fade-in .5s ${i * 0.06}s both` }} onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)} />;
            acc += len;
            return el;
          })}
        </svg>
        <div className="absolute inset-0 grid place-items-center text-center">
          {hover != null ? <div><p className="font-display text-xl font-semibold text-ink tnum">{num(segments[hover].value, locale)}</p><p className="max-w-[5.5rem] truncate text-[11px] text-muted">{segments[hover].label}</p></div> : center}
        </div>
      </div>
      <ul className="min-w-0 flex-1 space-y-1.5 text-[13px]">
        {segments.map((s, i) => (
          <li key={i} className={cx("flex items-center gap-2 rounded-md px-1.5 py-0.5 transition", hover === i && "bg-surface-2")} onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)}>
            <span className="h-2.5 w-2.5 shrink-0 rounded-[3px]" style={{ background: s.color }} />
            <span className="min-w-0 flex-1 truncate text-ink-2">{s.label}</span>
            <span className="font-semibold text-ink tnum">{num(s.value, locale)}</span>
            <span className="w-9 text-end text-xs text-faint tnum">{total ? Math.round((s.value / total) * 100) : 0}%</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** Horizontal share bar list (top products, governorates…). */
export function ShareBars({ rows, format }: { rows: { label: ReactNode; value: number; sub?: ReactNode }[]; format?: (n: number) => string }) {
  const { locale } = useI18n();
  const max = Math.max(...rows.map((r) => r.value), 1);
  return (
    <ul className="space-y-3.5">
      {rows.map((r, i) => (
        <li key={i} className="text-[13px]">
          <div className="mb-1.5 flex items-baseline justify-between gap-3">
            <span className="min-w-0 truncate font-medium text-ink-2">{r.label}</span>
            <span className="shrink-0 font-semibold text-ink tnum">{format ? format(r.value) : num(r.value, locale)}{r.sub && <span className="ms-1.5 text-xs font-normal text-faint">{r.sub}</span>}</span>
          </div>
          <div className="h-1.5 overflow-hidden rounded-full bg-[color-mix(in_srgb,var(--ink)_6%,transparent)]">
            <div className="grow-x h-full rounded-full bg-brand" style={{ width: `${(r.value / max) * 100}%`, opacity: 1 - i * 0.12, ["--i" as string]: i }} />
          </div>
        </li>
      ))}
    </ul>
  );
}

/* ───────────── Lists ───────────── */

/** Vertical timeline (today's appointments, order history). */
export function Timeline({ items }: { items: { key: string | number; time?: ReactNode; title: ReactNode; meta?: ReactNode; aside?: ReactNode; tone?: string; active?: boolean; href?: string }[] }) {
  return (
    <ol className="relative">
      {items.map((it, i) => {
        const body = (
          <div className={cx("relative flex items-start gap-3.5 rounded-[12px] px-2 py-2.5 transition", it.href && "hover:bg-surface-2", it.active && "bg-brand-soft")}>
            <div className="w-14 shrink-0 pt-0.5 text-end text-xs font-semibold text-muted tnum" dir="ltr">{it.time}</div>
            <div className="relative flex flex-col items-center self-stretch">
              <span className={cx("relative z-10 mt-1 h-2.5 w-2.5 rounded-full ring-4 ring-surface", it.active && "live-dot")} style={{ background: TONE_HEX[it.tone ?? "slate"] ?? it.tone }} />
              {i < items.length - 1 && <span className="absolute top-4 -bottom-3 w-px bg-line" />}
            </div>
            <div className="min-w-0 flex-1">
              <div className="truncate text-sm font-semibold text-ink">{it.title}</div>
              {it.meta && <div className="mt-0.5 truncate text-xs text-muted">{it.meta}</div>}
            </div>
            {it.aside && <div className="shrink-0">{it.aside}</div>}
          </div>
        );
        return <li key={it.key} className="stagger-item" style={{ animation: `fade-up .4s ${i * 0.04}s both` }}>{it.href ? <a href={it.href} className="block">{body}</a> : body}</li>;
      })}
    </ol>
  );
}

/** "Needs attention" row: tone dot, count, label and an arrow; only renders when count > 0. */
export function AttentionRow({ items }: { items: { href: string; count: number; label: string; tone?: "amber" | "red" | "blue"; icon?: IconName }[] }) {
  const shown = items.filter((i) => i.count > 0);
  if (!shown.length) return null;
  const tones = { amber: "text-amber-700 bg-amber-50", red: "text-red-700 bg-red-50", blue: "text-sky-700 bg-sky-50" };
  return (
    <div className="flex gap-2 overflow-x-auto pb-1 [scrollbar-width:none]">
      {shown.map((a, i) => (
        <a key={a.href} href={a.href} className="group press flex shrink-0 items-center gap-2.5 rounded-[12px] bg-surface py-2 ps-2 pe-3 shadow-card transition hover:shadow-[0_0_0_1px_var(--line-strong)]" style={{ animation: `fade-up .4s ${i * 0.05}s both` }}>
          <span className={cx("grid h-7 min-w-7 place-items-center rounded-[8px] px-1.5 text-[13px] font-bold tnum", tones[a.tone ?? "amber"])}>{a.count}</span>
          <span className="text-[13px] font-medium text-ink-2">{a.label}</span>
          <Icon name="chevron" className="h-3.5 w-3.5 text-faint transition-transform group-hover:translate-x-0.5 flip-rtl" />
        </a>
      ))}
    </div>
  );
}

/** Round avatar with the person's initials. */
export function Avatar({ name, className = "h-9 w-9 text-sm" }: { name: string; className?: string }) {
  const initials = name.trim().split(/\s+/).slice(0, 2).map((w) => w[0]).join("").toUpperCase();
  // a stable hue per name so lists of people don't all look the same
  let h = 0; for (const ch of name) h = (h * 31 + ch.charCodeAt(0)) % 360;
  return <span className={cx("inline-flex shrink-0 items-center justify-center rounded-full font-semibold", className)} style={{ background: `oklch(0.93 0.04 ${h})`, color: `oklch(0.38 0.09 ${h})` }} aria-hidden>{initials || "•"}</span>;
}

/** Skeletons shaped like what's coming. */
export function Loading({ variant = "page" }: { variant?: "page" | "list" | "table" | "cards" | "detail" }) {
  if (variant === "list" || variant === "table") return (
    <div className="overflow-hidden rounded-[14px] bg-surface shadow-card" aria-busy="true">
      <div className="flex gap-3 border-b border-line px-4 py-3.5"><Skeleton className="h-3 w-24" /><Skeleton className="ms-auto h-3 w-16" /></div>
      {Array.from({ length: 6 }).map((_, i) => (
        <div key={i} className="flex items-center gap-3 border-b border-line px-4 py-3.5 last:border-0" style={{ opacity: 1 - i * 0.12 }}>
          <Skeleton className="h-8 w-8 !rounded-full" /><div className="flex-1 space-y-1.5"><Skeleton className="h-3 w-2/5" /><Skeleton className="h-2.5 w-1/4" /></div><Skeleton className="h-5 w-16 !rounded-full" />
        </div>
      ))}
    </div>
  );
  if (variant === "cards") return (
    <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4" aria-busy="true">
      {Array.from({ length: 8 }).map((_, i) => <div key={i} className="space-y-2.5 rounded-[14px] bg-surface p-3 shadow-card"><Skeleton className="aspect-square w-full" /><Skeleton className="h-3 w-3/4" /><Skeleton className="h-3 w-1/3" /></div>)}
    </div>
  );
  if (variant === "detail") return (
    <div className="grid gap-4 lg:grid-cols-3" aria-busy="true">
      <div className="space-y-4 lg:col-span-2"><Skeleton className="h-40 w-full !rounded-[14px]" /><Skeleton className="h-64 w-full !rounded-[14px]" /></div>
      <Skeleton className="h-80 w-full !rounded-[14px]" />
    </div>
  );
  return (
    <div className="space-y-5" aria-busy="true">
      <div className="space-y-2"><Skeleton className="h-3 w-20" /><Skeleton className="h-8 w-56" /></div>
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">{Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-24 !rounded-[14px]" />)}</div>
      <Skeleton className="h-64 w-full !rounded-[14px]" />
    </div>
  );
}

/** Below `sm` the rows turn into stacked cards; each cell is labelled with its column header (data-label, set from the <th> text). */
export function Table({ head, children }: { head: ReactNode[]; children: ReactNode }) {
  const ref = useRef<HTMLTableElement>(null);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const labels = Array.from(el.tHead?.rows[0]?.cells ?? []).map((c) => c.textContent?.trim() ?? "");
    el.querySelectorAll("tbody > tr").forEach((tr) => Array.from((tr as HTMLTableRowElement).cells).forEach((td, i) => td.setAttribute("data-label", labels[i] ?? "")));
  });
  return (
    <div className="animate-fade-up overflow-x-auto rounded-[14px] bg-surface shadow-card">
      <table ref={ref} className="table-cards w-full text-start text-sm">
        <thead className="text-[11.5px] text-muted">
          <tr className="hairline-b">{head.map((h, i) => <th key={i} className="whitespace-nowrap bg-surface-2 px-4 py-3 text-start font-semibold first:ps-5 last:pe-5">{h}</th>)}</tr>
        </thead>
        <tbody className="divide-y divide-line [&>tr]:transition-colors [&>tr:hover]:bg-surface-2 [&>tr>td:first-child]:ps-5 [&>tr>td:last-child]:pe-5">{children}</tbody>
      </table>
    </div>
  );
}

export const Td = ({ children, className }: { children?: ReactNode; className?: string }) => <td className={cx("px-4 py-3 align-middle", className)}>{children}</td>;

export function Modal({ open, onClose, title, children, wide }: { open: boolean; onClose: () => void; title: string; children: ReactNode; wide?: boolean }) {
  const reduce = useReducedMotion();
  // only read while open (never during the server render), so no hydration mismatch
  const phone = open && typeof window !== "undefined" && window.matchMedia("(max-width: 639px)").matches;
  useEffect(() => {
    if (!open) return;
    const h = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", h);
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => { window.removeEventListener("keydown", h); document.body.style.overflow = prev; };
  }, [open, onClose]);
  const panel = phone ? { initial: { y: "100%" }, animate: { y: 0 }, exit: { y: "100%" } } : { initial: { opacity: 0, scale: 0.97, y: 8 }, animate: { opacity: 1, scale: 1, y: 0 }, exit: { opacity: 0, scale: 0.98, y: 4 } };
  return (
    <AnimatePresence>
      {open && (
        <motion.div key="m" className="fixed inset-0 z-50 flex items-end justify-center bg-[rgb(10_12_16/.42)] p-0 backdrop-blur-[3px] sm:items-center sm:p-4" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={{ duration: reduce ? 0 : 0.18 }} onClick={onClose} role="dialog" aria-modal="true" aria-label={title}>
          <motion.div {...panel} transition={reduce ? { duration: 0 } : { duration: 0.28, ease: ease.out }} className={cx("max-h-[90dvh] sheet-scroll w-full overflow-y-auto rounded-t-[22px] bg-surface p-6 pb-[calc(1.5rem+var(--safe-b))] shadow-[var(--shadow-float)] sm:rounded-[18px] sm:pb-6", wide ? "sm:max-w-3xl" : "sm:max-w-lg")} onClick={(e) => e.stopPropagation()}>
            <div className="mx-auto -mt-2 mb-3 h-1 w-10 rounded-full bg-line-strong sm:hidden" />
            <div className="mb-5 flex items-center justify-between gap-3">
              <h2 className="font-display text-lg font-semibold text-ink">{title}</h2>
              <button onClick={onClose} className="grid h-9 w-9 place-items-center rounded-full text-muted transition hover:bg-surface-2 hover:text-ink pointer-coarse:h-11 pointer-coarse:w-11" aria-label="Close"><Icon name="close" className="h-[18px] w-[18px]" /></button>
            </div>
            {children}
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}

/** One-time secret (activation/link PIN) shown once with a copy button. */
export function SecretBox({ label, value }: { label: string; value: string }) {
  const t = useT();
  const [copied, setCopied] = useState(false);
  return (
    <div className="rounded-[12px] border-2 border-dashed border-amber-400 bg-amber-50 p-4">
      <p className="text-sm font-medium text-amber-900">{label}</p>
      <p className="my-2 select-all font-mono text-2xl tracking-widest" dir="ltr">{value}</p>
      <p className="mb-2 text-xs text-amber-800">{t("secret.once")}</p>
      <Button size="sm" variant="secondary" onClick={() => { navigator.clipboard?.writeText(value); setCopied(true); }}>{copied ? t("common.copied") : t("common.copy")}</Button>
    </div>
  );
}

export function Pager({ meta, onPage }: { meta?: { page: number; pageSize: number; total: number; hasNext: boolean }; onPage: (p: number) => void }) {
  const t = useT();
  if (!meta || meta.total <= meta.pageSize) return null;
  return (
    <div className="mt-4 flex items-center justify-between text-[13px] text-muted">
      <span className="tnum">{t("common.pageOf", { page: meta.page, pages: Math.ceil(meta.total / meta.pageSize) })}</span>
      <div className="flex gap-2">
        <Button size="sm" variant="secondary" disabled={meta.page <= 1} onClick={() => onPage(meta.page - 1)}><Icon name="chevron" className="h-3.5 w-3.5 rotate-180 flip-rtl" />{t("common.prev")}</Button>
        <Button size="sm" variant="secondary" disabled={!meta.hasNext} onClick={() => onPage(meta.page + 1)}>{t("common.next")}<Icon name="chevron" className="h-3.5 w-3.5 flip-rtl" /></Button>
      </div>
    </div>
  );
}
