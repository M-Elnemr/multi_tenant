"use client";

import { useEffect, useId, useRef, useState, type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from "react";
import { ApiError } from "@/lib/client";
import { Icon, type IconName } from "./icons";
import { useT } from "./i18n-provider";

const cx = (...c: (string | false | null | undefined)[]) => c.filter(Boolean).join(" ");

export function Button({ variant = "primary", size = "md", loading, className, children, disabled, ...rest }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: "primary" | "secondary" | "danger" | "ghost"; size?: "sm" | "md"; loading?: boolean }) {
  const styles = {
    primary: "bg-brand-gradient text-white shadow-brand hover:-translate-y-px hover:brightness-110",
    secondary: "bg-white text-slate-800 border border-slate-200 shadow-sm hover:border-brand/40 hover:bg-brand-soft hover:text-brand-dark",
    danger: "bg-red-600 text-white shadow-[0_8px_20px_-8px_rgb(220_38_38/0.6)] hover:bg-red-700",
    ghost: "text-slate-700 hover:bg-brand-soft hover:text-brand-dark",
  }[variant];
  return (
    <button
      {...rest}
      disabled={disabled || loading}
      className={cx("inline-flex select-none items-center justify-center gap-2 rounded-xl font-semibold transition duration-200 active:scale-[.97] disabled:cursor-not-allowed disabled:opacity-50 disabled:shadow-none disabled:hover:translate-y-0", size === "sm" ? "px-3 py-1.5 text-sm" : "px-5 py-2.5 text-sm", styles, className)}
    >
      {loading && <Spinner />}
      {children}
    </button>
  );
}

export function Spinner({ className }: { className?: string }) {
  return <span className={cx("inline-block h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent", className)} aria-hidden />;
}

export function Field({ label, hint, error, children }: { label: string; hint?: string; error?: string; children: ReactNode }) {
  return (
    <label className="block space-y-1.5">
      <span className="text-sm font-semibold text-slate-700">{label}</span>
      {children}
      {hint && !error && <span className="block text-xs text-slate-500">{hint}</span>}
      {error && <span className="block animate-fade-in text-xs text-red-600">{error}</span>}
    </label>
  );
}

const inputCls = "w-full rounded-xl border border-slate-200 bg-white px-3.5 py-2.5 text-sm shadow-sm outline-none transition duration-200 placeholder:text-slate-400 hover:border-slate-300 focus:border-brand focus:shadow-none focus:ring-4 focus:ring-brand/15 disabled:bg-slate-100";

export function Input(props: InputHTMLAttributes<HTMLInputElement>) {
  return <input {...props} className={cx(inputCls, props.className)} />;
}

export function Textarea(props: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea rows={3} {...props} className={cx(inputCls, props.className)} />;
}

export function Select(props: SelectHTMLAttributes<HTMLSelectElement>) {
  return <select {...props} className={cx(inputCls, props.className)} />;
}

export function Card({ className, children, hover }: { className?: string; children: ReactNode; hover?: boolean }) {
  return <div className={cx("rounded-2xl bg-white p-5 shadow-card", hover && "hover-lift", className)}>{children}</div>;
}

const badgeTones: Record<string, string> = {
  green: "bg-emerald-50 text-emerald-700 ring-emerald-600/15",
  amber: "bg-amber-50 text-amber-800 ring-amber-600/20",
  red: "bg-red-50 text-red-700 ring-red-600/15",
  blue: "bg-sky-50 text-sky-700 ring-sky-600/15",
  slate: "bg-slate-100 text-slate-600 ring-slate-500/10",
};

const STATUS_TONE: Record<string, string> = {
  CONFIRMED: "green", COMPLETED: "green", DELIVERED: "green", ARRIVED: "green", DONE: "green", SHIPPED: "blue", PREPARING: "amber", PAID: "green", ACTIVE: "green", REVIEWED: "green", ISSUED: "green", SENT: "green", TRIAL: "blue", TRIALING: "blue",
  PENDING: "amber", PENDING_CONFIRMATION: "amber", REQUESTED: "amber", UNPAID: "amber", PROCESSING: "blue", PACKED: "blue", OUT_FOR_DELIVERY: "blue", CHECKED_IN: "blue", IN_PROGRESS: "blue", UNDER_REVIEW: "amber", ORDERED: "slate", DRAFT: "slate",
  CANCELLED: "red", REJECTED: "red", NO_SHOW: "red", SUSPENDED: "red", PAST_DUE: "red", REFUNDED: "slate", PARTIALLY_REFUNDED: "amber", RETURN_REQUESTED: "amber", RETURNED: "slate", PATIENT_UPLOADED: "blue",
};

