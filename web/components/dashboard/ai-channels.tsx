"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Button, ErrorText, Field, Input, Select } from "@/components/ui";

type Channel = { id: string; platform: string; externalId: string; name?: string; status: string };

/** Connect the shop's Facebook page, Instagram account or WhatsApp Business number to the assistant (Meta development-mode testing). */
export function AiChannels({ write }: { write: boolean }) {
  const { t } = useI18n();
  const { data, reload } = useApi<Channel[]>("store/ai/channels");
  const [f, setF] = useState({ platform: "FACEBOOK", externalId: "", accessToken: "", pageId: "" });
  const connect = useAction(async () => {
    await api("store/ai/channels", { body: { ...f, pageId: f.pageId || undefined } });
    setF({ ...f, externalId: "", accessToken: "", pageId: "" });
    await reload();
  });
  const remove = useAction(async (id: string) => { await api(`store/ai/channels/${id}`, { method: "DELETE" }); await reload(); });
  const idLabel = f.platform === "WHATSAPP" ? t("ai.waId") : f.platform === "INSTAGRAM" ? t("ai.igId") : t("ai.fbId");
  return (
    <div className="max-w-2xl space-y-5">
      <Alert tone="amber">{t("ai.channelsTest")}</Alert>
      <ul className="space-y-2">
        {data?.length === 0 && <p className="text-sm text-slate-500">{t("ai.noChannels")}</p>}
        {data?.map((c) => (
          <li key={c.id} className="flex items-center justify-between gap-3 rounded-xl border bg-white p-3 text-sm">
            <div className="min-w-0"><p className="font-semibold">{t(`ai.platform.${c.platform}`)} · <span className="font-normal text-slate-600">{c.name}</span></p><p className="font-mono text-xs text-slate-400" dir="ltr">{c.externalId}</p></div>
            <span className="flex items-center gap-2"><span className="rounded-full bg-emerald-100 px-2 py-0.5 text-xs font-semibold text-emerald-700">{t("ai.connected")}</span>{write && <Button size="sm" variant="ghost" onClick={() => void remove.run(c.id)}>{t("ai.disconnect")}</Button>}</span>
          </li>
        ))}
      </ul>
      {write && (
        <form onSubmit={(e) => { e.preventDefault(); void connect.run(); }} className="space-y-3 rounded-xl border bg-white p-4">
          <h3 className="font-semibold">{t("ai.connectChannel")}</h3>
          <Field label={t("ai.platform")}>
            <Select value={f.platform} onChange={(e) => setF({ ...f, platform: e.target.value })}>{["FACEBOOK", "INSTAGRAM", "WHATSAPP"].map((p) => <option key={p} value={p}>{t(`ai.platform.${p}`)}</option>)}</Select>
          </Field>
          <Field label={idLabel}><Input dir="ltr" inputMode="numeric" value={f.externalId} onChange={(e) => setF({ ...f, externalId: e.target.value.replace(/\D/g, "") })} required /></Field>
          {f.platform === "INSTAGRAM" && <Field label={t("ai.pageIdFor")} hint={t("ai.pageIdHint")}><Input dir="ltr" inputMode="numeric" value={f.pageId} onChange={(e) => setF({ ...f, pageId: e.target.value.replace(/\D/g, "") })} /></Field>}
          <Field label={t("ai.accessToken")} hint={t("ai.accessTokenHint")}><Input dir="ltr" type="password" autoComplete="off" value={f.accessToken} onChange={(e) => setF({ ...f, accessToken: e.target.value })} required /></Field>
          <ErrorText error={connect.error ?? remove.error} />
          <Button type="submit" loading={connect.loading} disabled={!f.externalId || f.accessToken.length < 20}>{t("ai.connect")}</Button>
        </form>
      )}
    </div>
  );
}
