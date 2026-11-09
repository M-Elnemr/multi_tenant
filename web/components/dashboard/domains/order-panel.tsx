"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { api, ApiError } from "@/lib/client";
import { dateTime, money } from "@/lib/format";
import { useAction, useApi } from "../../hooks";
import { useI18n } from "../../i18n-provider";
import { ToggleRow } from "../kit";
import { ConfirmDialog, Switch } from "../../overlay";
import { Alert, Button, ErrorText, Field, SecretBox, Select, Skeleton, Timeline } from "../../ui";
import { useRegistrantContacts } from "./registrant-contacts";
import { FORWARD_STATUSES, WALLET_TOPUP_LINK, isForward, isTerminal, type OrderDetail, type OrderListItem, type OrderStatus } from "./types";

const POLL_MS = 15_000;
type Pos = "done" | "current" | "pending";

/** Where a forward step stands: `pending/current/done` while the order is still moving, frozen at the last reached
 *  step once it is terminal OR reports a status this UI doesn't recognize (never claims a later step happened on a guess). */
function stepState(order: OrderDetail, pos: number): Pos {
  const idx = FORWARD_STATUSES.indexOf(order.status as (typeof FORWARD_STATUSES)[number]);
  if (idx < 0) {
    const last = [...order.events].reverse().find((e) => isForward(e.status));
    const lastIdx = last ? FORWARD_STATUSES.indexOf(last.status as (typeof FORWARD_STATUSES)[number]) : -1;
    return pos <= lastIdx ? "done" : "pending";
  }
  return pos < idx ? "done" : pos === idx ? "current" : "pending";
}

const toneOf = (status: string) => (isTerminal(status as OrderStatus) ? "red" : status === "LIVE" ? "green" : status === "PENDING_PAYMENT" ? "slate" : "blue");

