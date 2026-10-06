"use client";

import { useEffect, useState, type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from "react";
import { ApiError } from "@/lib/client";
import { useT } from "./i18n-provider";

const cx = (...c: (string | false | null | undefined)[]) => c.filter(Boolean).join(" ");

export function Button({ variant = "primary", size = "md", loading, className, children, disabled, ...rest }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: "primary" | "secondary" | "danger" | "ghost"; size?: "sm" | "md"; loading?: boolean }) {
  const styles = {
    primary: "bg-brand text-white hover:opacity-90",
    secondary: "bg-white text-slate-800 border border-slate-300 hover:bg-slate-50",
    danger: "bg-red-600 text-white hover:bg-red-700",
    ghost: "text-slate-700 hover:bg-slate-100",
  }[variant];
  return (
    <button
      {...rest}
      disabled={disabled || loading}
      className={cx("inline-flex items-center justify-center gap-2 rounded-lg font-medium transition disabled:cursor-not-allowed disabled:opacity-50", size === "sm" ? "px-3 py-1.5 text-sm" : "px-4 py-2.5 text-sm", styles, className)}
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
      <span className="text-sm font-medium text-slate-700">{label}</span>
      {children}
      {hint && !error && <span className="block text-xs text-slate-500">{hint}</span>}
      {error && <span className="block text-xs text-red-600">{error}</span>}
    </label>
  );
}

const inputCls = "w-full rounded-lg border border-slate-300 bg-white px-3 py-2.5 text-sm outline-none transition placeholder:text-slate-400 focus:border-brand focus:ring-2 focus:ring-brand/20 disabled:bg-slate-100";

export function Input(props: InputHTMLAttributes<HTMLInputElement>) {
  return <input {...props} className={cx(inputCls, props.className)} />;
}

export function Textarea(props: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea rows={3} {...props} className={cx(inputCls, props.className)} />;
}

export function Select(props: SelectHTMLAttributes<HTMLSelectElement>) {
  return <select {...props} className={cx(inputCls, props.className)} />;
}

export function Card({ className, children }: { className?: string; children: ReactNode }) {
  return <div className={cx("rounded-xl border border-slate-200 bg-white p-5 shadow-sm", className)}>{children}</div>;
}

const badgeTones: Record<string, string> = {
  green: "bg-emerald-100 text-emerald-800",
  amber: "bg-amber-100 text-amber-800",
  red: "bg-red-100 text-red-800",
  blue: "bg-sky-100 text-sky-800",
  slate: "bg-slate-100 text-slate-700",
};

const STATUS_TONE: Record<string, string> = {
  CONFIRMED: "green", COMPLETED: "green", DELIVERED: "green", PAID: "green", ACTIVE: "green", REVIEWED: "green", ISSUED: "green", SENT: "green", TRIAL: "blue", TRIALING: "blue",
  PENDING: "amber", PENDING_CONFIRMATION: "amber", REQUESTED: "amber", UNPAID: "amber", PROCESSING: "blue", PACKED: "blue", OUT_FOR_DELIVERY: "blue", CHECKED_IN: "blue", IN_PROGRESS: "blue", UNDER_REVIEW: "amber", ORDERED: "slate", DRAFT: "slate",
  CANCELLED: "red", REJECTED: "red", NO_SHOW: "red", SUSPENDED: "red", PAST_DUE: "red", REFUNDED: "slate", PARTIALLY_REFUNDED: "amber", RETURN_REQUESTED: "amber", RETURNED: "slate", PATIENT_UPLOADED: "blue",
};

export function Badge({ tone, children }: { tone?: keyof typeof badgeTones; children: ReactNode }) {
  return <span className={cx("inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium", badgeTones[tone ?? "slate"])}>{children}</span>;
}

/** Colour-coded status chip; the label is translated from `status.<CODE>` when available. */
export function StatusBadge({ status }: { status: string }) {
  const t = useT();
  const label = t(`status.${status}`);
  return <Badge tone={STATUS_TONE[status] ?? "slate"}>{label === `status.${status}` ? status : label}</Badge>;
}

export function Alert({ tone = "red", children }: { tone?: "red" | "green" | "amber" | "blue"; children: ReactNode }) {
  const tones = { red: "border-red-200 bg-red-50 text-red-800", green: "border-emerald-200 bg-emerald-50 text-emerald-800", amber: "border-amber-200 bg-amber-50 text-amber-900", blue: "border-sky-200 bg-sky-50 text-sky-900" };
  return <div role="alert" className={cx("rounded-lg border px-4 py-3 text-sm", tones[tone])}>{children}</div>;
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

export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: string; actions?: ReactNode }) {
  return (
    <div className="mb-6 flex flex-wrap items-start justify-between gap-3">
      <div>
        <h1 className="text-2xl font-semibold text-slate-900">{title}</h1>
        {subtitle && <p className="mt-1 text-sm text-slate-500">{subtitle}</p>}
      </div>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="rounded-xl border border-dashed border-slate-300 p-10 text-center text-sm text-slate-500">{children}</div>;
}

export function Loading() {
  return <div className="flex items-center justify-center p-10 text-slate-500"><Spinner className="h-6 w-6" /></div>;
}

export function Table({ head, children }: { head: ReactNode[]; children: ReactNode }) {
  return (
    <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white">
      <table className="w-full text-start text-sm">
        <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
          <tr>{head.map((h, i) => <th key={i} className="px-4 py-3 text-start font-medium">{h}</th>)}</tr>
        </thead>
        <tbody className="divide-y divide-slate-100">{children}</tbody>
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
    return () => window.removeEventListener("keydown", h);
  }, [open, onClose]);
  if (!open) return null;
  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/40 p-0 sm:items-center sm:p-4" onClick={onClose} role="dialog" aria-modal="true" aria-label={title}>
      <div className={cx("max-h-[90vh] w-full overflow-y-auto rounded-t-2xl bg-white p-6 shadow-xl sm:rounded-2xl", wide ? "sm:max-w-3xl" : "sm:max-w-lg")} onClick={(e) => e.stopPropagation()}>
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-lg font-semibold">{title}</h2>
          <button onClick={onClose} className="rounded p-1 text-slate-500 hover:bg-slate-100" aria-label="Close">✕</button>
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
