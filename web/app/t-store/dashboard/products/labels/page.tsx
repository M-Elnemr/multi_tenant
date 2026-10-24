"use client";

import QRCode from "qrcode";
import { useEffect, useMemo, useState } from "react";
import { money } from "@/lib/format";
import { useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Button, ErrorText, Input, Loading, PageHeader } from "@/components/ui";

type Label = { sku: string; name: string; variantLabel?: string | null; priceMinor: number; currency: string; qr: string };

/** Printable labels: each holds a QR code with the product's web address and SKU. Customers who scan it land on the product; the cashier's scanner finds it at the till. */
export default function Labels() {
  const { t, locale } = useI18n();
  const [q, setQ] = useState("");
  const { data, loading, error } = useApi<Label[]>(`store/pos/labels?q=${encodeURIComponent(q)}`);
  const [picked, setPicked] = useState<Record<string, boolean>>({});
  const [imgs, setImgs] = useState<Record<string, string>>({});
  const [copies, setCopies] = useState(1);
  const chosen = useMemo(() => (data ?? []).filter((l) => picked[l.sku]), [data, picked]);
  useEffect(() => {
    let live = true;
    (async () => {
      const out: Record<string, string> = {};
      for (const l of chosen) if (!imgs[l.sku]) out[l.sku] = await QRCode.toDataURL(l.qr, { margin: 1, width: 240, errorCorrectionLevel: "M" });
      if (live && Object.keys(out).length) setImgs((cur) => ({ ...cur, ...out }));
    })();
    return () => { live = false; };
  }, [chosen]); // eslint-disable-line react-hooks/exhaustive-deps
  const all = (data ?? []).length > 0 && (data ?? []).every((l) => picked[l.sku]);
  return (
    <>
      <div className="print:hidden">
        <PageHeader title={t("labels.title")} subtitle={t("labels.subtitle")} actions={<Button onClick={() => window.print()} disabled={chosen.length === 0}>{t("pos.print")} ({chosen.length * copies})</Button>} />
        <div className="mb-4 flex flex-wrap items-center gap-3">
          <div className="w-full max-w-sm"><Input placeholder={t("common.search")} value={q} onChange={(e) => setQ(e.target.value)} /></div>
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={all} onChange={(e) => setPicked(Object.fromEntries((data ?? []).map((l) => [l.sku, e.target.checked])))} />{t("labels.all")}</label>
          <label className="flex items-center gap-2 text-sm">{t("labels.copies")}<input type="number" min={1} max={20} value={copies} onChange={(e) => setCopies(Math.max(1, Math.min(20, Number(e.target.value) || 1)))} className="w-16 rounded-lg border px-2 py-1" /></label>
        </div>
        <ErrorText error={error} />
        {loading && !data ? <Loading /> : (
          <ul className="mb-6 grid gap-2 sm:grid-cols-2 lg:grid-cols-3">{(data ?? []).map((l) => (
            <li key={l.sku}><label className="flex cursor-pointer items-center gap-3 rounded-xl border bg-white p-3 text-sm"><input type="checkbox" checked={!!picked[l.sku]} onChange={(e) => setPicked({ ...picked, [l.sku]: e.target.checked })} /><span className="min-w-0"><span className="block truncate font-medium">{l.name}</span><span className="text-xs text-slate-500">{l.variantLabel} · <span dir="ltr">{l.sku}</span></span></span></label></li>
          ))}</ul>
        )}
      </div>
      <div className="hidden grid-cols-3 gap-3 print:grid">
        {chosen.flatMap((l) => Array.from({ length: copies }, (_, i) => (
          <div key={`${l.sku}-${i}`} className="break-inside-avoid rounded-lg border border-black p-2 text-center">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            {imgs[l.sku] && <img src={imgs[l.sku]} alt="" className="mx-auto h-28 w-28" />}
            <div className="mt-1 text-xs font-bold leading-tight">{l.name}{l.variantLabel ? ` - ${l.variantLabel}` : ""}</div>
            <div className="text-sm font-extrabold">{money(l.priceMinor, l.currency, locale)}</div>
            <div className="text-[10px]" dir="ltr">{l.sku}</div>
          </div>
        )))}
      </div>
    </>
  );
}