export function Badge({ tone, children }: { tone?: keyof typeof badgeTones; children: ReactNode }) {
  return <span className={cx("inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-semibold ring-1 ring-inset", badgeTones[tone ?? "slate"])}><span className="h-1.5 w-1.5 rounded-full bg-current opacity-70" />{children}</span>;
}

/** Colour-coded status chip; the label is translated from `status.<CODE>` when available. */
export function StatusBadge({ status }: { status: string }) {
  const t = useT();
  const label = t(`status.${status}`);
  return <Badge tone={STATUS_TONE[status] ?? "slate"}>{label === `status.${status}` ? status : label}</Badge>;
}

export function Alert({ tone = "red", children }: { tone?: "red" | "green" | "amber" | "blue"; children: ReactNode }) {
  const tones = { red: "border-red-200 bg-red-50 text-red-800 border-s-red-500", green: "border-emerald-200 bg-emerald-50 text-emerald-800 border-s-emerald-500", amber: "border-amber-200 bg-amber-50 text-amber-900 border-s-amber-500", blue: "border-sky-200 bg-sky-50 text-sky-900 border-s-sky-500" };
  return <div role="alert" className={cx("animate-fade-up rounded-xl border border-s-4 px-4 py-3 text-sm", tones[tone])}>{children}</div>;
}

/** Maps a backend business error code to a translated message (`error.<CODE>`), falling back to the server text. */
export function ErrorText({ error }: { error: unknown }) {
  const t = useT();
  if (!error) return null;
  const e = error as ApiError;
  const key = e.code ? `error.${e.code}` : "";
  const translated = key ? t(key) : "";
  const msg = translated && translated !== key ? translated : e.message || t("error.generic");
  return <Alert>{msg}{e.fields && <ul className="mt-1 list-disc ps-5">{Object.entries(e.fields).map(([k, v]) => <li key={k}>{k}: {v}</li>)}</ul>}</Alert>;
}

