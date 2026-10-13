"use client";

import { useEffect, useRef, useState } from "react";
import { useT } from "./i18n-provider";
import { Button } from "./ui";

/** Pulls the patient number out of a scanned QR ("TABIB:123456") or barcode ("123456"). */
export function parsePatientCode(raw: string): string | null {
  const m = raw.trim().match(/^(?:TABIB:)?(\d{6,9})$/i);
  return m ? m[1] : null;
}

type Detector = { detect: (v: HTMLVideoElement) => Promise<{ rawValue: string }[]> };

/** Opens the camera and reads a QR code or barcode. Browsers without BarcodeDetector (some Safari/Firefox) show a message and the typed field stays available. */
export function QrScan({ onCode, onClose }: { onCode: (code: string) => void; onClose: () => void }) {
  const t = useT();
  const video = useRef<HTMLVideoElement>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    let stream: MediaStream | null = null;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout>;
    (async () => {
      const BD = (window as unknown as { BarcodeDetector?: new (o: { formats: string[] }) => Detector }).BarcodeDetector;
      if (!BD || !navigator.mediaDevices?.getUserMedia) { setError(t("scan.unsupported")); return; }
      try {
        stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" } });
        if (video.current) { video.current.srcObject = stream; await video.current.play(); }
        const det = new BD({ formats: ["qr_code", "code_128"] });
        const tick = async () => {
          if (stopped || !video.current) return;
          try {
            const found = await det.detect(video.current);
            for (const f of found) { const c = parsePatientCode(f.rawValue); if (c) { stopped = true; onCode(c); return; } }
          } catch { /* keep scanning */ }
          timer = setTimeout(tick, 250);
        };
        void tick();
      } catch { setError(t("scan.denied")); }
    })();
    return () => { stopped = true; clearTimeout(timer); stream?.getTracks().forEach((x) => x.stop()); };
  }, [onCode, t]);
  return (
    <div className="space-y-3">
      {error ? <p className="rounded-lg bg-amber-50 p-3 text-sm text-amber-900">{error}</p> : (
        <div className="relative overflow-hidden rounded-2xl bg-black">
          <video ref={video} className="aspect-square w-full object-cover" muted playsInline />
          <div className="pointer-events-none absolute inset-8 rounded-2xl border-4 border-white/80" />
        </div>
      )}
      <p className="text-center text-sm text-slate-500">{t("scan.hint")}</p>
      <Button variant="secondary" className="w-full" onClick={onClose}>{t("common.close")}</Button>
    </div>
  );
}
