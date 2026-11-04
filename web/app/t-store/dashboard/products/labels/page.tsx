"use client";

import QRCode from "qrcode";
import { useEffect, useMemo, useState } from "react";
import { barcodeSvg } from "@/lib/barcode";
import { money } from "@/lib/format";
import { useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Button, ErrorText, Input, Loading, PageHeader, Select } from "@/components/ui";

type Label = { sku: string; barcode: string | null; name: string; variantLabel?: string | null; priceMinor: number; currency: string; qr: string };
type Size = "38x25" | "50x30" | "A4";
type Kind = "barcode" | "qr" | "both";

const SIZES: Record<Size, { w: string; h: string } | null> = { "38x25": { w: "38mm", h: "25mm" }, "50x30": { w: "50mm", h: "30mm" }, A4: null };
const PREFS = "labels-prefs";

/**
 * Printable labels. A barcode (EAN-13, or Code 128 for codes with letters) that any till scanner reads, a QR with the product's web address (customers land on the product,
 * a 2D scanner finds it at the till), or both. Sticker rolls (38×25 or 50×30 mm, one label per page for label printers) or an A4 sheet.
 */
export default function Labels() {
  const { t, locale } = useI18n();
  const [q, setQ] = useState("");
  const { data, loading, error } = useApi<Label[]>(`store/pos/labels?q=${encodeURIComponent(q)}`);
  const [picked, setPicked] = useState<Record<string, boolean>>({});
  const [qrs, setQrs] = useState<Record<string, string>>({});
  const [copies, setCopies] = useState(1);
  const [prefs, setPrefs] = useState<{ size: Size; kind: Kind; price: boolean }>({ size: "38x25", kind: "barcode", price: true });
  useEffect(() => {   // the last choice of this browser (read after the first paint, so the page renders the same on the server)
    void Promise.resolve().then(() => { try { const p = JSON.parse(localStorage.getItem(PREFS) ?? "null"); if (p?.size && p?.kind) setPrefs(p); } catch { /* first time */ } });
  }, []);
  const save = (p: typeof prefs) => { setPrefs(p); try { localStorage.setItem(PREFS, JSON.stringify(p)); } catch { /* private mode */ } };
  const chosen = useMemo(() => (data ?? []).filter((l) => picked[l.sku]), [data, picked]);
  const needQr = prefs.kind !== "barcode";
  useEffect(() => {
    if (!needQr) return;
    let live = true;
    (async () => {
      const out: Record<string, string> = {};
      for (const l of chosen) if (!qrs[l.sku]) out[l.sku] = await QRCode.toDataURL(l.qr, { margin: 0, width: 240, errorCorrectionLevel: "M" });
      if (live && Object.keys(out).length) setQrs((cur) => ({ ...cur, ...out }));
    })();
    return () => { live = false; };
  }, [chosen, needQr]); // eslint-disable-line react-hooks/exhaustive-deps
  const all = (data ?? []).length > 0 && (data ?? []).every((l) => picked[l.sku]);
  const sticker = SIZES[prefs.size];
  const code = (l: Label) => l.barcode || l.sku;   // every product has a barcode now; the SKU is the fallback

  const label = (l: Label, key: string) => (
    <div key={key} className="label-cell flex flex-col items-center justify-center overflow-hidden text-center text-black"
      style={sticker ? { width: sticker.w, height: sticker.h, padding: "1.2mm 1.5mm" } : { padding: "3mm", border: "1px solid #000", borderRadius: "2mm" }}>
      <div className="w-full truncate font-bold leading-tight" style={{ fontSize: sticker ? (prefs.size === "38x25" ? "7pt" : "8pt") : "9pt" }}>{l.name}{l.variantLabel ? ` · ${l.variantLabel}` : ""}</div>
      <div className="flex w-full min-h-0 flex-1 items-center justify-center gap-1">
        {prefs.kind !== "qr" && (
          <div className="flex min-w-0 flex-1 flex-col items-center">
            <div className="w-full" style={{ height: sticker ? (prefs.size === "38x25" ? "9mm" : "12mm") : "16mm" }} dangerouslySetInnerHTML={{ __html: barcodeSvg(code(l)).replace("<svg ", '<svg width="100%" height="100%" ') }} />
            <div dir="ltr" className="font-mono leading-none" style={{ fontSize: sticker ? "6.5pt" : "8pt", letterSpacing: "0.05em" }}>{code(l)}</div>
          </div>
        )}
        {/* eslint-disable-next-line @next/next/no-img-element -- drawn in the browser */}
        {prefs.kind !== "barcode" && qrs[l.sku] && <img src={qrs[l.sku]} alt="" style={{ height: sticker ? (prefs.size === "38x25" ? "13mm" : "17mm") : "26mm", width: "auto" }} />}
      </div>
      {prefs.price && <div className="font-extrabold leading-tight" style={{ fontSize: sticker ? "9pt" : "11pt" }}>{money(l.priceMinor, l.currency, locale)}</div>}
    </div>
  );

  return (
    <>
      <div className="print:hidden">
        <PageHeader title={t("labels.title")} subtitle={t("labels.subtitle2")} actions={<Button onClick={() => window.print()} disabled={chosen.length === 0}>{t("pos.print")} ({chosen.length * copies})</Button>} />
        <div className="mb-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <label className="block text-sm font-semibold">{t("labels.size")}
            <Select value={prefs.size} onChange={(e) => save({ ...prefs, size: e.target.value as Size })} className="mt-1"><option value="38x25">{t("labels.s38")}</option><option value="50x30">{t("labels.s50")}</option><option value="A4">{t("labels.sA4")}</option></Select>
          </label>
          <label className="block text-sm font-semibold">{t("labels.kind")}
            <Select value={prefs.kind} onChange={(e) => save({ ...prefs, kind: e.target.value as Kind })} className="mt-1"><option value="barcode">{t("labels.kBarcode")}</option><option value="qr">{t("labels.kQr")}</option><option value="both">{t("labels.kBoth")}</option></Select>
          </label>
          <label className="block text-sm font-semibold">{t("labels.copies")}<Input type="number" min={1} max={50} value={copies} onChange={(e) => setCopies(Math.max(1, Math.min(50, Number(e.target.value) || 1)))} className="mt-1" /></label>
          <label className="flex items-end gap-2 pb-2 text-sm"><input type="checkbox" checked={prefs.price} onChange={(e) => save({ ...prefs, price: e.target.checked })} />{t("labels.showPrice")}</label>
        </div>
        <div className="mb-4 flex flex-wrap items-center gap-3">
          <div className="w-full max-w-sm"><Input placeholder={t("common.search")} value={q} onChange={(e) => setQ(e.target.value)} /></div>
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={all} onChange={(e) => setPicked(Object.fromEntries((data ?? []).map((l) => [l.sku, e.target.checked])))} />{t("labels.all")}</label>
        </div>
        <p className="mb-3 text-xs text-slate-500">{prefs.size === "A4" ? t("labels.hintA4") : t("labels.hintRoll")}</p>
        <ErrorText error={error} />
        {loading && !data ? <Loading /> : (
          <ul className="mb-6 grid gap-2 sm:grid-cols-2 lg:grid-cols-3">{(data ?? []).map((l) => (
            <li key={l.sku} className="min-w-0"><label className="flex min-w-0 cursor-pointer items-center gap-3 rounded-xl border bg-white p-3 text-sm"><input type="checkbox" checked={!!picked[l.sku]} onChange={(e) => setPicked({ ...picked, [l.sku]: e.target.checked })} />
              <span className="min-w-0"><span className="block truncate font-medium">{l.name}</span><span className="block truncate text-xs text-slate-500">{l.variantLabel ? `${l.variantLabel} · ` : ""}<span dir="ltr">{code(l)}</span></span></span></label></li>
          ))}</ul>
        )}
        {chosen.length > 0 && (
          <div className="mb-6">
            <p className="mb-2 text-sm font-semibold">{t("labels.preview")}</p>
            <div className="inline-block rounded-lg bg-white shadow-card ring-1 ring-slate-200">{label(chosen[0], "preview")}</div>
          </div>
        )}
      </div>
      {/* what goes on paper: one label per page on a roll, a grid on A4 */}
      <style>{sticker
        ? `@media print { @page { size: ${sticker.w} ${sticker.h}; margin: 0; } .labels-print .label-cell { page-break-after: always; break-after: page; } }`
        : `@media print { @page { size: A4; margin: 8mm; } }`}</style>
      <div className={`labels-print hidden print:block ${sticker ? "" : "print:grid print:grid-cols-3 print:gap-2"}`}>
        {chosen.flatMap((l) => Array.from({ length: copies }, (_, i) => label(l, `${l.sku}-${i}`)))}
      </div>
    </>
  );
}
