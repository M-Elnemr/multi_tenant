"use client";

import QRCode from "qrcode";
import { useEffect, useMemo, useState } from "react";
import { barcodeSvg } from "@/lib/barcode";
import { money } from "@/lib/format";
import { useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import Link from "next/link";
import { Icon } from "@/components/icons";
import { Checkbox, Switch } from "@/components/overlay";
import { SearchBox } from "@/components/dashboard/filters";
import { BackLink, SideCard } from "@/components/dashboard/product-editor-bits";
import { Button, Empty, ErrorText, Field, Input, Loading, PageHeader, Select } from "@/components/ui";

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
        <PageHeader eyebrow={<BackLink href="/dashboard/products" label={t("nav.products")} />} title={t("labels.title")} subtitle={t("labels.subtitle2")}
          actions={<Button variant="ink" onClick={() => window.print()} disabled={chosen.length === 0}><Icon name="print" className="h-4 w-4" />{t("pos.print")} ({chosen.length * copies})</Button>} />
        <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_340px]">
          <div className="min-w-0 space-y-3">
            <div className="flex flex-wrap items-center gap-2.5 rounded-[14px] bg-surface p-2.5 shadow-card">
              <SearchBox value={q} onChange={setQ} placeholder={t("common.search")} />
              <label className="inline-flex min-h-11 items-center gap-2 rounded-[11px] px-3 text-[13px] font-semibold text-ink-2 ring-1 ring-line-strong"><Checkbox checked={all} onChange={(v) => setPicked(Object.fromEntries((data ?? []).map((l) => [l.sku, v])))} label={t("labels.all")} />{t("labels.all")}</label>
              <span className="ms-auto text-[12.5px] font-medium text-muted tnum">{t("cat.selectedN", { n: chosen.length })}</span>
            </div>
            <ErrorText error={error} />
            {loading && !data ? <Loading variant="list" /> : (data ?? []).length === 0 ? (
              <Empty icon="qr" title={t("cat.labelsEmptyTitle")} action={<Link href="/dashboard/products/new"><Button variant="ink">{t("products.add")}</Button></Link>}>{t("cat.labelsEmptyBody")}</Empty>
            ) : (
              <ul className="grid gap-2 sm:grid-cols-2">{(data ?? []).map((l) => (
                <li key={l.sku} className="min-w-0">
                  <label className={`flex min-w-0 cursor-pointer items-center gap-3 rounded-[14px] p-3 text-sm ring-1 transition ${picked[l.sku] ? "bg-brand-soft ring-brand/40" : "bg-surface ring-line hover:ring-line-strong"}`}>
                    <Checkbox checked={!!picked[l.sku]} onChange={(v) => setPicked({ ...picked, [l.sku]: v })} label={l.name} />
                    <span className="min-w-0 flex-1"><span className="block truncate font-semibold text-ink">{l.name}</span><span className="block truncate text-xs text-muted">{l.variantLabel ? `${l.variantLabel} · ` : ""}<span dir="ltr">{code(l)}</span></span></span>
                    <span className="shrink-0 text-[12.5px] font-semibold text-ink-2 tnum">{money(l.priceMinor, l.currency, locale)}</span>
                  </label>
                </li>
              ))}</ul>
            )}
          </div>
          <aside className="min-w-0 space-y-4 lg:sticky lg:top-20">
            <SideCard title={t("cat.labelSettings")}>
              <div className="space-y-3">
                <Field label={t("labels.size")}><Select value={prefs.size} onChange={(e) => save({ ...prefs, size: e.target.value as Size })}><option value="38x25">{t("labels.s38")}</option><option value="50x30">{t("labels.s50")}</option><option value="A4">{t("labels.sA4")}</option></Select></Field>
                <Field label={t("labels.kind")}><Select value={prefs.kind} onChange={(e) => save({ ...prefs, kind: e.target.value as Kind })}><option value="barcode">{t("labels.kBarcode")}</option><option value="qr">{t("labels.kQr")}</option><option value="both">{t("labels.kBoth")}</option></Select></Field>
                <Field label={t("labels.copies")}><Input type="number" min={1} max={50} value={copies} onChange={(e) => setCopies(Math.max(1, Math.min(50, Number(e.target.value) || 1)))} /></Field>
                <div className="flex items-center justify-between gap-3 rounded-[12px] bg-surface-2 px-3.5 py-2.5"><span className="text-[13px] font-semibold text-ink">{t("labels.showPrice")}</span><Switch checked={prefs.price} onChange={(v) => save({ ...prefs, price: v })} label={t("labels.showPrice")} /></div>
                <p className="text-[12px] leading-relaxed text-muted">{prefs.size === "A4" ? t("labels.hintA4") : t("labels.hintRoll")}</p>
              </div>
            </SideCard>
            <SideCard title={t("labels.preview")}>
              {chosen.length > 0
                ? <div className="grid place-items-center overflow-x-auto rounded-[12px] bg-surface-2 p-4"><div className="inline-block bg-white shadow-card ring-1 ring-line">{label(chosen[0], "preview")}</div></div>
                : <p className="rounded-[12px] border border-dashed border-line-strong p-4 text-center text-[12.5px] text-muted">{t("cat.previewEmpty")}</p>}
            </SideCard>
          </aside>
        </div>
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
