"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "@/lib/client";
import { money } from "@/lib/format";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Alert, Button, Card, ErrorText, Field, Input, Select } from "../ui";

type Found = { variantId: string; name: string; variantLabel?: string | null; priceMinor: number; currency: string; available: number };
type Line = Found & { qty: number };
type Done = { orderId: string; orderNumber: string; totalMinor: number; currency: string; paymentMethod: string; lines: Line[]; discountMinor: number };
type Today = { sales: number; totalMinor: number };

type Detector = { detect: (v: HTMLVideoElement) => Promise<{ rawValue: string }[]> };
type DetectorCtor = new (o?: { formats: string[] }) => Detector;

/** Counter sales: scan a product label (QR or barcode) with the camera, a USB scanner or by typing, take the money, print the receipt. */
export default function Pos() {
  const { t, locale, currency } = useI18n();
  const [lines, setLines] = useState<Line[]>([]);
  const [code, setCode] = useState("");
  const [msg, setMsg] = useState<string | null>(null);
  const [discount, setDiscount] = useState("");
  const [method, setMethod] = useState("CASH");
  const [phone, setPhone] = useState("");
  const [name, setName] = useState("");
  const [done, setDone] = useState<Done | null>(null);
  const [camera, setCamera] = useState(false);
  const today = useApi<Today>("store/pos/today");
  const last = useRef<{ code: string; at: number }>({ code: "", at: 0 });
  const video = useRef<HTMLVideoElement>(null);

  const scan = useAction(async (raw: string) => {
    const c = raw.trim();
    if (!c) return;
    setMsg(null);
    const f = await api<Found>(`store/pos/lookup?code=${encodeURIComponent(c)}`);
    setLines((cur) => {
      const i = cur.findIndex((l) => l.variantId === f.variantId);
      if (i >= 0) return cur.map((l, k) => (k === i ? { ...l, qty: Math.min(l.qty + 1, Math.max(1, l.available)) } : l));
      return [...cur, { ...f, qty: 1 }];
    });
    setCode("");
  });
  const scanRef = useRef(scan.run);
  useEffect(() => { scanRef.current = scan.run; });

  // camera scanning with the browser's own detector (Chrome on Android and desktop); other browsers keep typing and USB scanners
  const supported = typeof window !== "undefined" && "BarcodeDetector" in window;
  const onCode = useCallback((c: string) => {
    const now = Date.now();
    if (last.current.code === c && now - last.current.at < 2500) return;
    last.current = { code: c, at: now };
    void scanRef.current(c);
  }, []);
  useEffect(() => {
    if (!camera || !supported) return;
    let stop = false;
    let stream: MediaStream | null = null;
    const Ctor = (window as unknown as { BarcodeDetector: DetectorCtor }).BarcodeDetector;
    const det = new Ctor({ formats: ["qr_code", "ean_13", "ean_8", "code_128", "upc_a"] });
    navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" } }).then(async (s) => {
      if (stop) { s.getTracks().forEach((x) => x.stop()); return; }
      stream = s;
      const v = video.current;
      if (!v) return;
      v.srcObject = s;
      await v.play().catch(() => null);
      const tick = async () => {
        if (stop || !video.current) return;
        try { const r = await det.detect(video.current); if (r[0]?.rawValue) onCode(r[0].rawValue); } catch { /* a frame that cannot be read */ }
        setTimeout(tick, 350);
      };
      void tick();
    }).catch(() => { setMsg(t("pos.cameraDenied")); setCamera(false); });
    return () => { stop = true; stream?.getTracks().forEach((x) => x.stop()); };
  }, [camera, supported, onCode, t]);

  const subtotal = lines.reduce((n, l) => n + l.priceMinor * l.qty, 0);
  const disc = Math.max(0, Math.round(Number(discount || "0") * 100));
  const total = Math.max(0, subtotal - disc);
  const cur = lines[0]?.currency ?? currency;
  const sell = useAction(async () => {
    const r = await api<{ orderId: string; orderNumber: string; totalMinor: number; currency: string; paymentMethod: string }>("store/pos/sales", { body: { items: lines.map((l) => ({ variantId: l.variantId, quantity: l.qty })), paymentMethod: method, discountMinor: disc || undefined, customerName: name || undefined, phone: phone || undefined } });
    setDone({ ...r, lines, discountMinor: disc });
    setLines([]); setDiscount(""); setPhone(""); setName("");
    await today.reload();
  });

  if (done) {
    return (
      <Card className="mx-auto max-w-md space-y-3 print:shadow-none">
        <div className="text-center"><div className="text-3xl">✅</div><h2 className="text-lg font-bold">{t("pos.done")}</h2><p className="text-sm text-slate-500" dir="ltr">{done.orderNumber}</p></div>
        <ul className="divide-y text-sm">{done.lines.map((l) => <li key={l.variantId} className="flex justify-between py-1.5"><span>{l.qty} × {l.name}{l.variantLabel ? ` (${l.variantLabel})` : ""}</span><span>{money(l.priceMinor * l.qty, done.currency, locale)}</span></li>)}</ul>
        {done.discountMinor > 0 && <p className="flex justify-between text-sm"><span>{t("pos.discount")}</span><span>-{money(done.discountMinor, done.currency, locale)}</span></p>}
        <p className="flex justify-between text-lg font-bold"><span>{t("pos.total")}</span><span>{money(done.totalMinor, done.currency, locale)}</span></p>
        <p className="text-xs text-slate-500">{t(`pay.${done.paymentMethod}`)}</p>
        <div className="flex gap-2 print:hidden"><Button onClick={() => window.print()} variant="secondary">{t("pos.print")}</Button><Button onClick={() => setDone(null)}>{t("pos.newSale")}</Button></div>
      </Card>
    );
  }
  return (
    <div className="grid gap-5 lg:grid-cols-5">
      <div className="space-y-4 lg:col-span-3">
        <Card className="space-y-3">
          <form onSubmit={(e) => { e.preventDefault(); void scan.run(code); }} className="flex gap-2">
            <input autoFocus value={code} onChange={(e) => setCode(e.target.value)} placeholder={t("pos.scanHint")} dir="ltr" className="min-w-0 flex-1 rounded-xl border px-3 py-2.5 text-sm outline-none focus:border-brand" />
            <Button loading={scan.loading}>{t("pos.add")}</Button>
            <Button type="button" variant="secondary" onClick={() => setCamera(!camera)}>{camera ? t("pos.cameraOff") : t("pos.cameraOn")}</Button>
          </form>
          {camera && (supported ? <video ref={video} playsInline muted className="aspect-video w-full max-w-md rounded-xl bg-black object-cover" /> : <Alert tone="amber">{t("pos.noDetector")}</Alert>)}
          <ErrorText error={scan.error} />
          {msg && <Alert tone="amber">{msg}</Alert>}
        </Card>
        <Card className="divide-y p-0">
          {lines.length === 0 && <p className="p-6 text-center text-sm text-slate-500">{t("pos.empty")}</p>}
          {lines.map((l) => (
            <div key={l.variantId} className="flex items-center gap-3 p-3 text-sm">
              <div className="min-w-0 flex-1"><div className="truncate font-medium">{l.name}</div><div className="text-xs text-slate-500">{l.variantLabel} · {money(l.priceMinor, l.currency, locale)} · {t("pos.stock")} {l.available}</div></div>
              <div className="flex items-center rounded-full border"><button className="h-9 w-9" onClick={() => setLines(lines.map((x) => x.variantId === l.variantId ? { ...x, qty: Math.max(1, x.qty - 1) } : x))}>−</button><span className="w-7 text-center font-bold">{l.qty}</span><button className="h-9 w-9" onClick={() => setLines(lines.map((x) => x.variantId === l.variantId ? { ...x, qty: Math.min(x.qty + 1, Math.max(1, x.available)) } : x))}>+</button></div>
              <div className="w-24 text-end font-bold">{money(l.priceMinor * l.qty, l.currency, locale)}</div>
              <button className="text-slate-400 hover:text-red-600" onClick={() => setLines(lines.filter((x) => x.variantId !== l.variantId))} aria-label={t("common.delete")}>✕</button>
            </div>
          ))}
        </Card>
      </div>
      <div className="space-y-4 lg:col-span-2">
        <Card className="space-y-3">
          <div className="flex justify-between text-sm"><span>{t("pos.subtotal")}</span><b>{money(subtotal, cur, locale)}</b></div>
          <Field label={t("pos.discount")}><Input dir="ltr" inputMode="decimal" value={discount} onChange={(e) => setDiscount(e.target.value)} /></Field>
          <div className="flex justify-between text-xl font-extrabold"><span>{t("pos.total")}</span><span>{money(total, cur, locale)}</span></div>
          <Field label={t("pos.method")}><Select value={method} onChange={(e) => setMethod(e.target.value)}><option value="CASH">{t("pay.CASH")}</option><option value="CARD_POS">{t("pay.CARD_POS")}</option><option value="TRANSFER">{t("pay.TRANSFER")}</option></Select></Field>
          <div className="grid grid-cols-2 gap-3"><Field label={t("pos.customerName")}><Input value={name} onChange={(e) => setName(e.target.value)} /></Field><Field label={t("pos.customerPhone")} hint={t("pos.phoneHint")}><Input dir="ltr" inputMode="tel" value={phone} onChange={(e) => setPhone(e.target.value)} /></Field></div>
          <ErrorText error={sell.error} />
          <Button className="w-full" loading={sell.loading} disabled={lines.length === 0} onClick={() => void sell.run()}>{t("pos.charge")} {money(total, cur, locale)}</Button>
        </Card>
        <Card className="text-sm"><div className="text-slate-500">{t("pos.today")}</div><div className="text-lg font-bold">{today.data?.sales ?? 0} · {money(today.data?.totalMinor ?? 0, cur, locale)}</div></Card>
      </div>
    </div>
  );
}
