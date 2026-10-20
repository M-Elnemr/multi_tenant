"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/client";
import { useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Button, ErrorText, Field, Input, Select } from "@/components/ui";

type Channel = { id: string; platform: string; externalId: string; name?: string; status: string };

type Found = { kind: string; id: string; name: string; detail?: string };

/** After Facebook login: tick what to connect. The tokens never reach the browser. */
function Picker({ pendingId, onDone }: { pendingId: string; onDone: () => void }) {
  const { t } = useI18n();
  const found = useApi<Found[]>(`store/ai/meta/pending/${pendingId}`);
  const [picked, setPicked] = useState<string[]>([]);
  const choose = useAction(async () => { await api(`store/ai/meta/pending/${pendingId}/connect`, { body: { keys: picked } }); onDone(); });
  if (found.error) return <Alert tone="amber">{t("ai.pickExpired")}</Alert>;
  return (
    <form onSubmit={(e) => { e.preventDefault(); void choose.run(); }} className="space-y-3 rounded-xl border-2 border-brand bg-white p-4">
      <h3 className="font-semibold">{t("ai.pickTitle")}</h3>
      {found.data?.map((f) => {
        const key = `${f.kind}:${f.id}`;
        return (
          <label key={key} className="flex items-center gap-3 rounded-lg border p-3 text-sm">
            <input type="checkbox" checked={picked.includes(key)} onChange={(e) => setPicked(e.target.checked ? [...picked, key] : picked.filter((k) => k !== key))} />
            <span className="min-w-0"><span className="font-semibold">{t(`ai.platform.${f.kind}`)}</span> · {f.name}{f.detail ? <span className="text-slate-500"> ({f.detail})</span> : null}</span>
          </label>
        );
      })}
      <ErrorText error={choose.error} />
      <Button type="submit" loading={choose.loading} disabled={picked.length === 0}>{t("ai.connectChosen")}</Button>
    </form>
  );
}

/** Connect the shop's Facebook page, Instagram account or WhatsApp Business number to the assistant (Meta development-mode testing). */
export function AiChannels({ write, pendingId, metaError }: { write: boolean; pendingId?: string | null; metaError?: string | null }) {
  const { t } = useI18n();
  const router = useRouter();
  const { data, reload } = useApi<Channel[]>("store/ai/channels");
  const login = useApi<{ available: boolean }>("store/ai/meta/available");
  const start = useAction(async () => { const r = await api<{ url: string }>("store/ai/meta/start", { body: {} }); window.location.assign(r.url); });
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
      {metaError && <Alert tone="red">{t(`ai.metaError.${metaError}`)}</Alert>}
      {write && pendingId && <Picker pendingId={pendingId} onDone={() => { router.replace("/dashboard/ai"); void reload(); }} />}
      {write && (
        <div className="space-y-2 rounded-xl border bg-white p-4">
          <Button onClick={() => void start.run()} loading={start.loading} disabled={!login.data?.available}>{t("ai.loginFacebook")}</Button>
          <p className="text-xs text-slate-500">{login.data && !login.data.available ? t("ai.loginNotReady") : t("ai.loginHint")}</p>
          <ErrorText error={start.error} />
        </div>
      )}
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
        <details className="rounded-xl border bg-white p-4"><summary className="cursor-pointer text-sm font-semibold">{t("ai.manualConnect")}</summary>
        <form onSubmit={(e) => { e.preventDefault(); void connect.run(); }} className="mt-3 space-y-3">
          <Field label={t("ai.platform")}>
            <Select value={f.platform} onChange={(e) => setF({ ...f, platform: e.target.value })}>{["FACEBOOK", "INSTAGRAM", "WHATSAPP"].map((p) => <option key={p} value={p}>{t(`ai.platform.${p}`)}</option>)}</Select>
          </Field>
          <Field label={idLabel}><Input dir="ltr" inputMode="numeric" value={f.externalId} onChange={(e) => setF({ ...f, externalId: e.target.value.replace(/\D/g, "") })} required /></Field>
          {f.platform === "INSTAGRAM" && <Field label={t("ai.pageIdFor")} hint={t("ai.pageIdHint")}><Input dir="ltr" inputMode="numeric" value={f.pageId} onChange={(e) => setF({ ...f, pageId: e.target.value.replace(/\D/g, "") })} /></Field>}
          <Field label={t("ai.accessToken")} hint={t("ai.accessTokenHint")}><Input dir="ltr" type="password" autoComplete="off" value={f.accessToken} onChange={(e) => setF({ ...f, accessToken: e.target.value })} required /></Field>
          <ErrorText error={connect.error ?? remove.error} />
          <Button type="submit" loading={connect.loading} disabled={!f.externalId || f.accessToken.length < 20}>{t("ai.connect")}</Button>
        </form></details>
      )}
    </div>
  );
}
