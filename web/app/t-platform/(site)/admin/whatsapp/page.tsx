"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Badge, Button, Card, ErrorText, Field, Input, PageHeader } from "@/components/ui";

type Info = { available: boolean; enabled: boolean; status: "DISCONNECTED" | "CONNECTING" | "CONNECTED"; phone?: string | null };
type Qr = Info & { qr?: string | null; pairingCode?: string | null };

/** The platform's own WhatsApp number (free, linked with a QR code). It sends the one-time codes that prove a phone number belongs to the person registering. */
export default function PlatformWhatsApp() {
  const { t } = useI18n();
  const info = useApi<Info>("platform/whatsapp");
  const [qr, setQr] = useState<Qr | null>(null);
  const [number, setNumber] = useState("");
  const [sent, setSent] = useState(false);
  const enable = useAction(async () => { setQr(await api<Qr>("platform/whatsapp/enable", { body: {} })); await info.reload(); });
  const disable = useAction(async () => { await api("platform/whatsapp/disable", { body: {} }); setQr(null); await info.reload(); });
  const test = useAction(async () => { await api("platform/whatsapp/test", { body: { number } }); setSent(true); });
  const waiting = !!info.data?.enabled && info.data.status !== "CONNECTED";
  useEffect(() => {
    if (!waiting) return;
    let stop = false;
    const tick = async () => { try { const q = await api<Qr>("platform/whatsapp/qr"); if (!stop) { setQr(q); if (q.status === "CONNECTED") await info.reload(); } } catch { /* the next tick tries again */ } };
    void tick();
    const id = setInterval(tick, 6000);
    return () => { stop = true; clearInterval(id); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [waiting]);
  const d = info.data;
  if (!d) return null;
  const connected = d.enabled && d.status === "CONNECTED";
  return (
    <>
      <PageHeader title={t("pwa.title")} subtitle={t("pwa.subtitle")} />
      <Card className="max-w-2xl space-y-4">
        {!d.available && <Alert tone="amber">{t("evo.unavailable")}</Alert>}
        <div className="flex items-center justify-between gap-2"><span className="font-medium">{t("pwa.number")}</span>{d.enabled && <Badge tone={connected ? "green" : "amber"}>{connected ? t("evo.connected") : t("evo.waiting")}</Badge>}</div>
        <p className="text-sm text-slate-600">{t("pwa.what")}</p>
        <Alert tone="amber">{t("pwa.warn")}</Alert>
        {!d.enabled && d.available && <><ErrorText error={enable.error} /><Button loading={enable.loading} onClick={() => void enable.run()}>{t("pwa.link")}</Button></>}
        {d.enabled && !connected && (
          <div className="space-y-3">
            <ol className="list-decimal space-y-1 ps-5 text-sm"><li>{t("evo.s1")}</li><li>{t("evo.s2")}</li><li>{t("evo.s3")}</li></ol>
            {qr?.qr ? <img src={qr.qr} alt="QR" className="h-56 w-56 rounded-xl border bg-white p-2" /> : <p className="text-sm text-slate-500">{t("evo.qrLoading")}</p>}
          </div>
        )}
        {connected && (
          <div className="space-y-3">
            <Alert tone="green">{t("evo.linked")}{d.phone ? <> <b dir="ltr">+{d.phone}</b></> : null}</Alert>
            <form onSubmit={(e) => { e.preventDefault(); setSent(false); void test.run(); }} className="flex flex-wrap items-end gap-2">
              <Field label={t("evo.testTo")} hint={t("evo.testHint")}><Input dir="ltr" inputMode="tel" placeholder="201012345678" value={number} onChange={(e) => setNumber(e.target.value)} /></Field>
              <Button variant="secondary" loading={test.loading} disabled={number.replace(/\D/g, "").length < 8}>{t("evo.sendTest")}</Button>
            </form>
            <ErrorText error={test.error} />
            {sent && <Alert tone="green">{t("evo.sent")}</Alert>}
          </div>
        )}
        {d.enabled && <div className="border-t pt-3"><ErrorText error={disable.error} /><Button variant="danger" loading={disable.loading} onClick={() => { if (window.confirm(t("evo.confirmOff"))) void disable.run(); }}>{t("evo.disable")}</Button></div>}
      </Card>
    </>
  );
}
