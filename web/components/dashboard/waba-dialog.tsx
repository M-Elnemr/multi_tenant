"use client";

import { useState } from "react";
import { api, ApiError } from "@/lib/client";
import { useAction } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Alert, Button, ErrorText, Field, Input, Modal } from "../ui";

/** Where the owner finds the WhatsApp Business Account ID in Meta's own screens. */
export const WABA_LINK = "https://business.facebook.com/latest/settings/whatsapp_account";

/** True when an error is the server saying the account id is missing, so the screen can offer this dialog right next to it. */
export const isNoWaba = (e: unknown) => e instanceof ApiError && e.code === "NO_WABA";

/** Numbered steps, a link that opens Meta, a box to paste the id, and "find it for me" that asks Meta with the connected number. */
export function WabaDialog({ open, onClose, onSaved }: { open: boolean; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  const [id, setId] = useState("");
  const done = async () => { setId(""); onSaved(); onClose(); };
  const save = useAction(async () => { await api("store/ai/whatsapp/waba", { method: "PUT", body: { wabaId: id.trim() } }); await done(); });
  const detect = useAction(async () => { await api("store/ai/whatsapp/waba/detect", { body: {} }); await done(); });
  return (
    <Modal open={open} onClose={onClose} title={t("waba.title")}>
      <div className="space-y-4 text-sm">
        <p className="text-slate-600">{t("waba.what")}</p>
        <ol className="list-decimal space-y-2 ps-5">
          <li>{t("waba.s1")} <a href={WABA_LINK} target="_blank" rel="noreferrer" className="font-semibold text-brand underline">{t("waba.open")} ↗</a></li>
          <li>{t("waba.s2")}</li>
          <li>{t("waba.s3")}</li>
          <li>{t("waba.s4")}</li>
        </ol>
        <Field label={t("waba.label")} hint={t("waba.hint")}><Input dir="ltr" inputMode="numeric" placeholder="123456789012345" value={id} onChange={(e) => setId(e.target.value.replace(/\D/g, ""))} /></Field>
        <ErrorText error={save.error ?? detect.error} />
        <div className="flex flex-wrap gap-2">
          <Button loading={save.loading} disabled={id.length < 5} onClick={() => void save.run()}>{t("waba.save")}</Button>
          <Button variant="secondary" loading={detect.loading} onClick={() => void detect.run()}>{t("waba.detect")}</Button>
        </div>
        <Alert tone="blue">{t("waba.note")}</Alert>
      </div>
    </Modal>
  );
}
