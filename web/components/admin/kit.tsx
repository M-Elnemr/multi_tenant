"use client";

import type { ReactNode } from "react";
import { useI18n } from "../i18n-provider";
import { Icon, type IconName } from "../icons";
import { whatsappLink } from "@/lib/whatsapp";

const cx = (...c: (string | false | null | undefined)[]) => c.filter(Boolean).join(" ");

/** Search field for the admin toolbars (theme aware, logical padding). */
export function AdminSearch({ value, onChange, placeholder }: { value: string; onChange: (v: string) => void; placeholder: string }) {
  return (
    <label className="relative block min-w-0 flex-1 basis-56">
      <Icon name="search" className="pointer-events-none absolute start-3 top-1/2 h-4 w-4 -translate-y-1/2 text-faint" />
      <input type="search" value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder} aria-label={placeholder}
        className="h-10 w-full rounded-[11px] border border-line-strong bg-surface ps-9 pe-3 text-sm text-ink outline-none transition placeholder:text-faint hover:border-faint focus:border-brand focus:ring-4 focus:ring-brand/15" />
    </label>
  );
}

/** A sortable column header: a button with an arrow, aria-sort on the th is set by the caller via `active`. */
export function SortHead({ label, active, dir, onClick }: { label: string; active: boolean; dir: "asc" | "desc"; onClick: () => void }) {
  return (
    <button type="button" onClick={onClick} className={cx("inline-flex items-center gap-1 font-semibold transition hover:text-ink", active && "text-ink")}>
      {label}
      <Icon name="chevron" className={cx("h-3 w-3 transition", active ? (dir === "asc" ? "-rotate-90" : "rotate-90") : "opacity-30 rotate-90")} />
    </button>
  );
}

export function typeIcon(type: string): IconName {
  return type === "STORE" ? "bag" : type === "SERVICE" ? "calendar" : type === "LAWYER" ? "scale" : "stethoscope";
}
export function useTypeLabel() {
  const { t } = useI18n();
  return (type: string) => (type === "STORE" ? t("type.store") : type === "SERVICE" ? t("type.service") : type === "LAWYER" ? t("type.lawyer") : t("type.clinic"));
}

/** Type as a quiet inline label with icon (no pill: tables get busy fast). */
export function TypeTag({ type }: { type: string }) {
  const label = useTypeLabel();
  return <span className="inline-flex items-center gap-1.5 text-[12.5px] text-muted"><Icon name={typeIcon(type)} className="h-3.5 w-3.5" />{label(type)}</span>;
}

/** Call + WhatsApp as small square icon buttons. */
export function ContactButtons({ phone, name, size = "sm" }: { phone: string; name?: string; size?: "sm" | "md" }) {
  const { t } = useI18n();
  const cls = size === "md" ? "h-10 w-10" : "h-8 w-8";
  return (
    <span className="inline-flex shrink-0 gap-1.5">
      <a href={`tel:${phone}`} aria-label={t("admin.call")} title={t("admin.call")} className={cx("grid place-items-center rounded-[9px] bg-brand-soft text-brand transition hover:bg-brand-soft-2", cls)}><Icon name="phone" className="h-4 w-4" /></a>
      <a href={whatsappLink(phone, name)} target="_blank" rel="noopener noreferrer" aria-label={t("admin.whatsapp")} title={t("admin.whatsapp")} className={cx("grid place-items-center rounded-[9px] bg-emerald-50 text-emerald-700 transition hover:bg-emerald-100", cls)}><Icon name="chat" className="h-4 w-4" /></a>
    </span>
  );
}

/** Subscription time left: a thin bar plus the number of days, coloured by urgency. */
export function PeriodBar({ start, end, className }: { start?: string; end?: string; className?: string }) {
  const { t } = useI18n();
  if (!end) return <span className="text-faint">-</span>;
  const left = Math.ceil((new Date(end).getTime() - Date.now()) / 86_400_000);
  const total = start ? Math.max(1, Math.round((new Date(end).getTime() - new Date(start).getTime()) / 86_400_000)) : 30;
  const pct = Math.min(100, Math.max(0, (left / total) * 100));
  const tone = left <= 0 ? "bg-red-500" : left <= 7 ? "bg-amber-500" : "bg-emerald-500";
  const text = left <= 0 ? "text-red-600" : left <= 7 ? "text-amber-700" : "text-muted";
  return (
    <div className={cx("min-w-[7.5rem]", className)}>
      <p className={cx("tnum mb-1 text-[12px] font-semibold", text)}>{left <= 0 ? t("admin.expired") : t("admin.daysLeft", { n: left })}</p>
      <div className="h-1 overflow-hidden rounded-full bg-[color-mix(in_srgb,var(--ink)_8%,transparent)]"><div className={cx("h-full rounded-full", tone)} style={{ width: `${pct}%` }} /></div>
    </div>
  );
}

/** A label/value pair for fact lists inside panels. */
export function Fact({ label, children, className }: { label: string; children: ReactNode; className?: string }) {
  return <div className={cx("min-w-0", className)}><dt className="text-[11.5px] font-medium text-muted">{label}</dt><dd className="mt-0.5 text-[13.5px] font-semibold text-ink">{children}</dd></div>;
}

/** Row count line under a toolbar: "12 results" style, plus an optional end slot. */
export function ResultBar({ children, end }: { children: ReactNode; end?: ReactNode }) {
  return <div className="mb-2 flex min-h-6 flex-wrap items-center justify-between gap-2 px-1 text-[12.5px] text-muted tnum">{children}{end}</div>;
}

/** Skeleton + error + empty handled in one place for a data list. */
export function Retry({ onRetry }: { onRetry: () => void }) {
  const { t } = useI18n();
  return <button type="button" onClick={onRetry} className="mt-2 text-[13px] font-semibold text-brand underline-offset-4 hover:underline">{t("admin.retry")}</button>;
}
