"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/client";
import { useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Button, Empty, ErrorText, Field, Input, Select, Skeleton } from "@/components/ui";
import { ChannelIcon } from "@/components/channel-icons";
import { Icon } from "@/components/icons";
import { ConfirmDialog } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { SettingsCard } from "./kit";

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
    <form onSubmit={(e) => { e.preventDefault(); void choose.run(); }} className="space-y-3 rounded-[18px] bg-surface p-5 ring-2 ring-brand">
      <h3 className="font-semibold">{t("ai.pickTitle")}</h3>
      {found.data?.map((f) => {
        const key = `${f.kind}:${f.id}`;
        return (
          <label key={key} className="flex cursor-pointer items-center gap-3 rounded-[12px] p-3 text-sm ring-1 ring-line transition hover:ring-line-strong">
            <input type="checkbox" checked={picked.includes(key)} onChange={(e) => setPicked(e.target.checked ? [...picked, key] : picked.filter((k) => k !== key))} />
            <span className="min-w-0"><span className="font-semibold">{t(`ai.platform.${f.kind}`)}</span> · {f.name}{f.detail ? <span className="text-muted"> ({f.detail})</span> : null}</span>
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
  const toast = useToast();
  const [confirm, setConfirm] = useState<Channel | null>(null);
  const router = useRouter();
  const { data, reload } = useApi<Channel[]>("store/ai/channels");
  const login = useApi<{ available: boolean }>("store/ai/meta/available");
  const start = useAction(async () => { const r = await api<{ url: string }>("store/ai/meta/start", { body: {} }); window.location.assign(r.url); });
  const [f, setF] = useState({ platform: "FACEBOOK", externalId: "", accessToken: "", pageId: "" });
  const connect = useAction(async () => {
    await api("store/ai/channels", { body: { ...f, pageId: f.pageId || undefined } });
    setF({ ...f, externalId: "", accessToken: "", pageId: "" });
    await reload();
    toast(t("ai.connected"));
  });
  const remove = useAction(async (id: string) => { await api(`store/ai/channels/${id}`, { method: "DELETE" }); setConfirm(null); await reload(); toast(t("kit.channelRemoved")); });
  const idLabel = f.platform === "WHATSAPP" ? t("ai.waId") : f.platform === "INSTAGRAM" ? t("ai.igId") : t("ai.fbId");
  return (
    <div className="max-w-3xl space-y-5">
      <Alert tone="amber">{t("ai.channelsTest")}</Alert>
      {metaError && <Alert tone="red">{t(`ai.metaError.${metaError}`)}</Alert>}
      {write && pendingId && <Picker pendingId={pendingId} onDone={() => { router.replace("/dashboard/ai?tab=channels"); void reload(); }} />}
      {write && (
        <SettingsCard tone="ai" icon="link" title={t("ai.connectChannel")} description={login.data && !login.data.available ? t("ai.loginNotReady") : t("ai.loginHint")}
          action={<Button onClick={() => void start.run()} loading={start.loading} disabled={!login.data?.available}>{t("ai.loginFacebook")}</Button>}>
          <ErrorText error={start.error} />
        </SettingsCard>
      )}
      {!data ? <div className="space-y-2"><Skeleton className="h-16 w-full !rounded-[14px]" /><Skeleton className="h-16 w-full !rounded-[14px]" /></div> : data.length === 0 ? <Empty icon="chat" title={t("ai.noChannels")} /> : (
        <ul className="grid gap-3 sm:grid-cols-2">
          {data.map((c) => (
            <li key={c.id} className="flex items-center gap-3 rounded-[16px] bg-surface p-4 ring-1 ring-line">
              <span className="grid h-10 w-10 shrink-0 place-items-center rounded-[12px] bg-surface-2 ring-1 ring-inset ring-line"><ChannelIcon channel={c.platform} className="h-5 w-5" /></span>
              <div className="min-w-0 flex-1">
                <p className="truncate text-[13.5px] font-semibold text-ink">{c.name || t(`ai.platform.${c.platform}`)}</p>
                <p className="truncate text-xs text-muted">{t(`ai.platform.${c.platform}`)} · <span className="font-mono" dir="ltr">{c.externalId}</span></p>
                <span className="mt-1 inline-flex items-center gap-1 text-[11.5px] font-semibold text-emerald-700"><span className="h-1.5 w-1.5 rounded-full bg-emerald-500" />{t("ai.connected")}</span>
              </div>
              {write && <button type="button" onClick={() => setConfirm(c)} aria-label={t("ai.disconnect")} title={t("ai.disconnect")} className="grid h-8 w-8 shrink-0 place-items-center rounded-[9px] text-faint transition hover:bg-red-50 hover:text-red-600 pointer-coarse:h-11 pointer-coarse:w-11"><Icon name="close" className="h-4 w-4" /></button>}
            </li>
          ))}
        </ul>
      )}
      <ConfirmDialog open={!!confirm} onClose={() => setConfirm(null)} onConfirm={() => confirm && void remove.run(confirm.id)} loading={remove.loading} danger
        title={t("ai.disconnect")} body={confirm ? `${t(`ai.platform.${confirm.platform}`)} · ${confirm.name ?? ""}` : undefined} confirmLabel={t("ai.disconnect")} cancelLabel={t("common.cancel")} />
      {write && (
        <details className="group rounded-[18px] bg-surface ring-1 ring-line">
          <summary className="flex cursor-pointer list-none items-center justify-between gap-2 p-5 text-[14px] font-semibold text-ink">{t("ai.manualConnect")}<Icon name="chevron" className="h-4 w-4 text-muted transition-transform group-open:rotate-90" /></summary>
          <form onSubmit={(e) => { e.preventDefault(); void connect.run(); }} className="space-y-4 px-5 pb-5">
            <Field label={t("ai.platform")}>
              <Select value={f.platform} onChange={(e) => setF({ ...f, platform: e.target.value })}>{["FACEBOOK", "INSTAGRAM", "WHATSAPP"].map((p) => <option key={p} value={p}>{t(`ai.platform.${p}`)}</option>)}</Select>
            </Field>
            {f.platform === "FACEBOOK" && <Alert tone="blue"><div className="font-semibold">{t("ai.fbHelpTitle")}</div><ol className="mt-1 list-decimal space-y-1 ps-5 text-sm"><li>{t("ai.fbHelp1")}</li><li>{t("ai.fbHelp2")}</li><li>{t("ai.fbHelp3")}</li></ol></Alert>}
            <Field label={idLabel}><Input dir="ltr" inputMode="numeric" value={f.externalId} onChange={(e) => setF({ ...f, externalId: e.target.value.replace(/\D/g, "") })} required /></Field>
            {f.platform === "WHATSAPP" && <Field label={t("ai.wabaId")} hint={t("ai.wabaHint")}><Input dir="ltr" inputMode="numeric" value={f.pageId} onChange={(e) => setF({ ...f, pageId: e.target.value.replace(/\D/g, "") })} /></Field>}
            {f.platform === "INSTAGRAM" && <Field label={t("ai.pageIdFor")} hint={t("ai.pageIdHint")}><Input dir="ltr" inputMode="numeric" value={f.pageId} onChange={(e) => setF({ ...f, pageId: e.target.value.replace(/\D/g, "") })} /></Field>}
            <Field label={t("ai.accessToken")} hint={t("ai.accessTokenHint")}><Input dir="ltr" type="password" autoComplete="off" value={f.accessToken} onChange={(e) => setF({ ...f, accessToken: e.target.value })} required /></Field>
            <ErrorText error={connect.error ?? remove.error} />
            <Button type="submit" loading={connect.loading} disabled={!f.externalId || f.accessToken.length < 20}>{t("ai.connect")}</Button>
          </form>
        </details>
      )}
    </div>
  );
}
