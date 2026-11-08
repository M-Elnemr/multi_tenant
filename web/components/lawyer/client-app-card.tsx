"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { dateTime } from "@/lib/format";
import { useAction } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { ConfirmDialog } from "@/components/overlay";
import { Badge, Button, ErrorText, Modal } from "@/components/ui";

type Access = { portalEnabled: boolean; phone?: string; password?: string; message?: string; whatsapp?: string };

/** The client's access to the office app: turned on by the lawyer, with a temporary password sent to the client by WhatsApp. */
export function ClientAppCard({ clientId, phone, enabled, lastLogin, write, onChanged }: { clientId: string; phone: string; enabled: boolean; lastLogin?: string | null; write: boolean; onChanged: () => void }) {
  const { t, locale } = useI18n();
  const [shown, setShown] = useState<Access | null>(null);
  const [copied, setCopied] = useState(false);
  const [offAsk, setOffAsk] = useState(false);
  const run = useAction(async (action: "ENABLE" | "RESET" | "DISABLE") => {
    const r = await api<Access>(`lawyer/clients/${clientId}/app-access`, { body: { action } });
    if (r.password) { setShown(r); setCopied(false); }
    onChanged();
  });
  return (
    <div className="rounded-[18px] bg-surface p-5 shadow-card">
      <div className="mb-2 flex items-center justify-between gap-2">
        <p className="flex items-center gap-2 text-[14.5px] font-semibold text-ink"><Icon name="phone" className="h-4 w-4" />{t("lw.app.title")}</p>
        <Badge tone={enabled ? "green" : "slate"}>{enabled ? t("lw.app.on") : t("lw.app.off")}</Badge>
      </div>
      <p className="text-sm text-muted">{t("lw.app.what")}</p>
      {enabled && <p className="mt-2 text-xs text-muted">{lastLogin ? t("lw.app.lastLogin", { when: dateTime(lastLogin, locale) }) : t("lw.app.never")}</p>}
      {!phone && <p className="mt-2 rounded-[12px] bg-amber-50 px-3 py-2 text-xs text-amber-900">{t("lw.app.needPhone")}</p>}
      <ErrorText error={run.error} />
      {write && phone && (
        <div className="mt-3 flex flex-wrap gap-2">
          {!enabled ? <Button size="sm" loading={run.loading} onClick={() => run.run("ENABLE")}>{t("lw.app.enable")}</Button> : <>
            <Button size="sm" variant="secondary" loading={run.loading} onClick={() => run.run("RESET")}>{t("lw.app.reset")}</Button>
            <Button size="sm" variant="ghost" onClick={() => setOffAsk(true)}>{t("lw.app.disable")}</Button>
          </>}
        </div>
      )}
      <ConfirmDialog open={offAsk} onClose={() => setOffAsk(false)} onConfirm={async () => { await run.run("DISABLE"); setOffAsk(false); }} loading={run.loading} danger title={t("lw.app.disable")} body={t("lw.app.confirmOff")} confirmLabel={t("lw.app.disable")} cancelLabel={t("common.cancel")} />
      {shown && (
        <Modal open onClose={() => setShown(null)} title={t("lw.app.sendTitle")}>
          <div className="space-y-3">
            <div className="grid grid-cols-2 gap-2 rounded-xl bg-surface-2 p-3 text-sm">
              <span className="text-muted">{t("lw.app.loginNo")}</span><b dir="ltr" className="text-end font-mono">{shown.phone}</b>
              <span className="text-muted">{t("lw.app.tempPw")}</span><b dir="ltr" className="text-end font-mono text-lg tracking-widest">{shown.password}</b>
            </div>
            <p className="text-xs text-muted">{t("lw.app.onceHint")}</p>
            <pre className="whitespace-pre-wrap rounded-xl border border-line p-3 font-sans text-sm text-ink-2">{shown.message}</pre>
            <div className="flex flex-wrap gap-2">
              <a href={`https://wa.me/${shown.whatsapp}?text=${encodeURIComponent(shown.message ?? "")}`} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-2 rounded-xl bg-[#25d366] px-4 py-2.5 text-sm font-bold text-white">{t("lw.app.sendWa")}</a>
              <Button variant="secondary" onClick={async () => { try { await navigator.clipboard.writeText(shown.message ?? ""); setCopied(true); } catch { /* copy blocked: the text is on screen */ } }}>{copied ? t("lw.app.copied") : t("lw.app.copy")}</Button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  );
}
