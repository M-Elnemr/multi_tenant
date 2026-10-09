"use client";

import { use } from "react";
import { useSearchParams } from "next/navigation";
import { governorateName } from "@/lib/governorates";
import { dateTime, money } from "@/lib/format";
import { useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Loading } from "@/components/ui";

type Order = {
  id: string; orderNumber: string; status: string; paymentMethod: string; currency: string; subtotalMinor: number; discountMinor: number; shippingMinor: number; codFeeMinor?: number; totalMinor: number;
  customerNameSnapshot: string; customerPhoneSnapshot: string; phone2?: string; notes?: string; createdAt: string; governorateCode?: string; area?: string; landmark?: string;
  shippingAddress: Record<string, string>; courierName?: string; trackingNumber?: string;
  items: { productNameSnapshot: string; variantNameSnapshot?: string; skuSnapshot?: string; unitPriceMinor: number; quantity: number; lineTotalMinor: number }[];
};
type Shop = { storeName: string; addressText?: string; supportPhone?: string; taxId?: string; vatIncluded?: boolean; vatPercent?: number; supportEmail?: string };

/** Printable invoice (what the customer receives) or shipping label / packing slip (what goes on the parcel). Opens the print dialog on request. */
export default function PrintOrder({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const doc = useSearchParams().get("doc") === "label" ? "label" : "invoice";
  const { t, locale } = useI18n();
  const order = useApi<Order>(`store/orders/${id}`);
  const shop = useApi<Shop>("store/profile");
  const brand = useApi<{ logoFileId?: string }>("tenant/branding");
  if (!order.data || !shop.data) return <Loading />;
  const o = order.data, s = shop.data;
  const addr = [o.shippingAddress?.addressLine1, o.area, o.shippingAddress?.city, governorateName(o.governorateCode, locale)].filter(Boolean).join("، ");
  const vat = s.vatIncluded ? Math.round(o.totalMinor - o.totalMinor / (1 + (s.vatPercent ?? 14) / 100)) : 0;
  return (
    <div className="mx-auto max-w-3xl bg-white p-6 text-slate-900 print:max-w-none print:p-0 print:text-black">
      <div className="mb-4 flex gap-2 print:hidden"><button onClick={() => window.print()} className="rounded-xl bg-slate-900 px-5 py-2 text-sm font-semibold text-white">🖨️ {t("print.print")}</button></div>
      <header className="flex items-start justify-between gap-4 border-b-2 border-slate-900 pb-4">
        <div className="flex items-center gap-3">
          {brand.data?.logoFileId && (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={`/api/bff/files/${brand.data.logoFileId}/content?variant=thumb`} alt="" className="h-14 w-14 object-contain" />
          )}
          <div><p className="text-xl font-extrabold">{s.storeName}</p>{s.addressText && <p className="text-xs text-slate-600">{s.addressText}</p>}{s.supportPhone && <p dir="ltr" className="text-xs text-slate-600">{s.supportPhone}</p>}{s.taxId && <p className="text-xs text-slate-600">{t("identity.taxId")}: <span dir="ltr">{s.taxId}</span></p>}</div>
        </div>
        <div className="text-end"><p className="text-2xl font-extrabold">{doc === "invoice" ? t("print.invoice") : t("print.label")}</p><p className="font-mono" dir="ltr">{o.orderNumber}</p><p className="text-xs text-slate-600">{dateTime(o.createdAt, locale)}</p></div>
      </header>
      <section className="my-5 grid gap-4 text-sm sm:grid-cols-2 print:grid-cols-2">
        <div className={doc === "label" ? "rounded-xl border-2 border-slate-900 p-4 sm:col-span-2 print:col-span-2" : ""}>
          <p className="text-xs font-bold uppercase text-slate-500">{doc === "label" ? t("print.shipTo") : t("print.billTo")}</p>
          <p className={doc === "label" ? "text-2xl font-extrabold" : "font-bold"}>{o.customerNameSnapshot}</p>
          <p dir="ltr" className={doc === "label" ? "text-xl font-bold" : ""}>{o.customerPhoneSnapshot}{o.phone2 && ` · ${o.phone2}`}</p>
          <p className={doc === "label" ? "mt-1 text-lg" : ""}>{addr}</p>{o.landmark && <p>📍 {o.landmark}</p>}
        </div>
        {doc === "invoice" && <div><p className="text-xs font-bold uppercase text-slate-500">{t("orders.payment")}</p><p className="font-bold">{t(`pay.${o.paymentMethod}`)}</p>{o.courierName && <p className="text-slate-600">{o.courierName} {o.trackingNumber}</p>}</div>}
      </section>
      {doc === "label" && (
        <div className="mb-5 rounded-xl border-2 border-dashed border-slate-900 p-4 text-center"><p className="text-xs font-bold uppercase text-slate-500">{t("print.codAmount")}</p><p className="text-4xl font-extrabold">{o.paymentMethod === "CASH_ON_DELIVERY" ? money(o.totalMinor, o.currency, locale) : t("print.paid")}</p></div>
      )}
      <table className="w-full text-sm">
        <thead><tr className="border-b-2 border-slate-900 text-start text-xs uppercase"><th className="py-2 text-start">{t("products.name")}</th><th className="py-2 text-center">{t("print.qty")}</th>{doc === "invoice" && <><th className="py-2 text-end">{t("print.price")}</th><th className="py-2 text-end">{t("checkout.total")}</th></>}</tr></thead>
        <tbody>{o.items.map((i, k) => <tr key={k} className="border-b border-slate-200"><td className="py-2">{i.productNameSnapshot} {i.variantNameSnapshot && <span className="text-slate-500">({i.variantNameSnapshot})</span>}<span className="block font-mono text-[10px] text-slate-400">{i.skuSnapshot}</span></td><td className="py-2 text-center font-bold">{i.quantity}</td>{doc === "invoice" && <><td className="py-2 text-end">{money(i.unitPriceMinor, o.currency, locale)}</td><td className="py-2 text-end font-bold">{money(i.lineTotalMinor, o.currency, locale)}</td></>}</tr>)}</tbody>
      </table>
      {doc === "invoice" && (
        <dl className="ms-auto mt-4 w-64 space-y-1 text-sm">
          <div className="flex justify-between"><dt>{t("checkout.subtotal")}</dt><dd>{money(o.subtotalMinor, o.currency, locale)}</dd></div>
          {o.discountMinor > 0 && <div className="flex justify-between"><dt>{t("checkout.discount")}</dt><dd>−{money(o.discountMinor, o.currency, locale)}</dd></div>}
          <div className="flex justify-between"><dt>{t("checkout.shippingFee")}</dt><dd>{money(o.shippingMinor, o.currency, locale)}</dd></div>
          {!!o.codFeeMinor && <div className="flex justify-between"><dt>{t("checkout.codFee")}</dt><dd>{money(o.codFeeMinor, o.currency, locale)}</dd></div>}
          {vat > 0 && <div className="flex justify-between text-slate-600"><dt>{t("print.vatIncluded", { p: s.vatPercent ?? 14 })}</dt><dd>{money(vat, o.currency, locale)}</dd></div>}
          <div className="flex justify-between border-t-2 border-slate-900 pt-2 text-lg font-extrabold"><dt>{t("checkout.total")}</dt><dd>{money(o.totalMinor, o.currency, locale)}</dd></div>
        </dl>
      )}
      {o.notes && <p className="mt-4 text-sm"><b>{t("checkout.notes")}:</b> {o.notes}</p>}
      <footer className="mt-8 border-t pt-3 text-center text-xs text-slate-500">{t("print.thanks")}</footer>
    </div>
  );
}
