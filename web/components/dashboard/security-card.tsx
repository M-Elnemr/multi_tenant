"use client";

import QRCode from "qrcode";
import { useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Alert, Badge, Button, Card, ErrorText, Field, Input } from "../ui";

type Setup = { secret: string; otpauthUri: string };

/** Two-step sign-in with an authenticator app, and "sign out everywhere". Shown to the owner, staff and platform admins for their own account. */
export default function SecurityCard() {
  const { t } = useI18n();
  const status = useApi<{ enabled: boolean }>("auth/2fa");
  const [setup, setSetup] = useState<Setup | null>(null);
  const [qr, setQr] = useState("");   // the set-up QR as a picture, made when the secret arrives
  const [code, setCode] = useState("");
  const [recovery, setRecovery] = useState<string[] | null>(null);
  const [off, setOff] = useState(false);
  const [signedOut, setSignedOut] = useState(false);

  const start = useAction(async () => {
    const s = await api<Setup>("auth/2fa/setup", { body: {} });
    setQr(await QRCode.toDataURL(s.otpauthUri, { margin: 1, width: 192 }).catch(() => ""));
    setSetup(s); setCode("");
  });
  const enable = useAction(async () => {
    const r = await api<{ recoveryCodes: string[] }>("auth/2fa/enable", { body: { code } });
    setRecovery(r.recoveryCodes); setSetup(null); setCode(""); await status.reload();
  });
  const disable = useAction(async () => { await api("auth/2fa/disable", { body: { code } }); setOff(false); setCode(""); await status.reload(); });
  const everywhere = useAction(async () => { await api("auth/logout-all", { body: {} }); setSignedOut(true); });

  if (!status.data) return null;
  const on = status.data.enabled;
  return (
    <Card className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="font-medium">{t("sec.title")}</h2>
        <Badge tone={on ? "green" : "amber"}>{on ? t("sec.on") : t("sec.off")}</Badge>
      </div>
      <p className="text-sm text-slate-600">{t("sec.what")}</p>

      {recovery && (
        <Alert tone="amber">
          <p className="mb-2 font-semibold">{t("sec.recoveryTitle")}</p>
          <p className="mb-2 text-sm">{t("sec.recoveryHint")}</p>
          <div className="grid grid-cols-2 gap-1 font-mono text-sm" dir="ltr">{recovery.map((c) => <span key={c}>{c}</span>)}</div>
          <Button variant="secondary" size="sm" className="mt-3" onClick={() => setRecovery(null)}>{t("sec.saved")}</Button>
        </Alert>
      )}

      {!on && !setup && <Button onClick={() => void start.run()} loading={start.loading}>{t("sec.enable")}</Button>}
      {!on && setup && (
        <div className="space-y-3">
          <p className="text-sm">{t("sec.scan")}</p>
          <div className="flex flex-wrap items-center gap-4">
            {qr && /* a data: URL made here, nothing to optimise */ <img src={qr} alt="" width={192} height={192} className="rounded-lg border border-slate-200" />}
            <div className="min-w-0 text-sm">
              <p className="text-slate-500">{t("sec.manual")}</p>
              <p className="break-all font-mono font-semibold" dir="ltr">{setup.secret}</p>
            </div>
          </div>
          <Field label={t("sec.code")}>
            <Input value={code} onChange={(e) => setCode(e.target.value)} inputMode="numeric" dir="ltr" autoComplete="one-time-code" maxLength={8} className="max-w-40" />
          </Field>
          <div className="flex gap-2">
            <Button onClick={() => void enable.run()} loading={enable.loading} disabled={code.trim().length < 6}>{t("sec.confirm")}</Button>
            <Button variant="secondary" onClick={() => setSetup(null)}>{t("common.cancel")}</Button>
          </div>
        </div>
      )}
      {on && !off && <Button variant="secondary" onClick={() => { setOff(true); setCode(""); }}>{t("sec.disable")}</Button>}
      {on && off && (
        <div className="space-y-3">
          <Field label={t("sec.codeToDisable")}>
            <Input value={code} onChange={(e) => setCode(e.target.value)} dir="ltr" autoComplete="one-time-code" maxLength={12} className="max-w-48" />
          </Field>
          <div className="flex gap-2">
            <Button variant="danger" onClick={() => void disable.run()} loading={disable.loading} disabled={code.trim().length < 6}>{t("sec.disableNow")}</Button>
            <Button variant="secondary" onClick={() => setOff(false)}>{t("common.cancel")}</Button>
          </div>
        </div>
      )}
      <ErrorText error={start.error ?? enable.error ?? disable.error ?? everywhere.error} />

      <div className="border-t border-slate-100 pt-4">
        <p className="mb-2 text-sm text-slate-600">{t("sec.everywhereHint")}</p>
        {signedOut ? <Alert tone="green">{t("sec.everywhereDone")}</Alert> : <Button variant="secondary" onClick={() => void everywhere.run()} loading={everywhere.loading}>{t("sec.everywhere")}</Button>}
      </div>
    </Card>
  );
}
