"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { dateTime } from "@/lib/format";
import { useAction } from "../../hooks";
import { useI18n } from "../../i18n-provider";
import { Icon } from "../../icons";
import { ConfirmDialog } from "../../overlay";
import { Alert, Button, ErrorText, Field, Input } from "../../ui";
import { Panel, PanelHead } from "../marketing/kit";
import type { ManualInfo, ManualPolicy, UnofficialRes } from "./types";

/** Same arithmetic as the backend's HumanPolicy.estimateDays: days a list needs under the warm-up ramp, the hourly cap and the gaps. */
export function estimateDays(count: number, info: ManualInfo | undefined, p: ManualPolicy): number {
  if (count <= 0) return 0;
  const cap = (d: number) => {
    const ramp = p.warmupCaps;
    if (p.maxDaily <= 0 && ramp.length === 0) return Infinity;
    const c = d >= 1 && d <= ramp.length ? ramp[d - 1] : p.maxDaily > 0 ? p.maxDaily : ramp[ramp.length - 1];
    return p.maxDaily > 0 ? Math.min(c, p.maxDaily) : c;
  };
  const window = p.activeFromHour === p.activeToHour ? 24 : (p.activeToHour - p.activeFromHour + 24) % 24;
  const avgGap = Math.round((p.gapMinSeconds + p.gapMaxSeconds) / 2 + p.jitterMaxSeconds / 2);
  const byTime = Math.max(1, Math.floor((window * 3600) / Math.max(1, avgGap)));
  const start = info?.warmupDay ?? 1;
  let left = count, days = 0;
  for (let d = start, guard = 0; left > 0 && guard < 400; d++, guard++) {
    let c = cap(d);
    if (d === start) c = Math.max(0, c - (info?.sentToday ?? 0));
    if (p.hourlyCap > 0) c = Math.min(c, p.hourlyCap * window);
    c = Math.min(c, byTime);
    const today = Math.min(left, c);
    left -= today;
    days += today === 0 ? 1 : left > 0 ? 1 : today / Math.max(1, c);
  }
  return days;
}

/** The plain-language explanation, the estimated duration from the real caps and today's cap / warm-up progress. */
export function ManualSummary({ count, info, policy }: { count: number; info?: ManualInfo; policy: ManualPolicy }) {
  const { t, locale } = useI18n();
  const n = (x: number) => x.toLocaleString(locale === "ar" ? "ar-EG" : "en-US");
  const days = estimateDays(count, info, policy);
  const cap = info?.dailyCap ?? 0;
  const sent = info?.sentToday ?? 0;
  const pct = cap > 0 ? Math.min(100, Math.round((sent / cap) * 100)) : 0;
  const minutes = Math.max(1, Math.round((count * ((policy.gapMinSeconds + policy.gapMaxSeconds) / 2 + policy.jitterMaxSeconds / 2)) / 60));
  return (
    <div className="space-y-2.5 rounded-[14px] bg-amber-50 p-3.5 ring-1 ring-inset ring-amber-600/20">
      <p className="flex items-center gap-1.5 text-[13px] font-semibold text-amber-900"><Icon name="alert" className="h-4 w-4 shrink-0" />{t("mx.explainTitle")}</p>
      <p className="text-[12.5px] leading-relaxed text-amber-900/90">{t("mx.explain")}</p>
      <p className="text-[12.5px] font-semibold text-amber-950">
        {days <= 1 && (cap <= 0 || count <= Math.max(0, cap - sent)) ? t("mx.estimateToday", { n: n(minutes) }) : t("mx.estimateDays", { n: n(Math.ceil(days)), cap: n(Math.max(0, cap - sent)) })}
      </p>
      {cap > 0 && (
        <div className="space-y-1">
          <div className="h-1.5 overflow-hidden rounded-full bg-amber-200/70"><div className="h-full rounded-full bg-amber-600" style={{ width: `${pct}%` }} /></div>
          <p className="text-[12px] text-amber-900/80 tnum">{t("mx.capLine", { sent: n(sent), cap: n(cap), day: n(info?.warmupDay ?? 1) })}</p>
        </div>
      )}
      <p className="text-[12px] text-amber-900/80">{t("mx.riskReminder")}</p>
    </div>
  );
}

