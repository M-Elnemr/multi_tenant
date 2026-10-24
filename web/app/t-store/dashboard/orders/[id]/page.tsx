"use client";

import { localPhone } from "@/lib/phone";
import { use, useState } from "react";
import { api } from "@/lib/client";
import { toMinor } from "@/lib/format";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Button, Card, ErrorText, Field, Input, Loading, Modal, PageHeader, StatusBadge } from "@/components/ui";
import { ShipModal } from "@/components/dashboard/shipping/ship-modal";
import { WhatsAppButton } from "@/components/whatsapp-button";
import { ConfirmCard, TrackingCard } from "@/components/dashboard/order-actions";
import { governorateName } from "@/lib/governorates";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { dateTime, money } from "@/lib/format";

type Detail = {
  id: string; orderNumber: string; status: string; paymentStatus: string; paymentMethod: string; currency: string; subtotalMinor: number; discountMinor: number; shippingMinor: number; totalMinor: number; customerNameSnapshot: string; customerPhoneSnapshot: string; whatsappNumber?: string | null; whatsappConfirmedAt?: string | null; notes?: string; createdAt: string;
  items: { productNameSnapshot: string; variantNameSnapshot?: string; sku_snapshot?: string; skuSnapshot?: string; unitPriceMinor: number; quantity: number; lineTotalMinor: number }[];
  history: { newStatus: string; reason?: string; createdAt: string }[];
  payments: { id: string; kind: string; method: string; amountMinor: number; status: string }[];
  shippingAddress: Record<string, string>;
  governorateCode?: string; area?: string; landmark?: string; phone2?: string; codFeeMinor?: number; confirmedAt?: string | null; courierName?: string; trackingNumber?: string; trackingUrl?: string;
  etaMinDays?: number; etaMaxDays?: number;
  riskScore?: number; riskLevel?: "LOW" | "MEDIUM" | "HIGH" | null; riskReasons?: string; needsConfirmation?: boolean;
  customerHistory?: { deliveredCount: number; returnedCount: number; cancelledCount: number; blocked: boolean };
};

/** Requested -> preparing to ship -> shipped -> arrived (a shipped or arrived order can come back as returned). */
const NEXT: Record<string, string[]> = {
  PENDING: ["REQUESTED", "CANCELLED"], REQUESTED: ["PREPARING", "CANCELLED"], PREPARING: ["SHIPPED", "CANCELLED"], SHIPPED: ["ARRIVED", "RETURNED"], ARRIVED: ["RETURNED"],
};

