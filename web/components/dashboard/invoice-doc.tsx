"use client";

import { dateTime, money } from "@/lib/format";
import { useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Button, ErrorText, Loading } from "../ui";

type Line = { name: string; variant?: string | null; quantity: number; unitMinor: number; totalMinor: number };
type Inv = { id: string; number: string; kind: "INVOICE" | "CREDIT_NOTE"; issuedAt: string; currency: string; subtotalMinor: number; discountMinor: number; shippingMinor: number; totalMinor: number; vatPercent: number; vatMinor: number; vatIncluded: boolean;
  sellerName: string; sellerTaxId: string; sellerAddress: string; buyerName: string; buyerPhone: string; buyerAddress: string; lines: Line[]; reason?: string | null; orderNumber: string };

/** The printable tax invoice or credit note. Everything shown is the snapshot taken when it was issued. */
export default function InvoiceDoc({ id }: { id: string }) {
  const { t, locale } = useI18n();
  const d = useApi<Inv>(`store/invoices/${id}`);
  if (!d.data) return d.error ? <ErrorText error={d.error} /> : <Loading />;
  const v = d.data;
  const credit = v.kind === "CREDIT_NOTE";
  return (
    <div className="mx-auto max-w-2xl space-y-4">
      <div className="flex justify-end print:hidden"><Button onClick={() => window.print()}>{t("pos.print")}</Button></div>
      <div className="space-y-5 rounded-2xl bg-white p-8 shadow-card print:shadow-none">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div><div className="text-xl font-extrabold">{v.sellerName}</div>{v.sellerTaxId && <div className="text-sm text-slate-600">{t("inv.taxId")}: <span dir="ltr">{v.sellerTaxId}</span></div>}<div className="text-sm text-slate-600">{v.sellerAddress}</div></div>
          <div className="text-end"><div className="text-lg font-bold">{credit ? t("inv.creditNote") : t("inv.invoice")}</div><div dir="ltr" className="font-mono">{v.number}</div><div className="text-sm text-slate-500">{dateTime(v.issuedAt, locale)}</div><div className="text-xs text-slate-500">{t("orders.order")} <span dir="ltr">{v.orderNumber}</span></div></div>
        </div>
        <div className="rounded-xl bg-slate-50 p-3 text-sm"><div className="font-semibold">{v.buyerName}</div><div dir="ltr">{v.buyerPhone}</div><div>{v.buyerAddress}</div></div>
        <table className="w-full text-sm"><thead><tr className="border-b text-start text-slate-500"><th className="py-2 text-start">{t("products.name")}</th><th className="text-end">{t("inv.qty")}</th><th className="text-end">{t("inv.unit")}</th><th className="text-end">{t("pos.total")}</th></tr></thead>
          <tbody>{v.lines.map((l, i) => <tr key={i} className="border-b"><td className="py-2">{l.name}{l.variant ? ` (${l.variant})` : ""}</td><td className="text-end">{l.quantity}</td><td className="text-end">{money(l.unitMinor, v.currency, locale)}</td><td className="text-end">{money(l.totalMinor, v.currency, locale)}</td></tr>)}</tbody></table>
        <div className="ms-auto max-w-xs space-y-1 text-sm">
          <div className="flex justify-between"><span>{t("pos.subtotal")}</span><span>{money(v.subtotalMinor, v.currency, locale)}</span></div>
          {v.discountMinor > 0 && <div className="flex justify-between"><span>{t("pos.discount")}</span><span>-{money(v.discountMinor, v.currency, locale)}</span></div>}
          {v.shippingMinor > 0 && <div className="flex justify-between"><span>{t("inv.shipping")}</span><span>{money(v.shippingMinor, v.currency, locale)}</span></div>}
          <div className="flex justify-between text-base font-extrabold"><span>{t("pos.total")}</span><span>{credit ? "-" : ""}{money(v.totalMinor, v.currency, locale)}</span></div>
          {v.vatPercent > 0 && <div className="flex justify-between text-xs text-slate-500"><span>{v.vatIncluded ? t("inv.vatIncluded", { p: v.vatPercent }) : t("inv.vatNotCharged", { p: v.vatPercent })}</span><span>{v.vatIncluded ? money(v.vatMinor, v.currency, locale) : ""}</span></div>}
        </div>
        {v.reason && <p className="text-sm text-slate-600">{t("inv.reason")}: {v.reason}</p>}
      </div>
    </div>
  );
}