/** Registrant contact exactly as it was sent to the registrar for this order — plain and complete, never hidden. */
function RegistrantSection({ contactId }: { contactId: string }) {
  const { t } = useI18n();
  const contacts = useRegistrantContacts();
  const c = contacts.data?.find((x) => x.id === contactId);
  if (!contacts.data) return <Skeleton className="h-24 w-full !rounded-[14px]" />;
  if (!c) return null;
  const rows: [string, string | null | undefined][] = [
    [t("domainBuy.contact.name"), c.name], [t("domainBuy.contact.org"), c.org], [t("domainBuy.contact.email"), c.email],
    [t("domainBuy.contact.phone"), c.phone], [t("domainBuy.contact.country"), c.country], [t("domainBuy.contact.address"), c.address],
  ];
  return (
    <div>
      <p className="mb-1 text-[13.5px] font-semibold text-ink">{t("domainBuy.contact.title")}</p>
      <p className="mb-2.5 text-[12px] text-muted">{t("domainBuy.registrantHint")}</p>
      <dl className="grid gap-2.5 rounded-[14px] bg-canvas p-4 ring-1 ring-line sm:grid-cols-2">
        {rows.map(([label, value]) => (
          <div key={label} className="min-w-0">
            <dt className="text-[11px] font-semibold uppercase tracking-wide text-faint">{label}</dt>
            <dd className="truncate text-[13px] font-medium text-ink">{value || "—"}</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}

/** A working transfer-out: the EPP/auth code that moves this domain to any other registrar — never withheld. */
function TransferOutSection({ orderId }: { orderId: string }) {
  const { t } = useI18n();
  const [code, setCode] = useState<string | null>(null);
  const get = useAction(async () => {
    const res = await api<Record<string, unknown>>(`store/domains/orders/${orderId}/transfer-auth-code`, { body: {} });
    const value = (res.code ?? res.authCode ?? res.eppCode ?? res.epp) as string | undefined;
    setCode(value ?? null);
  });
  return (
    <div className="rounded-[14px] bg-canvas p-4 ring-1 ring-line">
      <p className="mb-1 text-[13.5px] font-semibold text-ink">{t("domainBuy.transferTitle")}</p>
      <p className="mb-3 text-[12.5px] leading-relaxed text-muted">{t("domainBuy.transferBody")}</p>
      {code ? <SecretBox label={t("domainBuy.transferCodeLabel")} value={code} /> : <Button size="sm" variant="secondary" loading={get.loading} onClick={() => void get.run()}>{t("domainBuy.transferCodeGet")}</Button>}
      <ErrorText error={get.error} />
    </div>
  );
}

/**
 * One purchased domain's progress: Paid → Registered → DNS configured → Verified → SSL → Live, its full history,
 * and (once live) the registrant on file, a transfer-out code, an opt-in auto-renew toggle, and a manual renew.
 * Polls the order every 15s exactly like `domain-setup/setup-panel.tsx` polls `check` — same interval shape, cleaned
 * up on unmount — and never marks a step done, or an order failed/succeeded, on a guess: an order status this UI
 * doesn't recognize (e.g. the registrar call is still being confirmed) is shown as "still confirming", not broken.
 */
export function DomainOrderPanel({ orderId, sslStatus, onLive }: { orderId: string; sslStatus?: string; onLive?: () => void }) {
  const { t, locale, currency } = useI18n();
  const { data: order, reload, error } = useApi<OrderDetail>(`store/domains/orders/${orderId}`);
  const wasLive = useRef(false);

  useEffect(() => {
    if (order?.status === "LIVE" && !wasLive.current) { wasLive.current = true; onLive?.(); }
  }, [order?.status, onLive]);

  useEffect(() => {
    if (!order || order.status === "LIVE" || isTerminal(order.status)) return;
    const timer = setInterval(() => void reload(), POLL_MS);
    return () => clearInterval(timer);
  }, [order, reload]);

  const wallet = useApi<{ balanceMinor: number }>(order?.status === "LIVE" ? "store/ai/whatsapp" : null);
  const [years, setYears] = useState(1);
  const renew = useAction(async () => { await api<OrderListItem>(`store/domains/orders/${orderId}/renew`, { body: { years } }); await reload(); });

  const [confirmAutoRenew, setConfirmAutoRenew] = useState(false);
  const autoRenew = useAction(async (enabled: boolean) => { await api(`store/domains/orders/${orderId}/auto-renew`, { method: "PUT", body: { enabled } }); await reload(); });

  if (!order) return <ErrorText error={error} />;

  const live = order.status === "LIVE";
  const unclear = !live && !isTerminal(order.status) && !isForward(order.status) && order.status !== "PENDING_PAYMENT";
  const steps: { label: string; state: Pos }[] = [
    { label: t("domainBuy.step.paid"), state: stepState(order, 0) },
    { label: t("domainBuy.step.registered"), state: stepState(order, 1) },
    { label: t("domainBuy.step.dns"), state: stepState(order, 2) },
    { label: t("domainBuy.step.verified"), state: stepState(order, 3) },
    { label: t("domainBuy.step.ssl"), state: live ? "done" : "pending" },
    { label: t("domainBuy.step.live"), state: live ? "done" : "pending" },
  ];
  const renewBalance = wallet.data?.balanceMinor ?? 0;
  const noCredit = renew.error instanceof ApiError && renew.error.code === "NO_CREDIT";
  const lastNote = order.events[order.events.length - 1]?.note;
  const yearlyPrice = money(order.years > 0 ? Math.round(order.priceMinor / order.years) : order.priceMinor, order.currency ?? currency, locale);

  return (
    <div className="space-y-4">
      <ol className="grid gap-2 sm:grid-cols-3 lg:grid-cols-6">
        {steps.map((s, i) => (
          <li key={i} className={`flex items-center gap-2 rounded-xl border p-2.5 text-[12.5px] font-semibold ${s.state === "current" ? "border-brand bg-brand-soft text-brand-dark" : s.state === "done" ? "border-emerald-200 bg-emerald-50 text-emerald-700" : "border-line bg-canvas text-faint"}`}>
            <span className={`flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-[11px] font-bold ${s.state === "current" ? "bg-brand text-white" : s.state === "done" ? "bg-emerald-500 text-white" : "bg-line-strong text-muted"}`}>{s.state === "done" ? "✓" : i + 1}</span>
            {s.label}
          </li>
        ))}
      </ol>

      {isTerminal(order.status) && <Alert tone="red">{t(`domainBuy.orderStatus.${order.status}`)}{lastNote ? ` — ${lastNote}` : ""}</Alert>}
      {unclear && <Alert tone="amber">{t("domainBuy.confirming")}{lastNote ? ` — ${lastNote}` : ""}</Alert>}
      {!live && !isTerminal(order.status) && !unclear && lastNote && <p className="text-[12px] text-muted">{lastNote}</p>}
      {live && <p className="text-[12px] text-muted">{t("domainBuy.sslHint")}{sslStatus ? ` (${sslStatus})` : ""}</p>}

      {live && order.registrantContactId && <RegistrantSection contactId={order.registrantContactId} />}
      {live && <TransferOutSection orderId={orderId} />}

      {live && (
        <div className="rounded-[14px] bg-canvas p-4 ring-1 ring-line">
          <p className="mb-1 text-[13.5px] font-semibold text-ink">{t("domainBuy.renewTitle")}</p>
          <div className="flex flex-wrap items-end gap-3">
            <Field label={t("domainBuy.years")} className="w-32">
              <Select value={years} onChange={(e) => setYears(Number(e.target.value))}>{[1, 2, 3, 4, 5].map((y) => <option key={y} value={y}>{t("domainBuy.yearsN", { n: y })}</option>)}</Select>
            </Field>
            <Button size="sm" loading={renew.loading} onClick={() => void renew.run()}>{t("domainBuy.renewNow")}</Button>
            <span className="text-[12px] text-muted">{t("domainBuy.walletBalance")}: <span className="tnum">{money(renewBalance, currency, locale)}</span></span>
          </div>
          <ErrorText error={renew.error} />
          {noCredit && <Link href={WALLET_TOPUP_LINK} className="text-[12.5px] font-semibold text-brand underline">{t("domainBuy.topUp")}</Link>}

          <div className="mt-3 border-t border-line pt-3">
            <ToggleRow icon="clock" title={t("domainBuy.autoRenewTitle")} hint={t("domainBuy.autoRenewHint", { price: yearlyPrice })}
              control={<Switch checked={!!order.autoRenew} disabled={autoRenew.loading} label={t("domainBuy.autoRenewTitle")}
                onChange={(v) => { if (v) setConfirmAutoRenew(true); else void autoRenew.run(false); }} />} />
            <ErrorText error={autoRenew.error} />
          </div>
        </div>
      )}

      <div>
        <p className="mb-2 text-[11.5px] font-semibold uppercase tracking-wide text-faint">{t("domainBuy.history")}</p>
        <Timeline items={order.events.map((e) => ({ key: e.id, time: dateTime(e.at, locale), title: t(`domainBuy.orderStatus.${e.status}`), meta: e.note ?? undefined, tone: toneOf(e.status) }))} />
      </div>

      <ConfirmDialog open={confirmAutoRenew} onClose={() => setConfirmAutoRenew(false)} loading={autoRenew.loading}
        onConfirm={() => { void autoRenew.run(true).then(() => setConfirmAutoRenew(false)); }}
        title={t("domainBuy.autoRenewConfirmTitle")} body={t("domainBuy.autoRenewConfirmBody", { price: yearlyPrice })}
        confirmLabel={t("domainBuy.autoRenewTitle")} cancelLabel={t("common.cancel")} />
    </div>
  );
}
