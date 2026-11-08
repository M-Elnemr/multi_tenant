"use client";

import { useI18n } from "../i18n-provider";
import { ChannelIcon } from "../channel-icons";
import { Icon } from "../icons";
import { STATUS_STYLE } from "./bits";
import type { Channel, Filters, Status } from "./types";
import { EMPTY } from "./types";

const STATUSES: Status[] = ["NEEDS_REPLY", "WAITING", "UNHAPPY", "TAKEOVER", "OPEN", "CLOSED"];
const CHANNELS: Channel[] = ["WHATSAPP", "INSTAGRAM", "FACEBOOK", "WEB"];
const TIMES = ["30m", "1h", "today", "7d"] as const;
const CUSTOMERS = ["NEW", "RETURNING", "WITH_ORDERS", "NO_ORDERS"] as const;

function toggle<T>(list: T[], v: T) { return list.includes(v) ? list.filter((x) => x !== v) : [...list, v]; }

function Chip({ on, onClick, children }: { on: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button type="button" onClick={onClick} aria-pressed={on}
      className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 text-[12.5px] font-medium ring-1 transition active:scale-[.97] ${on ? "bg-ink text-surface ring-ink" : "bg-surface text-ink-2 ring-line-strong hover:ring-faint"}`}>
      {children}{on && <Icon name="check" className="h-3 w-3" />}
    </button>
  );
}

/**
 * The filter groups: several values inside a group mean "any of them" (OR), different groups must all match (AND). Each change applies at once (the list shows the count).
 */
export function FilterPanel({ value, onChange, total }: { value: Filters; onChange: (f: Filters) => void; total: number }) {
  const { t, locale } = useI18n();
  const group = (title: string, children: React.ReactNode) => (
    <section className="py-3 first:pt-1">
      <p className="mb-2 text-[11.5px] font-semibold uppercase tracking-wide text-faint">{title}</p>
      <div className="flex flex-wrap gap-1.5">{children}</div>
    </section>
  );
  return (
    <div className="divide-y divide-line">
      {group(t("inbox.f.status"), STATUSES.map((s) => (
        <Chip key={s} on={value.status.includes(s)} onClick={() => onChange({ ...value, status: toggle(value.status, s) })}>
          <span className="h-2 w-2 rounded-full" style={{ background: STATUS_STYLE[s].dot }} />{t(`inbox.st.${s}`)}
        </Chip>
      )))}
      {group(t("inbox.f.ai"), (["ACTIVE", "PAUSED"] as const).map((a) => (
        <Chip key={a} on={value.ai.includes(a)} onClick={() => onChange({ ...value, ai: toggle(value.ai, a) })}>
          <Icon name={a === "ACTIVE" ? "sparkles" : "minus"} className="h-3.5 w-3.5" />{t(`inbox.ai.${a}`)}
        </Chip>
      )))}
      {group(t("inbox.f.channel"), CHANNELS.map((c) => (
        <Chip key={c} on={value.channel.includes(c)} onClick={() => onChange({ ...value, channel: toggle(value.channel, c) })}>
          <ChannelIcon channel={c} className="h-3.5 w-3.5" />{t(`inbox.ch.${c}`)}
        </Chip>
      )))}
      {group(t("inbox.f.time"), TIMES.map((x) => (
        <Chip key={x} on={value.since === x} onClick={() => onChange({ ...value, since: value.since === x ? "" : x })}>{t(`inbox.time.${x}`)}</Chip>
      )))}
      {group(t("inbox.f.customer"), CUSTOMERS.map((c) => (
        <Chip key={c} on={value.customer.includes(c)} onClick={() => onChange({ ...value, customer: toggle(value.customer, c) })}>{t(`inbox.cu.${c}`)}</Chip>
      )))}
      <div className="flex items-center justify-between gap-3 pt-3">
        <span className="text-[12.5px] text-muted tnum">{t("inbox.matching", { n: total.toLocaleString(locale === "ar" ? "ar-EG" : "en-US") })}</span>
        <button type="button" onClick={() => onChange({ ...EMPTY, q: value.q, sort: value.sort })} className="text-[12.5px] font-semibold text-brand hover:underline">{t("inbox.clearAll")}</button>
      </div>
    </div>
  );
}

/** The chosen filters as removable chips above the list. */
export function ActiveChips({ value, onChange }: { value: Filters; onChange: (f: Filters) => void }) {
  const { t } = useI18n();
  const chips: { key: string; label: React.ReactNode; drop: () => void }[] = [
    ...value.status.map((s) => ({ key: `s${s}`, label: t(`inbox.st.${s}`), drop: () => onChange({ ...value, status: value.status.filter((x) => x !== s) }) })),
    ...value.ai.map((a) => ({ key: `a${a}`, label: t(`inbox.ai.${a}`), drop: () => onChange({ ...value, ai: value.ai.filter((x) => x !== a) }) })),
    ...value.channel.map((c) => ({ key: `c${c}`, label: <><ChannelIcon channel={c} className="h-3 w-3" />{t(`inbox.ch.${c}`)}</>, drop: () => onChange({ ...value, channel: value.channel.filter((x) => x !== c) }) })),
    ...(value.since ? [{ key: "t", label: t(`inbox.time.${value.since}`), drop: () => onChange({ ...value, since: "" as const }) }] : []),
    ...value.customer.map((c) => ({ key: `u${c}`, label: t(`inbox.cu.${c}`), drop: () => onChange({ ...value, customer: value.customer.filter((x) => x !== c) }) })),
  ];
  if (chips.length === 0) return null;
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      {chips.map((c) => (
        <span key={c.key} className="inline-flex animate-scale-in items-center gap-1 rounded-full bg-brand-soft py-0.5 pe-1 ps-2.5 text-[11.5px] font-semibold text-brand">
          {c.label}
          <button type="button" onClick={c.drop} aria-label={t("common.remove")} className="grid h-4 w-4 place-items-center rounded-full hover:bg-brand/15"><Icon name="close" className="h-3 w-3" /></button>
        </span>
      ))}
      <button type="button" onClick={() => onChange({ ...EMPTY, q: value.q, sort: value.sort })} className="px-1 text-[11.5px] font-semibold text-muted hover:text-ink">{t("inbox.clearAll")}</button>
    </div>
  );
}
