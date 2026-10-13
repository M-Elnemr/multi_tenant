"use client";

import { useEffect, useRef, useState } from "react";
import { useT } from "./i18n-provider";
import { Icon } from "./icons";

/** The patient's platform number with a QR code of the same number, styled as a digital ID card. A clinic scans it (or types the number) to add the patient. */
export function PatientIdCard({ publicId, name, subtitle, compact }: { publicId: number | string; name?: string; subtitle?: string; compact?: boolean }) {
  const t = useT();
  const qr = useRef<HTMLCanvasElement>(null);
  const [copied, setCopied] = useState(false);
  const id = String(publicId);
  useEffect(() => {
    let live = true;
    void import("qrcode").then((m) => { if (live && qr.current) void m.toCanvas(qr.current, `TABIB:${id}`, { width: compact ? 132 : 188, margin: 1, errorCorrectionLevel: "M" }); });
    return () => { live = false; };
  }, [id, compact]);
  return (
    <section className="hero-card animate-fade-up p-5 sm:p-7 print:bg-white print:text-black print:shadow-none">
      <div className="flex flex-wrap items-center justify-between gap-6">
        <div className="min-w-0 space-y-3">
          <p className="flex items-center gap-2 text-sm font-semibold text-white/80"><Icon name="shield" className="h-4 w-4" />{t("pid.title")}</p>
          <p className="font-mono text-4xl font-extrabold tracking-[0.22em] sm:text-5xl" dir="ltr">{id}</p>
          {name && <p className="text-lg font-bold">{name}</p>}
          {subtitle && <p className="text-sm text-white/80">{subtitle}</p>}
          <div className="flex flex-wrap gap-2 pt-1 print:hidden">
            <button onClick={() => { void navigator.clipboard?.writeText(id); setCopied(true); setTimeout(() => setCopied(false), 1500); }} className="inline-flex items-center gap-2 rounded-full bg-white/20 px-4 py-2 text-sm font-semibold backdrop-blur transition hover:bg-white/30 active:scale-95"><Icon name={copied ? "check" : "link"} className="h-4 w-4" />{copied ? t("pid.copied") : t("pid.copy")}</button>
            <button onClick={() => window.print()} className="inline-flex items-center gap-2 rounded-full bg-white/20 px-4 py-2 text-sm font-semibold backdrop-blur transition hover:bg-white/30 active:scale-95"><Icon name="print" className="h-4 w-4" />{t("pid.print")}</button>
          </div>
        </div>
        <div className="rounded-3xl bg-white p-3 shadow-xl ring-1 ring-black/5"><canvas ref={qr} aria-label="QR" className="block" /></div>
      </div>
      <p className="mt-4 text-xs text-white/75 print:hidden">{t("pid.hint")}</p>
    </section>
  );
}
