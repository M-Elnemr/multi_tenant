"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { whatsappLink } from "@/lib/whatsapp";
import { useAction } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Alert, Button, Card, ErrorText, Field, Input } from "../ui";

export type OrderLike = {
  id: string; orderNumber: string; status: string; customerNameSnapshot: string; customerPhoneSnapshot: string; confirmedAt?: string | null; courierName?: string; trackingNumber?: string; trackingUrl?: string;
};

/** Message the customer on WhatsApp with a ready-made text for the current step (opens WhatsApp; the owner presses send). */
export function WhatsAppTemplates({ o, storeName }: { o: OrderLike; storeName: string }) {
  const { t } = useI18n();
  const track = typeof window !== "undefined" ? `${window.location.origin}/track?n=${encodeURIComponent(o.orderNumber)}` : "";
  const items: { key: string; text: string; show: boolean }[] = [
    { key: "confirm", text: t("wa.tplConfirm", { name: o.customerNameSnapshot, store: storeName, number: o.orderNumber }), show: !o.confirmedAt && o.status === "REQUESTED" },
    { key: "shipped", text: t("wa.tplShipped", { name: o.customerNameSnapshot, number: o.orderNumber, courier: o.courierName || "", tracking: o.trackingNumber || "", url: o.trackingUrl || track }), show: ["SHIPPED", "PREPARING"].includes(o.status) },
    { key: "arrived", text: t("wa.tplArrived", { name: o.customerNameSnapshot, store: storeName, number: o.orderNumber }), show: o.status === "ARRIVED" },
    { key: "hello", text: t("orders.waHello", { name: o.customerNameSnapshot, number: o.orderNumber }), show: true },
  ];
  return (
    <div className="flex flex-wrap gap-2">
      {items.filter((i) => i.show).map((i) => (
        <a key={i.key} href={whatsappLink(o.customerPhoneSnapshot, i.text)} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1.5 rounded-full bg-emerald-500 px-3.5 py-1.5 text-sm font-semibold text-white transition hover:bg-emerald-600">💬 {t(`wa.tpl.${i.key}`)}</a>
      ))}
    </div>
  );
}

/** "I called the customer and the order is real." Unconfirmed orders are the ones most likely to be refused at the door. */
export function ConfirmCard({ o, reload, storeName }: { o: OrderLike; reload: () => void; storeName: string }) {
  const { t } = useI18n();
  const confirm = useAction(async () => { await api(`store/orders/${o.id}/confirm`, { body: {} }); reload(); });
  const needs = o.status === "REQUESTED" && !o.confirmedAt;
  return (
    <Card className={`space-y-3 ${needs ? "border-amber-300 bg-amber-50/60" : ""}`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="font-semibold">{needs ? `📞 ${t("confirm.needs")}` : o.confirmedAt ? `✅ ${t("confirm.done")}` : t("confirm.title")}</h2>
        {needs && <Button size="sm" loading={confirm.loading} onClick={() => confirm.run()}>{t("confirm.button")}</Button>}
      </div>
      {needs && <p className="text-sm text-slate-600">{t("confirm.hint")}</p>}
      <WhatsAppTemplates o={o} storeName={storeName} />
      <ErrorText error={confirm.error} />
    </Card>
  );
}

/** Courier name and tracking number/link: shown to the customer on the tracking page. */
export function TrackingCard({ o, reload }: { o: OrderLike; reload: () => void }) {
  const { t } = useI18n();
  const [f, setF] = useState({ courierName: o.courierName ?? "", trackingNumber: o.trackingNumber ?? "", trackingUrl: o.trackingUrl ?? "" });
  const [saved, setSaved] = useState(false);
  const save = useAction(async () => { await api(`store/orders/${o.id}/tracking`, { method: "PATCH", body: f }); setSaved(true); reload(); });
  return (
    <Card className="space-y-3 text-sm">
      <h2 className="font-semibold">🚚 {t("tracking.title")}</h2>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label={t("tracking.courier")}><Input value={f.courierName} onChange={(e) => { setF({ ...f, courierName: e.target.value }); setSaved(false); }} placeholder="Bosta, Aramex, Mylerz…" /></Field>
        <Field label={t("tracking.number")}><Input dir="ltr" value={f.trackingNumber} onChange={(e) => { setF({ ...f, trackingNumber: e.target.value }); setSaved(false); }} /></Field>
      </div>
      <Field label={t("tracking.url")}><Input dir="ltr" value={f.trackingUrl} onChange={(e) => { setF({ ...f, trackingUrl: e.target.value }); setSaved(false); }} placeholder="https://…" /></Field>
      <ErrorText error={save.error} />{saved && <Alert tone="green">{t("common.saved")}</Alert>}
      <Button size="sm" loading={save.loading} onClick={() => save.run()}>{t("common.save")}</Button>
    </Card>
  );
}