export default function StoreOrder({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { t, locale } = useI18n();
  const { can } = useMe();
  const { data, loading, error, reload } = useApi<Detail>(`store/orders/${id}`);
  const shop = useApi<{ storeName: string }>(can("settings.manage") ? "store/profile" : null);
  const blockPhone = useAction(async (blocked: boolean) => { await api("store/phone-flags", { body: { phone: data?.customerPhoneSnapshot, blocked } }); await reload(); });
  const router = useRouter();
  const taxDoc = useAction(async (kind: "invoice" | "credit-note") => { const r = await api<{ id: string }>(`store/orders/${id}/${kind}`, { body: {} }); router.push(`/dashboard/invoices/${r.id}`); });
  const [refundOpen, setRefundOpen] = useState(false);
  const [shipOpen, setShipOpen] = useState(false);
  const [amount, setAmount] = useState("");
  const move = useAction(async (status: string) => { await api(`store/orders/${id}/status`, { body: { status } }); await reload(); });
  const refund = useAction(async () => { await api(`store/orders/${id}/refund`, { body: { amountMinor: toMinor(amount) } }); setRefundOpen(false); await reload(); });
  if (loading && !data) return <Loading />;
  if (!data) return <ErrorText error={error} />;
  const paid = data.payments.filter((p) => p.kind === "PAYMENT" && p.status === "SUCCEEDED").reduce((n, p) => n + p.amountMinor, 0);
  const refunded = data.payments.filter((p) => p.kind === "REFUND").reduce((n, p) => n + p.amountMinor, 0);
  return (
    <>
      <PageHeader title={`${t("orders.order")} ${data.orderNumber}`} subtitle={dateTime(data.createdAt, locale)} actions={<><StatusBadge status={data.status} /><StatusBadge status={data.paymentStatus} /></>} />
      <ErrorText error={move.error ?? taxDoc.error} />
      <div className="mb-4 flex flex-wrap items-center gap-2 print:hidden">
        {can("invoice.manage") && data.status !== "CANCELLED" && data.status !== "PENDING" && <button type="button" onClick={() => void taxDoc.run("invoice")} className="rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-sm font-semibold hover:bg-slate-50">📑 {t("inv.taxInvoice")}</button>}
        {can("invoice.manage") && ["RETURNED", "REFUNDED", "CANCELLED"].includes(data.status) && <button type="button" onClick={() => void taxDoc.run("credit-note")} className="rounded-xl border border-amber-300 bg-amber-50 px-3 py-1.5 text-sm font-semibold hover:bg-amber-100">↩️ {t("inv.creditNote")}</button>}
        <Link href={`/dashboard/orders/${id}/print?doc=invoice`} className="rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-sm font-semibold hover:bg-slate-50">🧾 {t("print.invoice")}</Link>
        <Link href={`/dashboard/orders/${id}/print?doc=label`} className="rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-sm font-semibold hover:bg-slate-50">🏷️ {t("print.label")}</Link>
      </div>
      {(data.riskLevel === "HIGH" || data.riskLevel === "MEDIUM") && data.paymentMethod === "CASH_ON_DELIVERY" && (
        <div className={`mb-4 rounded-2xl border p-4 text-sm ${data.riskLevel === "HIGH" ? "border-red-200 bg-red-50 text-red-800" : "border-amber-200 bg-amber-50 text-amber-900"}`}>
          <div className="font-bold">{data.riskLevel === "HIGH" ? "⚠️ " + t("risk.highTitle") : t("risk.mediumTitle")} ({data.riskScore}/100)</div>
          <ul className="mt-1 list-disc ps-5">{(data.riskReasons ?? "").split(",").filter(Boolean).map((r) => <li key={r}>{t(`risk.r.${r}`)}</li>)}</ul>
          {data.needsConfirmation && !data.confirmedAt && <p className="mt-2 font-semibold">{t("risk.callFirst")}</p>}
        </div>
      )}
      {data.customerHistory && (data.customerHistory.returnedCount > 0 || data.customerHistory.cancelledCount > 1 || data.customerHistory.blocked) && (
        <div className="mb-4 rounded-2xl border border-red-200 bg-red-50 p-4 text-sm text-red-800">⚠️ {t("history.warn", { d: data.customerHistory.deliveredCount, r: data.customerHistory.returnedCount, c: data.customerHistory.cancelledCount })}
          {can("order.update_status") && <Button size="sm" variant={data.customerHistory.blocked ? "secondary" : "danger"} className="ms-3" loading={blockPhone.loading} onClick={() => blockPhone.run(!data.customerHistory!.blocked)}>{data.customerHistory.blocked ? t("history.unblock") : t("history.block")}</Button>}</div>
      )}
      {can("order.update_status") && <div className="mb-4 space-y-4"><ConfirmCard o={data} reload={reload} storeName={shop.data?.storeName ?? ""} />{["PREPARING", "SHIPPED", "ARRIVED"].includes(data.status) && <TrackingCard key={`${data.courierName}${data.trackingNumber}`} o={data} reload={reload} />}</div>}
      {can("order.update_status") && (NEXT[data.status] ?? []).length > 0 && (
        <div className="mb-4 flex flex-wrap gap-2">{["REQUESTED", "PREPARING"].includes(data.status) && <Button size="sm" variant="secondary" onClick={() => setShipOpen(true)}>🚚 {t("ship.shipWith")}</Button>}{NEXT[data.status].map((s) => <Button key={s} variant={s === "CANCELLED" ? "danger" : "primary"} size="sm" loading={move.loading} onClick={() => move.run(s)}>{t(`orders.to.${s}`)}</Button>)}</div>
      )}
      <div className="grid gap-6 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <ul className="divide-y text-sm">{data.items.map((i, k) => (
            <li key={k} className="flex justify-between gap-3 py-3"><div><p className="font-medium">{i.productNameSnapshot}</p><p className="text-slate-500">{i.variantNameSnapshot} · {i.skuSnapshot ?? i.sku_snapshot}</p><p className="text-slate-500">{i.quantity} × {money(i.unitPriceMinor, data.currency, locale)}</p></div><b>{money(i.lineTotalMinor, data.currency, locale)}</b></li>
          ))}</ul>
          <dl className="mt-3 space-y-1 border-t pt-3 text-sm">
            <div className="flex justify-between"><dt>{t("checkout.subtotal")}</dt><dd>{money(data.subtotalMinor, data.currency, locale)}</dd></div>
            {data.discountMinor > 0 && <div className="flex justify-between"><dt>{t("checkout.discount")}</dt><dd>−{money(data.discountMinor, data.currency, locale)}</dd></div>}
            <div className="flex justify-between"><dt>{t("checkout.shippingFee")}</dt><dd>{money(data.shippingMinor, data.currency, locale)}</dd></div>
            {!!data.codFeeMinor && <div className="flex justify-between"><dt>{t("checkout.codFee")}</dt><dd>{money(data.codFeeMinor, data.currency, locale)}</dd></div>}
            <div className="flex justify-between text-base font-bold"><dt>{t("checkout.total")}</dt><dd>{money(data.totalMinor, data.currency, locale)}</dd></div>
          </dl>
        </Card>
        <div className="space-y-4">
          <Card className="text-sm"><h2 className="mb-1 font-medium">{t("orders.customer")}</h2><p>{data.customerNameSnapshot}</p><p className="flex items-center gap-2" dir="ltr"><a href={`tel:${data.customerPhoneSnapshot}`} className="text-brand">{localPhone(data.customerPhoneSnapshot)}</a><WhatsAppButton phone={data.customerPhoneSnapshot} message={t("orders.waHello", { name: data.customerNameSnapshot, number: data.orderNumber })} size={28} /></p>{data.whatsappNumber && <p className="mt-1 text-xs text-emerald-700" dir="ltr">WhatsApp {data.whatsappNumber}{data.whatsappConfirmedAt ? <span className="ms-2 rounded-full bg-emerald-100 px-2 py-0.5 font-semibold">✓ {t("orders.waConfirmed")}</span> : null}</p>}{data.shippingAddress?.addressLine1 && <p className="mt-2 text-slate-600">{[data.shippingAddress.addressLine1, data.area, data.shippingAddress.city, governorateName(data.governorateCode, locale)].filter(Boolean).join("، ")}</p>}{data.landmark && <p className="text-slate-600">📍 {data.landmark}</p>}{data.phone2 && <p dir="ltr" className="text-slate-600">☎ {data.phone2}</p>}{data.etaMinDays != null && <p className="mt-1 text-xs text-slate-500">🚚 {t("checkout.eta", { min: data.etaMinDays, max: data.etaMaxDays ?? data.etaMinDays })}</p>}{data.notes && <p className="mt-2 italic">{data.notes}</p>}</Card>
          <Card className="text-sm">
            <h2 className="mb-1 font-medium">{t("orders.payment")}</h2>
            <p>{t(`pay.${data.paymentMethod}`)} · {money(paid, data.currency, locale)}{refunded > 0 && <> · {t("orders.refunded")} {money(refunded, data.currency, locale)}</>}</p>
            {can("refund.create") && paid - refunded > 0 && <Button size="sm" variant="secondary" className="mt-2" onClick={() => { setAmount(((paid - refunded) / 100).toFixed(2)); setRefundOpen(true); }}>{t("orders.refund")}</Button>}
          </Card>
          <Card><h2 className="mb-2 text-sm font-medium">{t("orders.timeline")}</h2><ol className="space-y-1.5 text-sm">{data.history.map((h, i) => <li key={i}><StatusBadge status={h.newStatus} /> <span className="text-xs text-slate-500">{dateTime(h.createdAt, locale)}</span></li>)}</ol></Card>
        </div>
      </div>
      {shipOpen && <ShipModal orderId={id} onClose={() => setShipOpen(false)} onDone={() => { setShipOpen(false); void reload(); }} />}
      <Modal open={refundOpen} onClose={() => setRefundOpen(false)} title={t("orders.refund")}>
        <div className="space-y-3">
          <Field label={t("billing.amount")}><Input value={amount} onChange={(e) => setAmount(e.target.value)} inputMode="decimal" dir="ltr" /></Field>
          <ErrorText error={refund.error} />
          <Button loading={refund.loading} onClick={() => refund.run()} className="w-full">{t("orders.refund")}</Button>
        </div>
      </Modal>
    </>
  );
}