/** Connect / pause / wipe the shop's Instagram account for hand-style sending. Shown only when the server has the feature on. The password lives only in this form and is cleared on submit. */
export function ManualCard({ res, onChanged }: { res: UnofficialRes; onChanged: () => void }) {
  const { t, locale } = useI18n();
  const ig = res.platforms.INSTAGRAM;
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [totp, setTotp] = useState("");
  const [risk, setRisk] = useState(false);
  const [confirm, setConfirm] = useState(false);
  const connect = useAction(async () => {
    const body = { username: username.trim(), password, totpSecret: totp.trim() || undefined, acceptedRisk: risk };
    setPassword(""); setTotp("");   // never kept after the request leaves
    await api(`store/unofficial-meta/instagram/connect`, { body });
    setUsername(""); setRisk(false);
    onChanged();
  });
  const post = useAction(async (action: "disable" | "enable") => { await api(`store/unofficial-meta/instagram/${action}`, { body: {} }); onChanged(); });
  const wipe = useAction(async () => { await api(`store/unofficial-meta/instagram`, { method: "DELETE" }); setConfirm(false); onChanged(); });
  if (!ig || !ig.enabled) return null;
  const needsForm = !ig.connected || ig.status === "NEEDS_LOGIN";
  return (
    <Panel className="space-y-4 p-4 sm:p-5">
      <PanelHead icon="link" title={t("mx.cardTitle")} hint={t("mx.cardHint")} />
      {ig.connected && (
        <div className="space-y-2 rounded-[14px] bg-canvas p-3.5 ring-1 ring-line">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-[13.5px] font-semibold text-ink">{t("mx.connected", { name: ig.accountName ?? "" })}</span>
            <span className="rounded-full bg-surface px-2 py-0.5 text-[11.5px] font-semibold text-ink-2 ring-1 ring-line">{t(`mx.status.${ig.status}`)}</span>
          </div>
          {ig.status === "BLOCKED" && ig.blockedUntil && <Alert tone="amber">{t("mx.blockedUntil", { when: dateTime(ig.blockedUntil, locale) })}</Alert>}
          {ig.dailyCap > 0 && <p className="text-[12px] text-muted tnum">{t("mx.capLine", { sent: ig.sentToday.toLocaleString(), cap: ig.dailyCap.toLocaleString(), day: String(ig.warmupDay ?? 1) })}</p>}
          <div className="flex flex-wrap gap-2">
            {ig.status === "ACTIVE" && <Button size="sm" variant="secondary" loading={post.loading} onClick={() => void post.run("disable")}>{t("mx.disable")}</Button>}
            {(ig.status === "DISABLED" || ig.status === "BLOCKED") && <Button size="sm" variant="ink" loading={post.loading} onClick={() => void post.run("enable")}>{t("mx.enable")}</Button>}
            <Button size="sm" variant="ghost" onClick={() => setConfirm(true)}>{t("mx.wipe")}</Button>
          </div>
          <ErrorText error={post.error} />
        </div>
      )}
      {needsForm && (
        <form className="space-y-3" autoComplete="off" onSubmit={(e) => { e.preventDefault(); void connect.run(); }}>
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label={t("mx.username")}><Input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="off" dir="ltr" /></Field>
            <Field label={t("mx.password")}><Input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" dir="ltr" /></Field>
          </div>
          <Field label={t("mx.totp")} hint={t("mx.totpHint")}><Input value={totp} onChange={(e) => setTotp(e.target.value)} autoComplete="off" dir="ltr" /></Field>
          <label className="flex cursor-pointer items-start gap-2.5 rounded-[12px] bg-amber-50 p-3 text-[12.5px] leading-relaxed text-amber-950 ring-1 ring-inset ring-amber-600/20">
            <input type="checkbox" className="mt-0.5 h-4 w-4 shrink-0" checked={risk} onChange={(e) => setRisk(e.target.checked)} />
            <span>{t("mx.risk")}</span>
          </label>
          <ErrorText error={connect.error} />
          <Button type="submit" variant="ink" loading={connect.loading} disabled={!risk || username.trim().length < 2 || password.length < 4}>{t("mx.connect")}</Button>
        </form>
      )}
      <p className="text-[12px] text-muted">{t("mx.messenger")}</p>
      <ConfirmDialog open={confirm} onClose={() => setConfirm(false)} loading={wipe.loading} danger onConfirm={() => void wipe.run()}
        title={t("mx.wipeTitle")} body={t("mx.wipeBody")} confirmLabel={t("mx.wipe")} cancelLabel={t("common.cancel")} />
    </Panel>
  );
}
