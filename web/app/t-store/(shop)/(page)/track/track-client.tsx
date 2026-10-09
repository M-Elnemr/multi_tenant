"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/client";
import { money } from "@/lib/format";
import { governorateName } from "@/lib/governorates";
import { useAction } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { PhoneInput } from "@/components/inputs";
import { ErrorText } from "@/components/ui";

type Tracked = {
  orderNumber: string; status: string; currency: string; totalMinor: number; shippingMinor: number; codFeeMinor: number; subtotalMinor: number; discountMinor: number;
  courierName?: string; trackingNumber?: string; trackingUrl?: string; governorateCode?: string; etaMinDays?: number; etaMaxDays?: number; createdAt: string; deliveredAt?: string;
  confirmed: boolean; canReturn: boolean; returnRequest?: { status: string; reason: string } | null;
  items: { productNameSnapshot: string; variantNameSnapshot?: string; quantity: number; lineTotalMinor: number }[]; history: { newStatus: string; createdAt: string }[];
};

const STEPS = ["REQUESTED", "PREPARING", "SHIPPED", "ARRIVED"];
const REASONS = ["DEFECTIVE", "WRONG_ITEM", "NOT_AS_DESCRIBED", "SIZE", "CHANGED_MIND", "OTHER"];

/** Guests (and clients) follow an order with just the order number and the phone it was placed with. */
export function TrackClient({ initialNumber, initialPhone }: { initialNumber: string; initialPhone: string }) {
  const { t, locale } = useI18n();
  const [number, setNumber] = useState(initialNumber);
  const [phone, setPhone] = useState(initialPhone);
  const [order, setOrder] = useState<Tracked | null>(null);
  const [reason, setReason] = useState("SIZE");
  const [details, setDetails] = useState("");
  const [returning, setReturning] = useState(false);
  const find = useAction(async () => { setOrder(await api<Tracked>("shop/track", { body: { orderNumber: number.trim(), phone } })); });
  const doReturn = useAction(async () => { setOrder(await api<Tracked>("shop/track/return", { body: { orderNumber: number.trim(), phone, reason, details } })); setReturning(false); });
  const auto = useRef(false);
  useEffect(() => { if (!auto.current && initialNumber && initialPhone) { auto.current = true; void find.run(); } }, [initialNumber, initialPhone]); // eslint-disable-line react-hooks/exhaustive-deps

  const idx = order ? STEPS.indexOf(order.status) : -1;
  const closed = order && ["CANCELLED", "RETURNED", "REFUNDED"].includes(order.status);
  const when = (iso: string) => new Date(iso).toLocaleString(locale === "ar" ? "ar-EG" : "en-GB", { dateStyle: "medium", timeStyle: "short" });
  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <div><p className="s-eyebrow">{t("shop.trackOrder")}</p><h1 className="mt-1 text-3xl font-extrabold">{t("track.title")}</h1><p className="mt-1 text-[var(--s-mute)]">{t("track.subtitle")}</p></div>
      <form onSubmit={(e) => { e.preventDefault(); void find.run(); }} className="s-card grid gap-4 p-6 sm:grid-cols-[1fr_1fr_auto] sm:items-end">
        <label className="block space-y-1.5"><span className="text-sm font-bold">{t("track.orderNumber")}</span><input className="s-input" dir="ltr" value={number} onChange={(e) => setNumber(e.target.value)} placeholder="ST-20261009-000001" required /></label>
        <label className="block space-y-1.5"><span className="text-sm font-bold">{t("register.phone")}</span><PhoneInput value={phone} onValue={setPhone} required /></label>
        <button className="s-btn" disabled={find.loading}>{find.loading ? "…" : t("common.search")}</button>
      </form>
      <ErrorText error={find.error ?? doReturn.error} />
      {order && (
        <div className="s-card space-y-6 p-6 sm:p-8">
          <div className="flex flex-wrap items-center justify-between gap-3"><p className="font-mono text-lg font-extrabold" dir="ltr">{order.orderNumber}</p><span className="s-badge">{t(`status.${order.status}`)}</span></div>
          {!closed ? (
            <ol className="grid grid-cols-4 gap-2">
              {STEPS.map((s, i) => (
                <li key={s} className="text-center">
                  <div className={`mx-auto grid h-10 w-10 place-items-center rounded-full text-sm font-extrabold transition ${i <= idx ? "text-white" : "bg-[var(--s-soft)] text-[var(--s-mute)]"}`} style={i <= idx ? { background: "var(--brand)" } : undefined}>{i < idx ? "✓" : i + 1}</div>
                  <p className={`mt-2 text-xs font-bold ${i <= idx ? "" : "text-[var(--s-mute)]"}`}>{t(`status.${s}`)}</p>
                </li>
              ))}
            </ol>
          ) : <p className="rounded-2xl bg-[var(--s-soft)] p-4 text-center font-bold">{t(`status.${order.status}`)}</p>}
          {order.status === "REQUESTED" && !order.confirmed && <p className="rounded-2xl bg-amber-50 p-3 text-sm font-bold text-amber-800">📞 {t("track.willCall")}</p>}
          {(order.courierName || order.trackingNumber) && (
            <div className="rounded-2xl bg-[var(--s-soft)] p-4 text-sm"><p className="font-extrabold">🚚 {t("track.shipment")}</p><p className="mt-1">{order.courierName} {order.trackingNumber && <b dir="ltr">· {order.trackingNumber}</b>}</p>{order.trackingUrl && <a href={order.trackingUrl} target="_blank" rel="noopener noreferrer" className="mt-1 inline-block font-extrabold underline" style={{ color: "var(--brand)" }}>{t("track.followShipment")} →</a>}</div>
          )}
          {order.etaMinDays != null && !closed && order.status !== "ARRIVED" && <p className="text-sm">⏱ {t("checkout.eta", { min: order.etaMinDays, max: order.etaMaxDays ?? order.etaMinDays })} {order.governorateCode && `· ${governorateName(order.governorateCode, locale)}`}</p>}
          <ul className="divide-y divide-[var(--s-line)] text-sm">
            {order.items.map((i, k) => <li key={k} className="flex justify-between gap-3 py-2.5"><span>{i.productNameSnapshot} {i.variantNameSnapshot && <span className="text-[var(--s-mute)]">({i.variantNameSnapshot})</span>} × {i.quantity}</span><b>{money(i.lineTotalMinor, order.currency, locale)}</b></li>)}
            <li className="flex justify-between py-2.5 text-[var(--s-mute)]"><span>{t("checkout.shippingFee")}</span><span>{order.shippingMinor ? money(order.shippingMinor, order.currency, locale) : t("checkout.free")}</span></li>
            {order.codFeeMinor > 0 && <li className="flex justify-between py-2.5 text-[var(--s-mute)]"><span>{t("checkout.codFee")}</span><span>{money(order.codFeeMinor, order.currency, locale)}</span></li>}
            <li className="flex justify-between py-3 text-lg font-extrabold"><span>{t("checkout.total")}</span><span style={{ color: "var(--brand)" }}>{money(order.totalMinor, order.currency, locale)}</span></li>
          </ul>
          <details className="text-sm"><summary className="cursor-pointer font-extrabold">{t("track.history")}</summary><ul className="mt-3 space-y-1.5">{order.history.map((h, k) => <li key={k} className="flex justify-between"><span>{t(`status.${h.newStatus}`)}</span><span className="text-[var(--s-mute)]">{when(h.createdAt)}</span></li>)}</ul></details>
          {order.returnRequest && <p className="rounded-2xl bg-[var(--s-soft)] p-3 text-sm font-bold">↩️ {t("track.returnStatus")}: {t(`return.${order.returnRequest.status}`)}</p>}
          {order.canReturn && !returning && <button onClick={() => setReturning(true)} className="s-btn s-btn-ghost w-full">↩️ {t("track.requestReturn")}</button>}
          {returning && (
            <div className="space-y-3 rounded-2xl border border-[var(--s-line)] p-4">
              <label className="block space-y-1.5"><span className="text-sm font-bold">{t("track.reason")}</span><select className="s-input" value={reason} onChange={(e) => setReason(e.target.value)}>{REASONS.map((r) => <option key={r} value={r}>{t(`reason.${r}`)}</option>)}</select></label>
              <label className="block space-y-1.5"><span className="text-sm font-bold">{t("track.details")}</span><textarea className="s-input" rows={3} value={details} onChange={(e) => setDetails(e.target.value)} maxLength={1000} /></label>
              <button onClick={() => void doReturn.run()} disabled={doReturn.loading} className="s-btn w-full">{t("track.sendReturn")}</button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
