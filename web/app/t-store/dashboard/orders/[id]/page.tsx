"use client";

import { use, useState } from "react";
import { api } from "@/lib/client";
import { toMinor } from "@/lib/format";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Button, Card, ErrorText, Field, Input, Loading, Modal, PageHeader, StatusBadge } from "@/components/ui";
import { dateTime, money } from "@/lib/format";

type Detail = {
  id: string; orderNumber: string; status: string; paymentStatus: string; paymentMethod: string; currency: string; subtotalMinor: number; discountMinor: number; shippingMinor: number; totalMinor: number; customerNameSnapshot: string; customerPhoneSnapshot: string; notes?: string; createdAt: string;
  items: { productNameSnapshot: string; variantNameSnapshot?: string; sku_snapshot?: string; skuSnapshot?: string; unitPriceMinor: number; quantity: number; lineTotalMinor: number }[];
  history: { newStatus: string; reason?: string; createdAt: string }[];
  payments: { id: string; kind: string; method: string; amountMinor: number; status: string }[];
  shippingAddress: Record<string, string>;
};

const NEXT: Record<string, string[]> = {
  PENDING: ["CONFIRMED", "CANCELLED"], CONFIRMED: ["PROCESSING", "CANCELLED"], PROCESSING: ["PACKED", "CANCELLED"], PACKED: ["OUT_FOR_DELIVERY", "CANCELLED"],
  OUT_FOR_DELIVERY: ["DELIVERED"], DELIVERED: ["RETURN_REQUESTED"], RETURN_REQUESTED: ["RETURNED", "DELIVERED"],
};

export default function StoreOrder({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { t, locale } = useI18n();
  const { can } = useMe();
  const { data, loading, error, reload } = useApi<Detail>(`store/orders/${id}`);
  const [refundOpen, setRefundOpen] = useState(false);
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
      <ErrorText error={move.error} />
      {can("order.update_status") && (NEXT[data.status] ?? []).length > 0 && (
        <div className="mb-4 flex flex-wrap gap-2">{NEXT[data.status].map((s) => <Button key={s} variant={s === "CANCELLED" ? "danger" : "primary"} size="sm" loading={move.loading} onClick={() => move.run(s)}>{t(`orders.to.${s}`)}</Button>)}</div>
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
            <div className="flex justify-between text-base font-bold"><dt>{t("checkout.total")}</dt><dd>{money(data.totalMinor, data.currency, locale)}</dd></div>
          </dl>
        </Card>
        <div className="space-y-4">
          <Card className="text-sm"><h2 className="mb-1 font-medium">{t("orders.customer")}</h2><p>{data.customerNameSnapshot}</p><p dir="ltr">{data.customerPhoneSnapshot}</p>{data.shippingAddress?.addressLine1 && <p className="mt-2 text-slate-600">{data.shippingAddress.addressLine1}, {data.shippingAddress.city}</p>}{data.notes && <p className="mt-2 italic">{data.notes}</p>}</Card>
          <Card className="text-sm">
            <h2 className="mb-1 font-medium">{t("orders.payment")}</h2>
            <p>{t(`pay.${data.paymentMethod}`)} · {money(paid, data.currency, locale)}{refunded > 0 && <> · {t("orders.refunded")} {money(refunded, data.currency, locale)}</>}</p>
            {can("refund.create") && paid - refunded > 0 && <Button size="sm" variant="secondary" className="mt-2" onClick={() => { setAmount(((paid - refunded) / 100).toFixed(2)); setRefundOpen(true); }}>{t("orders.refund")}</Button>}
          </Card>
          <Card><h2 className="mb-2 text-sm font-medium">{t("orders.timeline")}</h2><ol className="space-y-1.5 text-sm">{data.history.map((h, i) => <li key={i}><StatusBadge status={h.newStatus} /> <span className="text-xs text-slate-500">{dateTime(h.createdAt, locale)}</span></li>)}</ol></Card>
        </div>
      </div>
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