export function PageHeader({ title, subtitle, actions, badge }: { title: string; subtitle?: string; actions?: ReactNode; badge?: ReactNode }) {
  return (
    <div className="mb-7 flex animate-fade-up flex-wrap items-start justify-between gap-3">
      <div>
        <h1 className="text-2xl font-bold tracking-tight text-ink sm:text-3xl">{title}</h1>
        {subtitle && <p className="mt-1.5 flex flex-wrap items-center gap-2 text-sm text-slate-500">{subtitle}{badge}</p>}
      </div>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return (
    <div className="animate-fade-up rounded-2xl border border-dashed border-slate-300 bg-white/60 p-10 text-center text-sm text-slate-500">
      <span className="mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-2xl bg-brand-soft text-brand"><Icon name="file" className="h-6 w-6" /></span>
      {children}
    </div>
  );
}

export function Skeleton({ className }: { className?: string }) {
  return <div className={cx("skeleton", className)} aria-hidden />;
}

/** A number that eases up from 0 when it first shows. Non-numeric values render as they are. */
export function AnimatedNumber({ value, format }: { value: number; format?: (n: number) => string }) {
  const [shown, setShown] = useState(value);
  const first = useRef(true);
  useEffect(() => {
    if (first.current) { first.current = false; if (typeof window === "undefined" || window.matchMedia("(prefers-reduced-motion: reduce)").matches) return; } 
    let raf = 0;
    const t0 = performance.now();
    const from = 0;
    const tick = (t: number) => {
      const k = Math.min(1, (t - t0) / 800);
      setShown(from + (value - from) * (1 - Math.pow(1 - k, 3)));
      if (k < 1) raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [value]);
  const n = Math.round(shown);
  return <>{format ? format(n) : n.toLocaleString()}</>;
}

/** KPI tile: label, big value, optional hint, trend and icon. Numeric `value` counts up. */
export function Stat({ label, value, hint, icon, tone = "brand", trend }: { label: string; value: ReactNode; hint?: ReactNode; icon?: IconName; tone?: "brand" | "green" | "amber" | "red"; trend?: { text: string; up?: boolean } }) {
  const tones = { brand: "bg-brand-soft text-brand", green: "bg-emerald-50 text-emerald-600", amber: "bg-amber-50 text-amber-600", red: "bg-red-50 text-red-600" };
  return (
    <Card hover className="relative overflow-hidden">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-[13px] font-medium text-slate-500">{label}</p>
          <p className="mt-2 truncate text-[1.65rem] font-bold leading-none tracking-tight text-ink">{typeof value === "number" ? <AnimatedNumber value={value} /> : value}</p>
        </div>
        {icon && <span className={cx("flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl", tones[tone])}><Icon name={icon} className="h-5 w-5" /></span>}
      </div>
      {(hint || trend) && (
        <p className="mt-3 flex items-center gap-2 text-xs text-slate-500">
          {trend && <span className={cx("inline-flex items-center gap-1 rounded-full px-2 py-0.5 font-semibold", trend.up === false ? "bg-red-50 text-red-600" : "bg-emerald-50 text-emerald-700")}><Icon name="trending" className={cx("h-3 w-3", trend.up === false && "scale-y-[-1]")} />{trend.text}</span>}
          {hint}
        </p>
      )}
    </Card>
  );
}

/** Section card with a title row (and optional action) used to group dashboard content. */
export function Section({ title, action, children, className }: { title: string; action?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={cx("rounded-2xl bg-white shadow-card", className)}>
      <div className="flex items-center justify-between gap-3 border-b border-slate-100 px-5 py-4"><h2 className="font-bold text-ink">{title}</h2>{action}</div>
      <div className="p-5">{children}</div>
    </section>
  );
}

/** Segmented tabs. Controlled: pass the active key and a handler. */
export function Tabs<T extends string>({ tabs, value, onChange }: { tabs: { key: T; label: string; count?: number }[]; value: T; onChange: (k: T) => void }) {
  return (
    <div role="tablist" className="inline-flex max-w-full gap-1 overflow-x-auto rounded-xl bg-slate-100/80 p-1">
      {tabs.map((tb) => (
        <button key={tb.key} role="tab" aria-selected={tb.key === value} onClick={() => onChange(tb.key)} className={cx("whitespace-nowrap rounded-lg px-3.5 py-1.5 text-sm font-semibold transition", tb.key === value ? "bg-white text-brand shadow-sm" : "text-slate-500 hover:text-slate-800")}>
          {tb.label}{tb.count !== undefined && <span className="ms-1.5 rounded-full bg-slate-200/70 px-1.5 text-xs">{tb.count}</span>}
        </button>
      ))}
    </div>
  );
}

/** Row that holds search + filters above a table. */
export function FilterBar({ children }: { children: ReactNode }) {
  return <div className="mb-4 flex flex-wrap items-center gap-3 rounded-2xl bg-white p-3 shadow-card">{children}</div>;
}

/** Small area chart (no library): values per label; the last point is highlighted. */
export function AreaChart({ points, height = 160, format }: { points: { label: string; value: number }[]; height?: number; format?: (n: number) => string }) {
  const id = useId().replace(/:/g, "");
  const [hover, setHover] = useState<number | null>(null);
  if (points.length < 2) return null;
  const W = 600, H = height, pad = 6;
  const max = Math.max(...points.map((p) => p.value), 1);
  const x = (i: number) => pad + (i * (W - pad * 2)) / (points.length - 1);
  const y = (v: number) => H - pad - (v / max) * (H - pad * 2 - 10);
  const line = points.map((p, i) => `${i ? "L" : "M"}${x(i).toFixed(1)},${y(p.value).toFixed(1)}`).join(" ");
  const area = `${line} L${x(points.length - 1)},${H} L${x(0)},${H} Z`;
  const act = hover ?? points.length - 1;
  return (
    <div className="relative">
      <svg viewBox={`0 0 ${W} ${H}`} className="h-auto w-full" style={{ height }} preserveAspectRatio="none" onMouseLeave={() => setHover(null)}>
        <defs><linearGradient id={id} x1="0" x2="0" y1="0" y2="1"><stop offset="0%" stopColor="var(--brand)" stopOpacity="0.32" /><stop offset="100%" stopColor="var(--brand)" stopOpacity="0" /></linearGradient></defs>
        {[0.25, 0.5, 0.75].map((g) => <line key={g} x1="0" x2={W} y1={H * g} y2={H * g} stroke="#e8eef5" strokeDasharray="4 6" />)}
        <path d={area} fill={`url(#${id})`} />
        <path d={line} fill="none" stroke="var(--brand)" strokeWidth="2.5" strokeLinejoin="round" strokeLinecap="round" vectorEffect="non-scaling-stroke" />
        {points.map((p, i) => <rect key={i} x={x(i) - (W / points.length) / 2} y={0} width={W / points.length} height={H} fill="transparent" onMouseEnter={() => setHover(i)} />)}
        <line x1={x(act)} x2={x(act)} y1={0} y2={H} stroke="var(--brand)" strokeOpacity="0.25" vectorEffect="non-scaling-stroke" />
      </svg>
      <div className="pointer-events-none absolute top-0 rounded-lg bg-ink px-2.5 py-1 text-xs font-semibold text-white shadow-lift" style={{ insetInlineStart: `${(x(act) / W) * 100}%`, transform: "translateX(-50%)" }}>
        {points[act].label}: {format ? format(points[act].value) : points[act].value}
      </div>
    </div>
  );
}

/** Round avatar with the person's initials on the brand gradient. */
export function Avatar({ name, className = "h-9 w-9 text-sm" }: { name: string; className?: string }) {
  const initials = name.trim().split(/\s+/).slice(0, 2).map((w) => w[0]).join("").toUpperCase();
  return <span className={cx("inline-flex shrink-0 items-center justify-center rounded-full bg-brand-gradient font-bold text-white shadow-brand", className)} aria-hidden>{initials || "•"}</span>;
}

export function Loading() {
  return (
    <div className="space-y-3 p-2" aria-busy="true">
      <Skeleton className="h-8 w-48" />
      <Skeleton className="h-24 w-full" />
      <Skeleton className="h-24 w-full" />
    </div>
  );
}

export function Table({ head, children }: { head: ReactNode[]; children: ReactNode }) {
  return (
    <div className="animate-fade-up overflow-x-auto rounded-2xl bg-white shadow-card">
      <table className="w-full text-start text-sm">
        <thead className="border-b border-slate-100 bg-slate-50/70 text-[11px] uppercase tracking-wider text-slate-500">
          <tr>{head.map((h, i) => <th key={i} className="whitespace-nowrap px-4 py-3.5 text-start font-semibold">{h}</th>)}</tr>
        </thead>
        <tbody className="divide-y divide-slate-100 [&>tr]:transition-colors [&>tr:hover]:bg-brand-soft/50">{children}</tbody>
      </table>
    </div>
  );
}

export const Td = ({ children, className }: { children?: ReactNode; className?: string }) => <td className={cx("px-4 py-3 align-middle", className)}>{children}</td>;

export function Modal({ open, onClose, title, children, wide }: { open: boolean; onClose: () => void; title: string; children: ReactNode; wide?: boolean }) {
  useEffect(() => {
    if (!open) return;
    const h = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", h);
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => { window.removeEventListener("keydown", h); document.body.style.overflow = prev; };
  }, [open, onClose]);
  if (!open) return null;
  return (
    <div className="fixed inset-0 z-50 flex animate-fade-in items-end justify-center bg-slate-900/45 p-0 backdrop-blur-sm sm:items-center sm:p-4" onClick={onClose} role="dialog" aria-modal="true" aria-label={title}>
      <div className={cx("max-h-[90vh] w-full animate-slide-up overflow-y-auto rounded-t-3xl bg-white p-6 shadow-2xl sm:animate-scale-in sm:rounded-3xl", wide ? "sm:max-w-3xl" : "sm:max-w-lg")} onClick={(e) => e.stopPropagation()}>
        <div className="mb-5 flex items-center justify-between">
          <h2 className="text-lg font-bold text-ink">{title}</h2>
          <button onClick={onClose} className="rounded-full p-1.5 text-slate-500 transition hover:rotate-90 hover:bg-slate-100" aria-label="Close"><Icon name="close" className="h-5 w-5" /></button>
        </div>
        {children}
      </div>
    </div>
  );
}

/** One-time secret (activation/link PIN) shown once with a copy button. */
export function SecretBox({ label, value }: { label: string; value: string }) {
  const t = useT();
  const [copied, setCopied] = useState(false);
  return (
    <div className="rounded-lg border-2 border-dashed border-amber-400 bg-amber-50 p-4">
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
    <div className="mt-4 flex items-center justify-between text-sm text-slate-600">
      <span>{t("common.pageOf", { page: meta.page, pages: Math.ceil(meta.total / meta.pageSize) })}</span>
      <div className="flex gap-2">
        <Button size="sm" variant="secondary" disabled={meta.page <= 1} onClick={() => onPage(meta.page - 1)}>{t("common.prev")}</Button>
        <Button size="sm" variant="secondary" disabled={!meta.hasNext} onClick={() => onPage(meta.page + 1)}>{t("common.next")}</Button>
      </div>
    </div>
  );
}
