"use client";

import { useEffect, useRef, useState } from "react";
import { useT } from "./i18n-provider";
import { Button, Card } from "./ui";

/** The patient's platform number with a QR and a barcode of the same number (scanned by a clinic to add the patient). */
export function PatientIdCard({ publicId, name, compact }: { publicId: number | string; name?: string; compact?: boolean }) {
  const t = useT();
  const qr = useRef<HTMLCanvasElement>(null);
  const bar = useRef<SVGSVGElement>(null);
  const [copied, setCopied] = useState(false);
  const id = String(publicId);
  useEffect(() => {
    let live = true;
    void import("qrcode").then((m) => { if (live && qr.current) void m.toCanvas(qr.current, `TABIB:${id}`, { width: compact ? 120 : 180, margin: 1, errorCorrectionLevel: "M" }); });
    void import("jsbarcode").then((m) => { if (live && bar.current) m.default(bar.current, id, { format: "CODE128", height: compact ? 40 : 60, displayValue: false, margin: 0, width: 2 }); });
    return () => { live = false; };
  }, [id, compact]);
  return (
    <Card className="print:shadow-none">
      <div className="flex flex-wrap items-center gap-6">
        <div className="space-y-1">
          <p className="text-sm text-slate-500">{t("pid.title")}</p>
          <p className="font-mono text-4xl font-extrabold tracking-[0.25em] text-brand" dir="ltr">{id}</p>
          {name && <p className="text-sm font-medium">{name}</p>}
          <div className="flex gap-2 pt-2 print:hidden">
            <Button size="sm" variant="secondary" onClick={() => { void navigator.clipboard?.writeText(id); setCopied(true); setTimeout(() => setCopied(false), 1500); }}>{copied ? t("pid.copied") : t("pid.copy")}</Button>
            <Button size="sm" variant="secondary" onClick={() => window.print()}>{t("pid.print")}</Button>
          </div>
        </div>
        <div className="flex flex-col items-center gap-3">
          <canvas ref={qr} aria-label="QR" className="rounded-lg border border-slate-200 bg-white p-1" />
          <svg ref={bar} aria-label="barcode" className="max-w-[220px]" />
        </div>
      </div>
      <p className="mt-3 text-xs text-slate-500 print:hidden">{t("pid.hint")}</p>
    </Card>
  );
}
