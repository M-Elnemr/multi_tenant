"use client";

import Link from "next/link";
import { use } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { ErrorText, Loading } from "@/components/ui";
import { dateTime, money } from "@/lib/format";
import { governorateName } from "@/lib/governorates";

export type OrderDetail = {
  id: string; orderNumber: string; status: string; paymentStatus: string; paymentMethod: string; currency: string; subtotalMinor: number; discountMinor: number; shippingMinor: number; codFeeMinor?: number; totalMinor: number; createdAt: string;
  customerPhoneSnapshot?: string; governorateCode?: string; area?: string; landmark?: string; courierName?: string; trackingNumber?: string; trackingUrl?: string; etaMinDays?: number; etaMaxDays?: number;
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
  const canCancel = ["PENDING", "REQUESTED"].includes(data.status);
  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div><p className="s-eyebrow">{t("orders.order")}</p><h1 className="font-mono text-3xl font-extrabold" dir="ltr">{data.orderNumber}</h1><p className="text-sm text-[var(--s-mute)]">{dateTime(data.createdAt, locale)}</p></div>
        <span className="s-badge !px-4 !py-1.5 !text-sm">{t(`status.${data.status}`)}</span>
      </div>
      {data.customerPhoneSnapshot && <Link href={`/track?n=${encodeURIComponent(data.orderNumber)}&p=${encodeURIComponent(data.customerPhoneSnapshot)}`} className="s-btn s-btn-ghost w-full sm:w-auto">🚚 {t("shop.trackOrder")}</Link>}
      <div className="grid items-start gap-6 lg:grid-cols-[1fr_22rem]">
        <section className="s-card p-6">
          <ul className="divide-y divide-[var(--s-line)]">
            {data.items.map((i, k) => (
              <li key={k} className="flex justify-between gap-3 py-3 text-sm"><div><p className="font-extrabold">{i.productNameSnapshot}</p>{i.variantNameSnapshot && <p className="text-[var(--s-mute)]">{i.variantNameSnapshot}</p>}<p className="text-[var(--s-mute)]">{i.quantity} × {money(i.unitPriceMinor, data.currency, locale)}</p></div><b>{money(i.lineTotalMinor, data.currency, locale)}</b></li>
            ))}
          </ul>
          <dl className="mt-3 space-y-1.5 border-t border-[var(--s-line)] pt-4 text-sm">
            <div className="flex justify-between"><dt className="text-[var(--s-mute)]">{t("checkout.subtotal")}</dt><dd>{money(data.subtotalMinor, data.currency, locale)}</dd></div>
            {data.discountMinor > 0 && <div className="flex justify-between text-emerald-700"><dt>{t("checkout.discount")}</dt><dd>−{money(data.discountMinor, data.currency, locale)}</dd></div>}
            <div className="flex justify-between"><dt className="text-[var(--s-mute)]">{t("checkout.shippingFee")}</dt><dd>{data.shippingMinor ? money(data.shippingMinor, data.currency, locale) : t("checkout.free")}</dd></div>
            {!!data.codFeeMinor && <div className="flex justify-between"><dt className="text-[var(--s-mute)]">{t("checkout.codFee")}</dt><dd>{money(data.codFeeMinor, data.currency, locale)}</dd></div>}
            <div className="flex justify-between border-t border-[var(--s-line)] pt-3 text-lg font-extrabold"><dt>{t("checkout.total")}</dt><dd style={{ color: "var(--brand)" }}>{money(data.totalMinor, data.currency, locale)}</dd></div>
          </dl>
        </section>
        <div className="space-y-4">
          {(data.courierName || data.trackingNumber) && <div className="s-card space-y-1 p-5 text-sm"><p className="font-extrabold">🚚 {t("track.shipment")}</p><p>{data.courierName} <b dir="ltr">{data.trackingNumber}</b></p>{data.trackingUrl && <a href={data.trackingUrl} target="_blank" rel="noopener noreferrer" className="font-extrabold underline" style={{ color: "var(--brand)" }}>{t("track.followShipment")} →</a>}</div>}
          {data.shippingAddress?.addressLine1 && (
            <div className="s-card p-5 text-sm"><p className="mb-1 font-extrabold">📍 {t("checkout.address")}</p><p>{data.shippingAddress.recipientName}</p><p className="text-[var(--s-mute)]">{[data.shippingAddress.addressLine1, data.area, data.shippingAddress.city, governorateName(data.governorateCode, locale)].filter(Boolean).join("، ")}</p>{data.landmark && <p className="text-[var(--s-mute)]">{data.landmark}</p>}<p dir="ltr" className="text-[var(--s-mute)]">{data.shippingAddress.phone}</p></div>
          )}
          <div className="s-card p-5"><p className="mb-2 font-extrabold">{t("orders.timeline")}</p><ol className="space-y-2 text-sm">{data.history.map((h, i) => <li key={i} className="flex justify-between gap-2"><span className="font-bold">{t(`status.${h.newStatus}`)}</span><span className="text-xs text-[var(--s-mute)]">{dateTime(h.createdAt, locale)}</span></li>)}</ol></div>
          <ErrorText error={cancel.error} />
          {canCancel && <button className="s-btn w-full !bg-red-600" disabled={cancel.loading} onClick={() => cancel.run()}>{t("orders.cancel")}</button>}
        </div>
      </div>
    </div>
  );
}
