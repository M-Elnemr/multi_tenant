"use client";

import { useEffect, useState } from "react";
import QRCode from "qrcode";
import { money } from "@/lib/format";
import type { Invoice, Shop } from "@/lib/pos/db";
import { useI18n } from "../i18n-provider";

export type PrintDoc =
  | { kind: "sale"; invoice: Invoice; copy?: boolean }
  | { kind: "return"; invoice: Invoice; lines: { name: string; qty: number; unitMinor: number }[]; refundMinor: number; method: string; at: string }
  | { kind: "report"; title: string; rows: [string, string][]; at: string };

const WIDTH: Record<string, string> = { "58": "48mm", "80": "72mm", A4: "180mm" };

/**
 * What the receipt printer prints: hidden on screen, the only thing on paper. The page size follows the shop's choice (or this terminal's): 58 mm or 80 mm thermal rolls, or A4.
 * Works with no internet (the QR is drawn in the browser).
 */
export function Receipt({ doc, shop, size, deviceName, onPrinted }: { doc: PrintDoc | null; shop: Shop | null; size: "58" | "80" | "A4"; deviceName: string; onPrinted: () => void }) {
  const { t, locale } = useI18n();
  const [drawn, setDrawn] = useState<{ number: string; url: string | null } | null>(null);
  const number = doc && doc.kind !== "report" ? doc.invoice.number : null;
  const qr = drawn && drawn.number === number ? drawn.url : null;
  const qrReady = !number || (drawn !== null && drawn.number === number);

  useEffect(() => {
    let alive = true;
    if (!number) return;
    QRCode.toDataURL(number, { margin: 0, width: 160 }).then((u) => alive && setDrawn({ number, url: u })).catch(() => alive && setDrawn({ number, url: null }));
    return () => { alive = false; };
  }, [number]);

  useEffect(() => {
    if (!doc || !qrReady) return;
    const done = () => onPrinted();
    window.addEventListener("afterprint", done, { once: true });
    const id = setTimeout(() => window.print(), 60);
    return () => { clearTimeout(id); window.removeEventListener("afterprint", done); };
  }, [doc, qrReady, onPrinted]);

  if (!doc) return null;
  const cur = doc.kind === "report" ? shop?.currency ?? "EGP" : doc.invoice.currency;
  const m = (v: number) => money(v, cur, locale);
  const thermal = size !== "A4";
  const when = (iso: string) => new Date(iso).toLocaleString(locale === "ar" ? "ar-EG" : "en-GB", { dateStyle: "short", timeStyle: "short" });
  return (
    <div className="pos-receipt hidden print:block" style={{ width: WIDTH[size], fontSize: thermal ? (size === "58" ? "10px" : "11.5px") : "12px", color: "#000" }}>
      <style>{`@media print { @page { size: ${thermal ? `${size}mm auto` : "A4"}; margin: ${thermal ? "2mm" : "12mm"}; } body * { visibility: hidden; } .pos-receipt, .pos-receipt * { visibility: visible; } .pos-receipt { position: absolute; inset-inline-start: 0; top: 0; } }`}</style>
      <div style={{ textAlign: "center", lineHeight: 1.35 }}>
        <div style={{ fontWeight: 800, fontSize: "1.25em" }}>{shop?.name}</div>
        {shop?.address && <div>{shop.address}</div>}
        {shop?.phone && <div dir="ltr">{shop.phone}</div>}
        {shop?.taxId && <div>{t("term.taxId")}: <span dir="ltr">{shop.taxId}</span></div>}
      </div>
      <Hr />
      {doc.kind === "report" ? (
        <>
          <div style={{ textAlign: "center", fontWeight: 700 }}>{doc.title}</div>
          <div style={{ textAlign: "center" }}>{when(doc.at)} · {deviceName}</div>
          <Hr />
          {doc.rows.map(([k, v], i) => k === "—" ? <Hr key={i} /> : <Row key={i} k={k} v={v} />)}
        </>
      ) : (
        <>
          <div style={{ textAlign: "center", fontWeight: 700 }}>{doc.kind === "return" ? t("term.returnReceipt") : t("term.receiptTitle")}{doc.kind === "sale" && doc.copy ? ` · ${t("term.copy")}` : ""}</div>
          <Row k={t("term.invoiceNo")} v={doc.invoice.number} ltr />
          <Row k={t("term.date")} v={when(doc.kind === "return" ? doc.at : doc.invoice.at)} />
          {doc.invoice.cashierName && <Row k={t("term.cashier")} v={doc.invoice.cashierName} />}
          {doc.invoice.customerName && doc.invoice.customerName !== "Walk-in customer" && <Row k={t("term.customer")} v={doc.invoice.customerName} />}
          <Hr />
          {(doc.kind === "return" ? doc.lines.map((l) => ({ name: l.name, label: null as string | null, qty: l.qty, unitMinor: l.unitMinor, discountMinor: 0 })) : doc.invoice.lines).map((l, i) => (
            <div key={i} style={{ marginBottom: 3 }}>
              <div style={{ fontWeight: 600 }}>{l.name}{l.label ? ` (${l.label})` : ""}</div>
              <div style={{ display: "flex", justifyContent: "space-between" }}><span dir="ltr">{l.qty} × {m(l.unitMinor)}</span><span>{m(l.qty * l.unitMinor - (l.discountMinor ?? 0))}</span></div>
              {(l.discountMinor ?? 0) > 0 && <div style={{ fontSize: "0.9em" }}>{t("term.lineDiscount")}: -{m(l.discountMinor ?? 0)}</div>}
            </div>
          ))}
          <Hr />
          {doc.kind === "return" ? (
            <>
              <Row k={t("term.refund")} v={m(doc.refundMinor)} bold />
              <Row k={t("term.method")} v={t(`pay.${doc.method}`)} />
            </>
          ) : (
            <>
              <Row k={t("term.subtotal")} v={m(doc.invoice.subtotalMinor)} />
              {doc.invoice.discountMinor > 0 && <Row k={t("term.discount")} v={`-${m(doc.invoice.discountMinor)}`} />}
              <Row k={t("term.total")} v={m(doc.invoice.totalMinor)} bold />
              {doc.invoice.vatMinor > 0 && <Row k={t("term.vatIncluded", { p: String(shop?.vatPercent ?? "") })} v={m(doc.invoice.vatMinor)} />}
              <Hr />
              {doc.invoice.payments.map((p, i) => <Row key={i} k={t(`pay.${p.method}`)} v={m(p.amountMinor)} />)}
              {(doc.invoice.tenderedMinor ?? 0) > 0 && <Row k={t("term.tendered")} v={m(doc.invoice.tenderedMinor ?? 0)} />}
              {(doc.invoice.changeMinor ?? 0) > 0 && <Row k={t("term.change")} v={m(doc.invoice.changeMinor ?? 0)} bold />}
            </>
          )}
          {/* eslint-disable-next-line @next/next/no-img-element -- drawn in the browser, works offline */}
          {qr && <div style={{ textAlign: "center", marginTop: 6 }}><img src={qr} alt="" style={{ width: thermal ? "28mm" : "30mm", height: thermal ? "28mm" : "30mm", display: "inline-block" }} /></div>}
          {shop?.receiptFooter && <div style={{ textAlign: "center", marginTop: 4, whiteSpace: "pre-line" }}>{shop.receiptFooter}</div>}
          <div style={{ textAlign: "center", marginTop: 4 }}>{t("term.thanks")}</div>
        </>
      )}
    </div>
  );
}

function Row({ k, v, bold, ltr }: { k: string; v: string; bold?: boolean; ltr?: boolean }) {
  return <div style={{ display: "flex", justifyContent: "space-between", gap: 8, fontWeight: bold ? 800 : 400, fontSize: bold ? "1.15em" : undefined }}><span>{k}</span><span dir={ltr ? "ltr" : undefined}>{v}</span></div>;
}

function Hr() { return <div style={{ borderTop: "1px dashed #000", margin: "5px 0" }} />; }
