"use client";

import { localPhone } from "@/lib/phone";
import { use, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { api } from "@/lib/client";
import { dateTime, money, toMinor } from "@/lib/format";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { ConfirmDialog } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { Button, ErrorText, Field, Input, Loading, Modal, PageHeader, StatusBadge } from "@/components/ui";
import { ShipModal } from "@/components/dashboard/shipping/ship-modal";
import { WhatsAppButton } from "@/components/whatsapp-button";
import { ConfirmCard, TrackingCard } from "@/components/dashboard/order-actions";
import { Block, Facts, LinkButton, MenuButton, Notice, Rise } from "@/components/dashboard/sell/kit";
import { governorateName } from "@/lib/governorates";
import { ChannelBadge } from "@/components/dashboard/channel-badge";

type Detail = {
  channel?: string;
  id: string; orderNumber: string; status: string; paymentStatus: string; paymentMethod: string; currency: string; subtotalMinor: number; discountMinor: number; shippingMinor: number; totalMinor: number; customerNameSnapshot: string; customerPhoneSnapshot: string; whatsappNumber?: string | null; whatsappConfirmedAt?: string | null; notes?: string; createdAt: string;
  items: { productNameSnapshot: string; variantNameSnapshot?: string; sku_snapshot?: string; skuSnapshot?: string; unitPriceMinor: number; quantity: number; lineTotalMinor: number; note?: string }[];
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
const STEPS = ["REQUESTED", "PREPARING", "SHIPPED", "ARRIVED"];

export default function StoreOrder({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { t, locale } = useI18n();
  const toast = useToast();
  const { can } = useMe();
  const { data, loading, error, reload } = useApi<Detail>(`store/orders/${id}`);
  const shop = useApi<{ storeName: string }>(can("settings.manage") ? "store/profile" : null);
  const blockPhone = useAction(async (blocked: boolean) => { await api("store/phone-flags", { body: { phone: data?.customerPhoneSnapshot, blocked } }); toast(t(blocked ? "history.block" : "history.unblock")); await reload(); });
  const router = useRouter();
  const taxDoc = useAction(async (kind: "invoice" | "credit-note") => { const r = await api<{ id: string }>(`store/orders/${id}/${kind}`, { body: {} }); toast(t("sell.docIssued")); router.push(`/dashboard/invoices/${r.id}`); });
  const [refundOpen, setRefundOpen] = useState(false);
  const [shipOpen, setShipOpen] = useState(false);
  const [cancelOpen, setCancelOpen] = useState(false);
  const [amount, setAmount] = useState("");
  const move = useAction(async (status: string) => { await api(`store/orders/${id}/status`, { body: { status } }); toast(t(`orders.to.${status}`)); await reload(); });
  const refund = useAction(async () => { await api(`store/orders/${id}/refund`, { body: { amountMinor: toMinor(amount) } }); setRefundOpen(false); toast(t("orders.refunded")); await reload(); });
  if (loading && !data) return <Loading variant="detail" />;
  if (!data) return <ErrorText error={error} />;
  const paid = data.payments.filter((p) => p.kind === "PAYMENT" && p.status === "SUCCEEDED").reduce((n, p) => n + p.amountMinor, 0);
  const refunded = data.payments.filter((p) => p.kind === "REFUND").reduce((n, p) => n + p.amountMinor, 0);
  const next = can("order.update_status") ? (NEXT[data.status] ?? []) : [];
  const forward = next.filter((s) => s !== "CANCELLED");
  const canCancel = next.includes("CANCELLED");
  const cur = data.currency;
  const m = (v: number) => money(v, cur, locale);
  const stepIdx = STEPS.indexOf(data.status);
  const risky = (data.riskLevel === "HIGH" || data.riskLevel === "MEDIUM") && data.paymentMethod === "CASH_ON_DELIVERY";
  const h = data.customerHistory;
  const addr = [data.shippingAddress?.addressLine1, data.area, data.shippingAddress?.city, governorateName(data.governorateCode, locale)].filter(Boolean).join("، ");

  const moreItems = [
    ...(can("invoice.manage") && data.status !== "CANCELLED" && data.status !== "PENDING" ? [{ label: t("inv.taxInvoice"), icon: "receipt", onSelect: () => void taxDoc.run("invoice") }] : []),
    ...(can("invoice.manage") && ["RETURNED", "REFUNDED", "CANCELLED"].includes(data.status) ? [{ label: t("inv.creditNote"), icon: "return", onSelect: () => void taxDoc.run("credit-note") }] : []),
    { label: t("print.invoice"), icon: "print", onSelect: () => router.push(`/dashboard/orders/${id}/print?doc=invoice`) },
    { label: t("print.label"), icon: "tag", onSelect: () => router.push(`/dashboard/orders/${id}/print?doc=label`) },
  ];

  return (
    <div className="space-y-5">
      <Link href="/dashboard/orders" className="inline-flex items-center gap-1.5 text-[13px] font-semibold text-muted hover:text-ink print:hidden"><Icon name="arrow" className="h-4 w-4 rotate-180 rtl:rotate-0" />{t("nav.orders")}</Link>
      <PageHeader eyebrow={t("sell.eyebrow")} title={`${t("orders.order")} ${data.orderNumber}`}
        subtitle={dateTime(data.createdAt, locale)}
        badge={<span className="flex flex-wrap items-center gap-1.5"><ChannelBadge channel={data.channel} /><StatusBadge status={data.status} /><StatusBadge status={data.paymentStatus} /></span>}
        actions={<div className="flex flex-wrap items-center gap-2 print:hidden">
          {can("order.update_status") && ["REQUESTED", "PREPARING"].includes(data.status) && <Button size="md" variant="secondary" onClick={() => setShipOpen(true)}><Icon name="truck" className="h-4 w-4" />{t("ship.shipWith")}</Button>}
          {forward.map((s) => <Button key={s} variant="ink" loading={move.loading} onClick={() => move.run(s)}>{t(`orders.to.${s}`)}</Button>)}
          <MenuButton icon="menu" label={t("inbox.more")} items={moreItems} />
        </div>} />
      <ErrorText error={move.error ?? taxDoc.error} />

      {/* progress */}
      {stepIdx >= 0 && (
        <Rise>
          <ol className="flex items-center gap-2 rounded-[18px] bg-surface p-4 ring-1 ring-line" aria-label={t("orders.timeline")}>
            {STEPS.map((s, i) => (
              <li key={s} className="flex min-w-0 flex-1 items-center gap-2">
                <span className={`grid h-6 w-6 shrink-0 place-items-center rounded-full text-[11px] font-bold ${i < stepIdx ? "bg-emerald-500 text-white" : i === stepIdx ? "bg-ink text-surface" : "bg-canvas text-faint ring-1 ring-line"}`}>{i < stepIdx ? <Icon name="check" className="h-3.5 w-3.5" /> : i + 1}</span>
                <span className={`hidden truncate text-[12.5px] font-semibold sm:block ${i <= stepIdx ? "text-ink" : "text-faint"}`}>{t(`status.${s}`)}</span>
                {i < STEPS.length - 1 && <span className={`h-px min-w-3 flex-1 ${i < stepIdx ? "bg-emerald-400" : "bg-line-strong"}`} />}
              </li>
            ))}
          </ol>
        </Rise>
      )}

      {risky && (
        <Notice tone={data.riskLevel === "HIGH" ? "red" : "amber"}>
          <p className="font-semibold">{data.riskLevel === "HIGH" ? t("risk.highTitle") : t("risk.mediumTitle")} <span className="tnum">({data.riskScore}/100)</span></p>
          <ul className="mt-1 list-disc ps-5">{(data.riskReasons ?? "").split(",").filter(Boolean).map((r) => <li key={r}>{t(`risk.r.${r}`)}</li>)}</ul>
          {data.needsConfirmation && !data.confirmedAt && <p className="mt-1.5 font-semibold">{t("risk.callFirst")}</p>}
        </Notice>
      )}
      {h && (h.returnedCount > 0 || h.cancelledCount > 1 || h.blocked) && (
        <Notice tone="red" action={can("order.update_status") && <Button size="sm" variant={h.blocked ? "secondary" : "danger"} loading={blockPhone.loading} onClick={() => blockPhone.run(!h.blocked)}>{h.blocked ? t("history.unblock") : t("history.block")}</Button>}>
          {t("history.warn", { d: h.deliveredCount, r: h.returnedCount, c: h.cancelledCount })}
        </Notice>
      )}

      <div className="grid gap-5 lg:grid-cols-[1.5fr_1fr]">
        <div className="min-w-0 space-y-5">
          {can("order.update_status") && <ConfirmCard o={data} reload={reload} storeName={shop.data?.storeName ?? ""} />}
          {can("order.update_status") && ["PREPARING", "SHIPPED", "ARRIVED"].includes(data.status) && <TrackingCard key={`${data.courierName}${data.trackingNumber}`} o={data} reload={reload} />}

          <Block title={t("sell.items")} icon="box" aside={data.items.length} flush>
            <ul className="divide-y divide-line">
              {data.items.map((i, k) => (
                <li key={k} className="flex items-start justify-between gap-3 px-5 py-3">
                  <div className="min-w-0">
                    <p className="text-[14px] font-semibold text-ink">{i.productNameSnapshot}</p>
                    <p className="text-[12.5px] text-muted">{[i.variantNameSnapshot, i.skuSnapshot ?? i.sku_snapshot].filter(Boolean).join(" · ")}</p>
                    <p className="text-[12.5px] text-muted tnum">{i.quantity} × {m(i.unitPriceMinor)}</p>
                    {i.note && <p className="mt-1.5 inline-flex items-center gap-1 rounded-[8px] bg-amber-50 px-2 py-1 text-[12px] font-semibold text-amber-800"><Icon name="edit" className="h-3 w-3" />{i.note}</p>}
                  </div>
                  <b className="shrink-0 text-[14px] text-ink tnum">{m(i.lineTotalMinor)}</b>
                </li>
              ))}
            </ul>
            <dl className="mt-1 space-y-1.5 border-t border-line bg-surface-2/60 px-5 py-4 text-[13.5px] [border-end-end-radius:18px] [border-end-start-radius:18px]">
              <div className="flex justify-between text-muted"><dt>{t("checkout.subtotal")}</dt><dd className="tnum">{m(data.subtotalMinor)}</dd></div>
              {data.discountMinor > 0 && <div className="flex justify-between text-emerald-700"><dt>{t("checkout.discount")}</dt><dd className="tnum">−{m(data.discountMinor)}</dd></div>}
              <div className="flex justify-between text-muted"><dt>{t("checkout.shippingFee")}</dt><dd className="tnum">{m(data.shippingMinor)}</dd></div>
              {!!data.codFeeMinor && <div className="flex justify-between text-muted"><dt>{t("checkout.codFee")}</dt><dd className="tnum">{m(data.codFeeMinor)}</dd></div>}
              <div className="flex justify-between border-t border-line pt-2 font-display text-[16px] font-semibold text-ink"><dt>{t("checkout.total")}</dt><dd className="tnum">{m(data.totalMinor)}</dd></div>
            </dl>
          </Block>
        </div>

        <div className="min-w-0 space-y-5">
          <Block title={t("orders.customer")} icon="users">
            <p className="text-[15px] font-semibold text-ink">{data.customerNameSnapshot}</p>
            <p className="mt-1 flex items-center gap-2 text-[13.5px]" dir="ltr"><a href={`tel:${data.customerPhoneSnapshot}`} className="font-medium text-brand tnum">{localPhone(data.customerPhoneSnapshot)}</a><WhatsAppButton phone={data.customerPhoneSnapshot} message={t("orders.waHello", { name: data.customerNameSnapshot, number: data.orderNumber })} size={28} /></p>
            {data.whatsappNumber && <p className="mt-1.5 flex flex-wrap items-center gap-2 text-[12.5px] text-emerald-700" dir="ltr">WhatsApp {data.whatsappNumber}{data.whatsappConfirmedAt ? <span className="inline-flex items-center gap-1 rounded-full bg-emerald-50 px-2 py-0.5 font-semibold ring-1 ring-emerald-600/15"><Icon name="check" className="h-3 w-3" />{t("orders.waConfirmed")}</span> : null}</p>}
            <div className="mt-4 space-y-2 border-t border-line pt-4 text-[13.5px] text-ink-2">
              {addr && <p className="flex gap-2"><Icon name="pin" className="mt-0.5 h-4 w-4 shrink-0 text-faint" /><span>{addr}</span></p>}
              {data.landmark && <p className="flex gap-2 text-muted"><Icon name="navigate" className="mt-0.5 h-4 w-4 shrink-0 text-faint" /><span>{data.landmark}</span></p>}
              {data.phone2 && <p dir="ltr" className="flex gap-2 text-muted"><Icon name="phone" className="mt-0.5 h-4 w-4 shrink-0 text-faint" /><span className="tnum">{data.phone2}</span></p>}
              {data.etaMinDays != null && <p className="flex gap-2 text-muted"><Icon name="truck" className="mt-0.5 h-4 w-4 shrink-0 text-faint" /><span>{t(data.etaMaxDays === 0 ? "checkout.etaToday" : "checkout.eta", { min: data.etaMinDays, max: data.etaMaxDays ?? data.etaMinDays })}</span></p>}
              {data.notes && <p className="rounded-[10px] bg-canvas px-3 py-2 italic text-muted">{data.notes}</p>}
            </div>
          </Block>

          <Block title={t("orders.payment")} icon="wallet">
            <Facts items={[
              [t("orders.payment"), t(`pay.${data.paymentMethod}`)],
              [t("admin.status"), <StatusBadge key="p" status={data.paymentStatus} />],
              [t("sell.paid"), m(paid)],
              refunded > 0 && [t("orders.refunded"), m(refunded)],
            ]} />
            {can("refund.create") && paid - refunded > 0 && <Button size="sm" variant="secondary" className="mt-4 w-full" onClick={() => { setAmount(((paid - refunded) / 100).toFixed(2)); setRefundOpen(true); }}><Icon name="return" className="h-4 w-4" />{t("orders.refund")}</Button>}
          </Block>

          <Block title={t("orders.timeline")} icon="clock">
            <ol className="relative space-y-3 ps-4 before:absolute before:inset-y-1 before:start-[3px] before:w-px before:bg-line-strong">
              {data.history.map((x, i) => (
                <li key={i} className="relative">
                  <span className="absolute -start-4 top-1.5 h-[7px] w-[7px] rounded-full bg-ink ring-2 ring-surface" />
                  <StatusBadge status={x.newStatus} />
                  <p className="mt-0.5 text-[12px] text-muted tnum">{dateTime(x.createdAt, locale)}</p>
                  {x.reason && <p className="text-[12.5px] text-ink-2">{x.reason}</p>}
                </li>
              ))}
            </ol>
          </Block>

          {canCancel && (
            <button type="button" onClick={() => setCancelOpen(true)} className="flex w-full items-center justify-center gap-2 rounded-[14px] py-3 text-[13px] font-semibold text-red-600 ring-1 ring-red-200 transition hover:bg-red-50 print:hidden"><Icon name="xcircle" className="h-4 w-4" />{t("orders.to.CANCELLED")}</button>
          )}
          <div className="print:hidden"><LinkButton href={`/dashboard/orders/${id}/print?doc=label`} icon="print">{t("print.label")}</LinkButton></div>
        </div>
      </div>

      {shipOpen && <ShipModal orderId={id} onClose={() => setShipOpen(false)} onDone={() => { setShipOpen(false); toast(t("ship.shipWith")); void reload(); }} />}
      <ConfirmDialog open={cancelOpen} onClose={() => setCancelOpen(false)} danger title={t("sell.cancelTitle")} body={t("sell.cancelBody", { n: data.orderNumber })} confirmLabel={t("orders.to.CANCELLED")} cancelLabel={t("common.back")} loading={move.loading}
        onConfirm={async () => { await move.run("CANCELLED"); setCancelOpen(false); }} />
      <Modal open={refundOpen} onClose={() => setRefundOpen(false)} title={t("orders.refund")}>
        <div className="space-y-3">
          <Field label={t("billing.amount")} hint={`${t("sell.paid")}: ${m(paid - refunded)}`}><Input value={amount} onChange={(e) => setAmount(e.target.value)} inputMode="decimal" dir="ltr" /></Field>
          <ErrorText error={refund.error} />
          <Button loading={refund.loading} onClick={() => refund.run()} className="w-full">{t("orders.refund")}</Button>
        </div>
      </Modal>
    </div>
  );
}
