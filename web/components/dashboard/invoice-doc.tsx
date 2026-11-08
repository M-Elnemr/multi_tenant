"use client";

import Link from "next/link";
import { dateTime, money } from "@/lib/format";
import { useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Icon } from "../icons";
import { Badge, Button, ErrorText, Loading } from "../ui";

type Line = { name: string; variant?: string | null; quantity: number; unitMinor: number; totalMinor: number };
type Inv = { id: string; number: string; kind: "INVOICE" | "CREDIT_NOTE"; issuedAt: string; currency: string; subtotalMinor: number; discountMinor: number; shippingMinor: number; totalMinor: number; vatPercent: number; vatMinor: number; vatIncluded: boolean;
  sellerName: string; sellerTaxId: string; sellerAddress: string; buyerName: string; buyerPhone: string; buyerAddress: string; lines: Line[]; reason?: string | null; orderNumber: string };

/** The printable tax invoice or credit note. Everything shown is the snapshot taken when it was issued. The paper stays white in dark mode (it is printed). */
export default function InvoiceDoc({ id }: { id: string }) {
  const { t, locale } = useI18n();
  const d = useApi<Inv>(`store/invoices/${id}`);
  if (!d.data) return d.error ? <ErrorText error={d.error} /> : <Loading variant="detail" />;
  const v = d.data;
  const credit = v.kind === "CREDIT_NOTE";
  return (
    <div className="mx-auto max-w-2xl space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2 print:hidden">
        <Link href="/dashboard/invoices" className="inline-flex items-center gap-1.5 text-[13px] font-semibold text-muted hover:text-ink"><Icon name="arrow" className="h-4 w-4 rotate-180 rtl:rotate-0" />{t("inv.title")}</Link>
        <div className="flex items-center gap-2">
          <Link href={`/dashboard/orders`} className="hidden h-10 items-center rounded-[11px] px-3.5 text-[13px] font-semibold text-ink-2 ring-1 ring-line-strong hover:bg-surface sm:inline-flex">{t("nav.orders")}</Link>
          <Button variant="ink" onClick={() => window.print()}><Icon name="print" className="h-4 w-4" />{t("pos.print")}</Button>
        </div>
      </div>
      <div className="space-y-6 rounded-[18px] bg-white p-6 text-slate-900 shadow-[0_0_0_1px_var(--line),0_18px_40px_-24px_rgb(16_18_24/.3)] print:rounded-none print:p-0 print:shadow-none sm:p-9">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div><div className="font-display text-xl font-bold">{v.sellerName}</div>{v.sellerTaxId && <div className="text-sm text-slate-600">{t("inv.taxId")}: <span dir="ltr">{v.sellerTaxId}</span></div>}<div className="text-sm text-slate-600">{v.sellerAddress}</div></div>
          <div className="text-end"><div className="mb-1 flex justify-end">{credit ? <Badge tone="amber">{t("inv.creditNote")}</Badge> : <Badge tone="green">{t("inv.invoice")}</Badge>}</div><div dir="ltr" className="font-mono text-lg font-semibold">{v.number}</div><div className="text-sm text-slate-500">{dateTime(v.issuedAt, locale)}</div><div className="text-xs text-slate-500">{t("orders.order")} <span dir="ltr">{v.orderNumber}</span></div></div>
        </div>
        <div className="rounded-[12px] bg-slate-50 p-4 text-sm"><div className="font-semibold">{v.buyerName}</div><div dir="ltr" className="text-slate-600">{v.buyerPhone}</div><div className="text-slate-600">{v.buyerAddress}</div></div>
        <table className="w-full text-sm"><thead><tr className="border-b border-slate-200 text-slate-500"><th className="py-2 text-start font-semibold">{t("products.name")}</th><th className="text-end font-semibold">{t("inv.qty")}</th><th className="text-end font-semibold">{t("inv.unit")}</th><th className="text-end font-semibold">{t("pos.total")}</th></tr></thead>
          <tbody>{v.lines.map((l, i) => <tr key={i} className="border-b border-slate-100"><td className="py-2.5">{l.name}{l.variant ? ` (${l.variant})` : ""}</td><td className="text-end tabular-nums">{l.quantity}</td><td className="text-end tabular-nums">{money(l.unitMinor, v.currency, locale)}</td><td className="text-end tabular-nums">{money(l.totalMinor, v.currency, locale)}</td></tr>)}</tbody></table>
        <div className="ms-auto max-w-xs space-y-1.5 text-sm tabular-nums">
          <div className="flex justify-between"><span>{t("pos.subtotal")}</span><span>{money(v.subtotalMinor, v.currency, locale)}</span></div>
          {v.discountMinor > 0 && <div className="flex justify-between"><span>{t("pos.discount")}</span><span>-{money(v.discountMinor, v.currency, locale)}</span></div>}
          {v.shippingMinor > 0 && <div className="flex justify-between"><span>{t("inv.shipping")}</span><span>{money(v.shippingMinor, v.currency, locale)}</span></div>}
          <div className="flex justify-between border-t border-slate-200 pt-2 text-base font-extrabold"><span>{t("pos.total")}</span><span>{credit ? "-" : ""}{money(v.totalMinor, v.currency, locale)}</span></div>
          {v.vatPercent > 0 && <div className="flex justify-between text-xs text-slate-500"><span>{v.vatIncluded ? t("inv.vatIncluded", { p: v.vatPercent }) : t("inv.vatNotCharged", { p: v.vatPercent })}</span><span>{v.vatIncluded ? money(v.vatMinor, v.currency, locale) : ""}</span></div>}
        </div>
        {v.reason && <p className="text-sm text-slate-600">{t("inv.reason")}: {v.reason}</p>}
      </div>
    </div>
  );
}
