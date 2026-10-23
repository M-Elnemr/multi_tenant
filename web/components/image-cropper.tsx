"use client";

import { useMemo, useRef, useState } from "react";
import { useT } from "./i18n-provider";
import { Button, Modal } from "./ui";

const RATIOS: { key: string; value: number }[] = [{ key: "1:1", value: 1 }, { key: "4:3", value: 4 / 3 }, { key: "3:4", value: 3 / 4 }, { key: "16:9", value: 16 / 9 }];
const MAX_SIDE = 1600;   // pictures are shown at most this big; smaller files upload faster and always fit the server's size limit

/** Cuts the picked picture to the wanted shape: drag to move, slide to zoom. Returns a new File (JPEG, or PNG when the original was one). */
export function ImageCropper({ file, aspect = 1, onDone, onCancel, onInvalid }: { file: File; aspect?: number; onDone: (f: File) => void; onCancel: () => void; onInvalid?: () => void }) {
  const t = useT();
  const url = useMemo(() => URL.createObjectURL(file), [file]);
  // released when the dialog is finished (not in an effect cleanup: React's dev double-run would release it before the picture loads)
  const release = () => URL.revokeObjectURL(url);
  const [ratio, setRatio] = useState(aspect);
  const [nat, setNat] = useState<{ w: number; h: number } | null>(null);
  const [zoom, setZoom] = useState(1);
  const [moved, setPos] = useState<{ x: number; y: number } | null>(null);   // image top-left inside the frame, in frame pixels; null = centred
  const frameRef = useRef<HTMLDivElement>(null);
  const drag = useRef<{ x: number; y: number; px: number; py: number } | null>(null);
  const frame = useMemo(() => { const w = Math.min(360, typeof window === "undefined" ? 320 : window.innerWidth - 80); return { w, h: w / ratio }; }, [ratio]);

  const base = nat ? Math.max(frame.w / nat.w, frame.h / nat.h) : 1;   // smallest scale that still covers the frame
  const scale = base * zoom;
  const clamp = (x: number, y: number, s = scale) => nat ? { x: Math.min(0, Math.max(frame.w - nat.w * s, x)), y: Math.min(0, Math.max(frame.h - nat.h * s, y)) } : { x, y };
  const pos = moved ?? (nat ? { x: (frame.w - nat.w * scale) / 2, y: (frame.h - nat.h * scale) / 2 } : { x: 0, y: 0 });

  const setZoomKeepingCentre = (z: number) => {
    if (!nat) return;
    const s2 = base * z;
    const cx = (frame.w / 2 - pos.x) / scale, cy = (frame.h / 2 - pos.y) / scale;   // the picture point under the frame centre
    setZoom(z);
    setPos(clamp(frame.w / 2 - cx * s2, frame.h / 2 - cy * s2, s2));
  };

  const exportFile = async (crop: boolean) => {
    if (!nat) return;
    const img = new Image();
    img.src = url;
    await img.decode();
    const sx = crop ? -pos.x / scale : 0, sy = crop ? -pos.y / scale : 0, sw = crop ? frame.w / scale : nat.w, sh = crop ? frame.h / scale : nat.h;
    const k = Math.min(1, MAX_SIDE / Math.max(sw, sh));
    const c = document.createElement("canvas");
    c.width = Math.round(sw * k); c.height = Math.round(sh * k);
    c.getContext("2d")!.drawImage(img, sx, sy, sw, sh, 0, 0, c.width, c.height);
    const png = file.type === "image/png";
    const blob: Blob = await new Promise((res, rej) => c.toBlob((b) => (b ? res(b) : rej(new Error("crop"))), png ? "image/png" : "image/jpeg", 0.9));
    release(); onDone(new File([blob], file.name.replace(/\.[^.]+$/, "") + (png ? ".png" : ".jpg"), { type: blob.type }));
  };
  const [busy, setBusy] = useState(false);
  const run = async (crop: boolean) => { setBusy(true); try { await exportFile(crop); } finally { setBusy(false); } };

  return (
    <Modal open onClose={() => { release(); onCancel(); }} title={t("crop.title")}>
      <div className="space-y-4">
        <div className="flex flex-wrap justify-center gap-1.5">
          {RATIOS.map((r) => <button key={r.key} type="button" onClick={() => { setRatio(r.value); setPos(null); setZoom(1); }} className={`rounded-lg border px-3 py-1 text-xs font-bold ${Math.abs(ratio - r.value) < 0.01 ? "border-brand bg-brand text-white" : "border-slate-300 bg-white text-slate-700"}`}>{r.key}</button>)}
        </div>
        <div className="flex justify-center">
          <div ref={frameRef} dir="ltr" className="relative touch-none select-none overflow-hidden rounded-2xl bg-slate-900 shadow-inner" style={{ width: frame.w, height: frame.h, cursor: "grab" }}
            onPointerDown={(e) => { (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId); drag.current = { x: e.clientX, y: e.clientY, px: pos.x, py: pos.y }; }}
            onPointerMove={(e) => { if (drag.current) setPos(clamp(drag.current.px + e.clientX - drag.current.x, drag.current.py + e.clientY - drag.current.y)); }}
            onPointerUp={() => { drag.current = null; }}
            onWheel={(e) => setZoomKeepingCentre(Math.min(4, Math.max(1, zoom - e.deltaY / 500)))}>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={url} alt="" draggable={false} onError={() => { release(); onInvalid?.(); }} onLoad={(e) => setNat({ w: e.currentTarget.naturalWidth, h: e.currentTarget.naturalHeight })}
              className="pointer-events-none absolute left-0 top-0 max-w-none" style={nat ? { width: nat.w * scale, height: nat.h * scale, transform: `translate(${pos.x}px, ${pos.y}px)` } : { visibility: "hidden" }} />
            <div className="pointer-events-none absolute inset-0 rounded-2xl ring-2 ring-white/70" />
          </div>
        </div>
        <label className="flex items-center gap-3 text-sm font-semibold text-slate-600">{t("crop.zoom")}
          <input type="range" min={1} max={4} step={0.01} value={zoom} onChange={(e) => setZoomKeepingCentre(Number(e.target.value))} className="flex-1 accent-[var(--brand,#1f6a99)]" />
        </label>
        <p className="text-center text-xs text-slate-500">{t("crop.hint")}</p>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <Button type="button" variant="ghost" size="sm" disabled={busy || !nat} onClick={() => void run(false)}>{t("crop.skip")}</Button>
          <div className="flex gap-2">
            <Button type="button" variant="secondary" onClick={() => { release(); onCancel(); }}>{t("common.cancel")}</Button>
            <Button type="button" loading={busy} disabled={!nat} onClick={() => void run(true)}>{t("crop.use")}</Button>
          </div>
        </div>
      </div>
    </Modal>
  );
}
