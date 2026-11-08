"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { whatsappLink } from "@/lib/whatsapp";
import { ChannelIcon } from "../channel-icons";
import { useAction } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Icon } from "../icons";
import { useToast } from "../toast";
import { Button, ErrorText, Field, Input } from "../ui";

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
    <div className="flex flex-wrap gap-1.5">
      {items.filter((i) => i.show).map((i) => (
        <a key={i.key} href={whatsappLink(o.customerPhoneSnapshot, i.text)} target="_blank" rel="noopener noreferrer"
          className="inline-flex items-center gap-1.5 rounded-full bg-surface px-3 py-1.5 text-[12.5px] font-semibold text-ink-2 ring-1 ring-line-strong transition hover:text-ink hover:ring-emerald-500/50">
          <ChannelIcon channel="WHATSAPP" className="h-3.5 w-3.5" />{t(`wa.tpl.${i.key}`)}
        </a>
      ))}
    </div>
  );
}

/** "I called the customer and the order is real." Unconfirmed orders are the ones most likely to be refused at the door. */
export function ConfirmCard({ o, reload, storeName }: { o: OrderLike; reload: () => void; storeName: string }) {
  const { t } = useI18n();
  const toast = useToast();
  const confirm = useAction(async () => { await api(`store/orders/${o.id}/confirm`, { body: {} }); toast(t("confirm.done")); reload(); });
  const needs = o.status === "REQUESTED" && !o.confirmedAt;
  return (
    <section className={`rounded-[18px] p-5 ring-1 ${needs ? "bg-amber-50/70 ring-amber-600/20" : "bg-surface ring-line"}`}>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex min-w-0 items-start gap-3">
          <span className={`grid h-9 w-9 shrink-0 place-items-center rounded-[11px] ${needs ? "bg-amber-100 text-amber-700" : o.confirmedAt ? "bg-emerald-50 text-emerald-600" : "bg-canvas text-muted"}`}><Icon name={needs ? "phone" : o.confirmedAt ? "check" : "chat"} className="h-4 w-4" /></span>
          <div className="min-w-0">
            <h2 className="font-display text-[15px] font-semibold text-ink">{needs ? t("confirm.needs") : o.confirmedAt ? t("confirm.done") : t("confirm.title")}</h2>
            {needs && <p className="mt-0.5 text-[13px] text-amber-900/80">{t("confirm.hint")}</p>}
          </div>
        </div>
        {needs && <Button size="sm" variant="ink" loading={confirm.loading} onClick={() => confirm.run()}><Icon name="check" className="h-4 w-4" />{t("confirm.button")}</Button>}
      </div>
      <div className="mt-4"><WhatsAppTemplates o={o} storeName={storeName} /></div>
      {!!confirm.error && <div className="mt-3"><ErrorText error={confirm.error} /></div>}
    </section>
  );
}

/** Courier name and tracking number/link: shown to the customer on the tracking page. */
export function TrackingCard({ o, reload }: { o: OrderLike; reload: () => void }) {
  const { t } = useI18n();
  const toast = useToast();
  const [f, setF] = useState({ courierName: o.courierName ?? "", trackingNumber: o.trackingNumber ?? "", trackingUrl: o.trackingUrl ?? "" });
  const dirty = f.courierName !== (o.courierName ?? "") || f.trackingNumber !== (o.trackingNumber ?? "") || f.trackingUrl !== (o.trackingUrl ?? "");
  const save = useAction(async () => { await api(`store/orders/${o.id}/tracking`, { method: "PATCH", body: f }); toast(t("common.saved")); reload(); });
  return (
    <section className="rounded-[18px] bg-surface p-5 ring-1 ring-line">
      <h2 className="mb-4 flex items-center gap-2 font-display text-[15px] font-semibold text-ink"><Icon name="truck" className="h-4 w-4 text-faint" />{t("tracking.title")}</h2>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label={t("tracking.courier")}><Input value={f.courierName} onChange={(e) => setF({ ...f, courierName: e.target.value })} placeholder="Bosta, Aramex, Mylerz…" /></Field>
        <Field label={t("tracking.number")}><Input dir="ltr" value={f.trackingNumber} onChange={(e) => setF({ ...f, trackingNumber: e.target.value })} /></Field>
        <Field label={t("tracking.url")} className="sm:col-span-2"><Input dir="ltr" value={f.trackingUrl} onChange={(e) => setF({ ...f, trackingUrl: e.target.value })} placeholder="https://…" /></Field>
      </div>
      {!!save.error && <div className="mt-3"><ErrorText error={save.error} /></div>}
      <div className="mt-4 flex justify-end"><Button size="sm" variant="ink" disabled={!dirty} loading={save.loading} onClick={() => save.run()}>{t("common.save")}</Button></div>
    </section>
  );
}
