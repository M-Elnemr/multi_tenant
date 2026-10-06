"use client";

import Link from "next/link";
import { use } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Button, Card, ErrorText, Loading, PageHeader, StatusBadge } from "@/components/ui";
import { dateTime, money } from "@/lib/format";

export type OrderDetail = {
  id: string; orderNumber: string; status: string; paymentStatus: string; paymentMethod: string; currency: string; subtotalMinor: number; discountMinor: number; shippingMinor: number; totalMinor: number; createdAt: string;
  items: { productNameSnapshot: string; variantNameSnapshot?: string; unitPriceMinor: number; quantity: number; lineTotalMinor: number }[];
  history: { fromStatus?: string; newStatus: string; reason?: string; createdAt: string }[];
  shippingAddress: Record<string, string>;
};

export default function OrderPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { t, locale } = useI18n();
  const { data, loading, error, reload } = useApi<OrderDetail>(`shop/orders/${id}`);
  const cancel = useAction(async () => { await api(`shop/orders/${id}/cancel`, { body: {} }); await reload(); });
  if (loading && !data) return <Loading />;
  if (!data) return <ErrorText error={error} />;
  const canCancel = ["PENDING", "CONFIRMED"].includes(data.status);
  return (
    <>
      <PageHeader title={`${t("orders.order")} ${data.orderNumber}`} subtitle={dateTime(data.createdAt, locale)} actions={<><StatusBadge status={data.status} /><StatusBadge status={data.paymentStatus} /></>} />
      {data.status === "PENDING" && data.paymentMethod === "CARD" && data.paymentStatus === "UNPAID" && (
        <div className="mb-4"><Alert tone="amber">{t("orders.awaitingPayment")} <Link className="font-medium underline" href={`/checkout/pay?kind=order&id=${data.id}&amount=${data.totalMinor}`}>{t("orders.payNow")}</Link></Alert></div>
      )}
      <div className="grid gap-6 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <ul className="divide-y">
            {data.items.map((i, k) => (
              <li key={k} className="flex justify-between gap-3 py-3 text-sm">
                <div><p className="font-medium">{i.productNameSnapshot}</p>{i.variantNameSnapshot && <p className="text-slate-500">{i.variantNameSnapshot}</p>}<p className="text-slate-500">{i.quantity} × {money(i.unitPriceMinor, data.currency, locale)}</p></div>
                <b>{money(i.lineTotalMinor, data.currency, locale)}</b>
              </li>
            ))}
          </ul>
          <dl className="mt-3 space-y-1 border-t pt-3 text-sm">
            <div className="flex justify-between"><dt>{t("checkout.subtotal")}</dt><dd>{money(data.subtotalMinor, data.currency, locale)}</dd></div>
            {data.discountMinor > 0 && <div className="flex justify-between"><dt>{t("checkout.discount")}</dt><dd>−{money(data.discountMinor, data.currency, locale)}</dd></div>}
            <div className="flex justify-between"><dt>{t("checkout.shippingFee")}</dt><dd>{money(data.shippingMinor, data.currency, locale)}</dd></div>
            <div className="flex justify-between text-base font-bold"><dt>{t("checkout.total")}</dt><dd>{money(data.totalMinor, data.currency, locale)}</dd></div>
          </dl>
        </Card>
        <div className="space-y-4">
          <Card>
            <h2 className="mb-2 font-medium">{t("orders.timeline")}</h2>
            <ol className="space-y-2 text-sm">{data.history.map((h, i) => <li key={i}><StatusBadge status={h.newStatus} /> <span className="text-xs text-slate-500">{dateTime(h.createdAt, locale)}</span></li>)}</ol>
          </Card>
          {data.shippingAddress?.addressLine1 && <Card className="text-sm"><h2 className="mb-1 font-medium">{t("checkout.address")}</h2><p>{data.shippingAddress.recipientName}</p><p>{data.shippingAddress.addressLine1}, {data.shippingAddress.city}</p><p dir="ltr">{data.shippingAddress.phone}</p></Card>}
          <ErrorText error={cancel.error} />
          {canCancel && <Button variant="danger" className="w-full" loading={cancel.loading} onClick={() => cancel.run()}>{t("orders.cancel")}</Button>}
        </div>
      </div>
    </>
  );
}
