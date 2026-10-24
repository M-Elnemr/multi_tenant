"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Alert, Badge, Button, Card, ErrorText, Field, Input } from "../ui";

type Info = { available: boolean; enabled: boolean; status: "DISCONNECTED" | "CONNECTING" | "CONNECTED"; phone?: string | null };
type Qr = Info & { qr?: string | null; pairingCode?: string | null };

/** Optional unofficial mode: the shop's own WhatsApp number linked by QR code. Off by default; the owner must read and accept the warning first. */
export default function EvolutionCard({ write }: { write: boolean }) {
  const { t } = useI18n();
  const info = useApi<Info>("store/ai/evolution");
  const [accepted, setAccepted] = useState(false);
  const [qr, setQr] = useState<Qr | null>(null);
  const [number, setNumber] = useState("");
  const [sentOk, setSentOk] = useState(false);

  const enable = useAction(async () => { setQr(await api<Qr>("store/ai/evolution/enable", { body: { acceptedRisk: true } })); await info.reload(); });
  const disable = useAction(async () => { await api("store/ai/evolution/disable", { body: {} }); setQr(null); setAccepted(false); await info.reload(); });
  const test = useAction(async () => { await api("store/ai/evolution/test", { body: { number } }); setSentOk(true); });

  // while the phone is not linked yet, keep asking for the current QR (WhatsApp rotates it every minute or so)
  const waiting = !!info.data?.enabled && info.data.status !== "CONNECTED";
  useEffect(() => {
    if (!waiting) return;
    let stop = false;
    const tick = async () => { try { const q = await api<Qr>("store/ai/evolution/qr"); if (!stop) { setQr(q); if (q.status === "CONNECTED") await info.reload(); } } catch { /* the next tick tries again */ } };
    void tick();
    const id = setInterval(tick, 6000);
    return () => { stop = true; clearInterval(id); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [waiting]);

  if (!info.data) return null;
  if (!info.data.available) return <Card className="space-y-2"><h2 className="font-medium">{t("evo.title")}</h2><Alert tone="blue">{t("evo.unavailable")}</Alert></Card>;
  const connected = info.data.enabled && info.data.status === "CONNECTED";

  return (
    <Card className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="font-medium">{t("evo.title")}</h2>
        {info.data.enabled && <Badge tone={connected ? "green" : "amber"}>{connected ? t("evo.connected") : t("evo.waiting")}</Badge>}
      </div>
      <p className="text-sm text-slate-600">{t("evo.what")}</p>
      <Alert tone="amber">
        <div className="font-semibold">⚠️ {t("evo.warnTitle")}</div>
        <ul className="mt-1 list-disc space-y-1 ps-5 text-sm"><li>{t("evo.w1")}</li><li>{t("evo.w2")}</li><li>{t("evo.w3")}</li><li>{t("evo.w4")}</li></ul>
      </Alert>

      {!info.data.enabled && write && (
        <>
          <label className="flex items-start gap-2 text-sm font-medium"><input type="checkbox" className="mt-1 h-5 w-5" checked={accepted} onChange={(e) => setAccepted(e.target.checked)} />{t("evo.accept")}</label>
          <ErrorText error={enable.error} />
          <Button loading={enable.loading} disabled={!accepted} onClick={() => void enable.run()}>{t("evo.enable")}</Button>
        </>
      )}

      {info.data.enabled && !connected && (
        <div className="space-y-3">
          <ol className="list-decimal space-y-1 ps-5 text-sm"><li>{t("evo.s1")}</li><li>{t("evo.s2")}</li><li>{t("evo.s3")}</li></ol>
          {qr?.qr ? <img src={qr.qr} alt="QR" className="h-56 w-56 rounded-xl border bg-white p-2" /> : <p className="text-sm text-slate-500">{t("evo.qrLoading")}</p>}
          {qr?.pairingCode && <p className="text-sm">{t("evo.pairing")}: <b dir="ltr" className="font-mono">{qr.pairingCode}</b></p>}
        </div>
      )}

      {connected && (
        <div className="space-y-3">
          <Alert tone="green">{t("evo.linked")}{info.data.phone ? <> <b dir="ltr">+{info.data.phone}</b></> : null}</Alert>
          <p className="text-sm text-slate-600">{t("evo.does")}</p>
          {write && (
            <form onSubmit={(e) => { e.preventDefault(); setSentOk(false); void test.run(); }} className="flex flex-wrap items-end gap-2">
              <Field label={t("evo.testTo")} hint={t("evo.testHint")}><Input dir="ltr" inputMode="tel" placeholder="201012345678" value={number} onChange={(e) => setNumber(e.target.value)} /></Field>
              <Button variant="secondary" loading={test.loading} disabled={number.replace(/\D/g, "").length < 8}>{t("evo.sendTest")}</Button>
            </form>
          )}
          <ErrorText error={test.error} />
          {sentOk && <Alert tone="green">{t("evo.sent")}</Alert>}
        </div>
      )}

      {info.data.enabled && write && (
        <div className="border-t pt-3">
          <ErrorText error={disable.error} />
          <Button variant="danger" loading={disable.loading} onClick={() => { if (window.confirm(t("evo.confirmOff"))) void disable.run(); }}>{t("evo.disable")}</Button>
        </div>
      )}
    </Card>
  );
}
