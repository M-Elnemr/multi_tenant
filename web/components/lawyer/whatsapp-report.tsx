"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Badge, Button, ErrorText } from "@/components/ui";

type Info = { available: boolean; enabled: boolean; status: "DISCONNECTED" | "CONNECTING" | "CONNECTED"; phone?: string | null; waReport: boolean };
type Qr = Partial<Info> & { qr?: string | null; pairingCode?: string | null };

/** The evening report on the lawyer's own WhatsApp: he links his phone once (like WhatsApp Web); every day at 8 pm tomorrow's agenda is sent from his number to itself. */
export function WhatsAppReportCard({ write }: { write: boolean }) {
  const { t } = useI18n();
  const info = useApi<Info>("lawyer/whatsapp");
  const [accepted, setAccepted] = useState(false);
  const [qr, setQr] = useState<Qr | null>(null);
  const [sent, setSent] = useState<boolean | null>(null);
  const enable = useAction(async () => { setQr(await api<Qr>("lawyer/whatsapp/enable", { body: { acceptedRisk: true } })); await info.reload(); });
  const disable = useAction(async () => { await api("lawyer/whatsapp/disable", { body: {} }); setQr(null); setAccepted(false); await info.reload(); });
  const toggle = useAction(async (on: boolean) => { await api("lawyer/profile", { method: "PATCH", body: { waReport: on } }); await info.reload(); });
  const now = useAction(async () => { const r = await api<{ sent: boolean }>("lawyer/whatsapp/report/send-now", { body: {} }); setSent(r.sent); });

  const waiting = !!info.data?.enabled && info.data.status !== "CONNECTED";
  useEffect(() => {
    if (!waiting) return;
    let stop = false;
    const tick = async () => { try { const q = await api<Qr>("lawyer/whatsapp/qr"); if (!stop) { setQr(q); if (q.status === "CONNECTED") await info.reload(); } } catch { /* next tick */ } };
    void tick();
    const id = setInterval(tick, 6000);
    return () => { stop = true; clearInterval(id); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [waiting]);

  if (!info.data) return null;
  const d = info.data;
  const connected = d.enabled && d.status === "CONNECTED";
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-slate-600">{t("lw.wa.what")}</p>
        {d.enabled && <Badge tone={connected ? "green" : "amber"}>{connected ? t("evo.connected") : t("evo.waiting")}</Badge>}
      </div>
      {!d.available && <Alert tone="blue">{t("evo.unavailable")}</Alert>}
      {d.available && !d.enabled && write && (
        <>
          <Alert tone="amber"><div className="font-semibold">⚠️ {t("evo.warnTitle")}</div><ul className="mt-1 list-disc space-y-1 ps-5 text-sm"><li>{t("evo.w1")}</li><li>{t("lw.wa.w2")}</li></ul></Alert>
          <label className="flex items-start gap-2 text-sm font-medium"><input type="checkbox" className="mt-1 h-5 w-5" checked={accepted} onChange={(e) => setAccepted(e.target.checked)} />{t("evo.accept")}</label>
          <ErrorText error={enable.error} />
          <Button loading={enable.loading} disabled={!accepted} onClick={() => void enable.run()}>{t("lw.wa.link")}</Button>
        </>
      )}
      {d.enabled && !connected && (
        <div className="space-y-3">
          <ol className="list-decimal space-y-1 ps-5 text-sm"><li>{t("evo.s1")}</li><li>{t("evo.s2")}</li><li>{t("evo.s3")}</li></ol>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          {qr?.qr ? <img src={qr.qr} alt="QR" className="h-56 w-56 rounded-xl border bg-white p-2" /> : <p className="text-sm text-slate-500">{t("evo.qrLoading")}</p>}
          {qr?.pairingCode && <p className="text-sm">{t("evo.pairing")}: <b dir="ltr" className="font-mono">{qr.pairingCode}</b></p>}
        </div>
      )}
      {connected && (
        <div className="space-y-3">
          <Alert tone="green">{t("lw.wa.linked")}{d.phone ? <> <b dir="ltr">+{d.phone}</b></> : null}</Alert>
          <label className="flex items-center justify-between gap-3 rounded-xl bg-slate-50 p-4"><span><b className="block text-slate-800">{t("lw.wa.daily")}</b><span className="text-sm text-slate-500">{t("lw.wa.dailyHint")}</span></span>
            <input type="checkbox" disabled={!write || toggle.loading} className="h-6 w-6 accent-[var(--brand)]" checked={d.waReport} onChange={(e) => void toggle.run(e.target.checked)} /></label>
          {write && <Button variant="secondary" loading={now.loading} onClick={() => { setSent(null); void now.run(); }}>{t("lw.wa.sendNow")}</Button>}
          <ErrorText error={now.error ?? toggle.error} />
          {sent === true && <Alert tone="green">{t("lw.wa.sent")}</Alert>}
          {sent === false && <Alert tone="amber">{t("lw.wa.notSent")}</Alert>}
        </div>
      )}
      {d.enabled && write && (
        <div className="border-t pt-3"><ErrorText error={disable.error} />
          <Button variant="danger" loading={disable.loading} onClick={() => { if (window.confirm(t("lw.wa.confirmOff"))) void disable.run(); }}>{t("lw.wa.unlink")}</Button></div>
      )}
    </div>
  );
}
