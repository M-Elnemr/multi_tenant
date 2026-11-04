"use client";

import { useEffect, useRef, useState } from "react";
import { useI18n } from "../i18n-provider";
import { Button } from "../ui";

type Detector = { detect: (v: HTMLVideoElement) => Promise<{ rawValue: string }[]> };
type DetectorCtor = new (o?: { formats: string[] }) => Detector;

const FORMATS = ["ean_13", "ean_8", "upc_a", "upc_e", "code_128", "code_39", "code_93", "itf", "qr_code"];

/**
 * Scanning with the phone's or tablet's camera: stays open for one product after another (the same code is read again only after 2 s), each read goes straight to the cart.
 * Uses the browser's own barcode reader (Chrome and Edge on Android, Windows, Mac); where it is missing (iPhone Safari) it says so: a scanner or typing works there.
 */
export function CameraScan({ onCode, last, onClose }: { onCode: (code: string) => void; last: string | null; onClose: () => void }) {
  const { t } = useI18n();
  const video = useRef<HTMLVideoElement>(null);
  const handler = useRef(onCode);
  useEffect(() => { handler.current = onCode; });
  const supported = typeof window !== "undefined" && "BarcodeDetector" in window && !!navigator.mediaDevices?.getUserMedia;
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (!supported) return;
    let stop = false, stream: MediaStream | null = null, timer: ReturnType<typeof setTimeout> | undefined;
    const seen = new Map<string, number>();
    (async () => {
      try {
        const Ctor = (window as unknown as { BarcodeDetector: DetectorCtor & { getSupportedFormats?: () => Promise<string[]> } }).BarcodeDetector;
        const can = Ctor.getSupportedFormats ? await Ctor.getSupportedFormats() : FORMATS;
        const det = new Ctor({ formats: FORMATS.filter((f) => can.includes(f)) });
        stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment", width: { ideal: 1280 } } });
        if (stop) { stream.getTracks().forEach((x) => x.stop()); return; }
        const v = video.current;
        if (!v) return;
        v.srcObject = stream;
        await v.play().catch(() => null);
        const tick = async () => {
          if (stop || !video.current) return;
          try {
            for (const r of await det.detect(video.current)) {
              const now = Date.now();
              if (r.rawValue && now - (seen.get(r.rawValue) ?? 0) > 2000) { seen.set(r.rawValue, now); handler.current(r.rawValue); }
            }
          } catch { /* a frame that cannot be read */ }
          timer = setTimeout(tick, 200);
        };
        void tick();
      } catch { setError(t("term.cameraDenied")); }
    })();
    return () => { stop = true; if (timer) clearTimeout(timer); stream?.getTracks().forEach((x) => x.stop()); };
  }, [supported, t]);
  return (
    <div className="space-y-3">
      {!supported ? <p className="rounded-xl bg-amber-50 p-3 text-sm text-amber-900">{t("term.cameraUnsupported")}</p>
        : error ? <p className="rounded-xl bg-amber-50 p-3 text-sm text-amber-900">{error}</p> : (
          <div className="relative overflow-hidden rounded-2xl bg-black">
            <video ref={video} className="aspect-[4/3] w-full object-cover" muted playsInline />
            <div className="pointer-events-none absolute inset-x-6 top-1/2 h-24 -translate-y-1/2 rounded-xl border-4 border-white/80" />
          </div>
        )}
      <p className="min-h-5 truncate text-center text-sm font-semibold text-emerald-700">{last ? `✓ ${last}` : t("term.cameraHint")}</p>
      <Button variant="secondary" className="w-full" onClick={onClose}>{t("term.done")}</Button>
    </div>
  );
}
