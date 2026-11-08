"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { dateTime } from "@/lib/format";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Icon } from "../icons";
import { ConfirmDialog } from "../overlay";
import { useToast } from "../toast";
import { Badge, Button, Empty, ErrorText, Field, Input, SecretBox } from "../ui";
import { SettingsCard } from "./kit";

type Key = { id: string; name: string; prefix: string; createdAt: string; lastUsedAt?: string | null; revokedAt?: string | null };
type Hook = { id: string; url: string; events: string[]; active: boolean; lastStatus?: string | null; failed: number };
type Delivery = { id: number; event: string; status: string; attempts: number; responseCode?: number | null; lastError?: string | null; createdAt: string };

/** Read-only API keys and signed webhooks for the shop's own systems. Secrets are shown once. */
export default function Developers() {
  const { t, locale } = useI18n();
  const toast = useToast();
  const keys = useApi<Key[]>("store/api-keys");
  const hooks = useApi<Hook[]>("store/webhooks");
  const [name, setName] = useState("");
  const [url, setUrl] = useState("");
  const [secret, setSecret] = useState<{ label: string; value: string } | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  const [confirm, setConfirm] = useState<{ kind: "key" | "hook"; id: string } | null>(null);
  const deliveries = useApi<Delivery[]>(open ? `store/webhooks/${open}/deliveries` : null);
  const origin = typeof window === "undefined" ? "" : window.location.origin;
  const addKey = useAction(async () => { const r = await api<{ key: string }>("store/api-keys", { body: { name } }); setSecret({ label: t("dev.keyOnce"), value: r.key }); setName(""); await keys.reload(); });
  const revoke = useAction(async (id: string) => { await api(`store/api-keys/${id}`, { method: "DELETE" }); setConfirm(null); await keys.reload(); toast(t("dev.revoked")); });
  const addHook = useAction(async () => { const r = await api<{ secret: string }>("store/webhooks", { body: { url } }); setSecret({ label: t("dev.secretOnce"), value: r.secret }); setUrl(""); await hooks.reload(); });
  const delHook = useAction(async (id: string) => { await api(`store/webhooks/${id}`, { method: "DELETE" }); setConfirm(null); await hooks.reload(); toast(t("common.delete")); });
  return (
    <div className="max-w-3xl space-y-5">
      {secret && <SecretBox label={secret.label} value={secret.value} />}
      <SettingsCard icon="lock" title={t("dev.keys")} description={t("dev.keysHint")}>
        <code dir="ltr" className="block overflow-x-auto rounded-[12px] bg-ink px-4 py-3 text-xs text-surface">curl -H &quot;X-Api-Key: mk_live_…&quot; {origin}/api/v1/public/orders</code>
        <div className="mt-4 flex flex-wrap items-end gap-3"><Field label={t("dev.keyName")} className="min-w-[12rem] flex-1"><Input value={name} onChange={(e) => setName(e.target.value)} /></Field><Button loading={addKey.loading} disabled={name.trim().length < 2} onClick={() => void addKey.run()}><Icon name="plus" className="h-4 w-4" />{t("dev.createKey")}</Button></div>
        <div className="mt-3"><ErrorText error={addKey.error ?? revoke.error} /></div>
        {(keys.data ?? []).length > 0 && (
          <ul className="mt-4 divide-y divide-line rounded-[14px] ring-1 ring-line">
            {(keys.data ?? []).map((k) => (
              <li key={k.id} className="flex flex-wrap items-center justify-between gap-2 px-4 py-3 text-sm">
                <span className="min-w-0"><b className="text-ink">{k.name}</b> <span dir="ltr" className="font-mono text-xs text-muted">{k.prefix}…</span> {k.revokedAt && <Badge tone="red">{t("dev.revoked")}</Badge>}<span className="block text-xs text-muted">{k.lastUsedAt ? `${t("dev.lastUsed")}: ${dateTime(k.lastUsedAt, locale)}` : t("dev.neverUsed")}</span></span>
                {!k.revokedAt && <Button size="sm" variant="ghost" onClick={() => setConfirm({ kind: "key", id: k.id })}>{t("dev.revoke")}</Button>}
              </li>
            ))}
          </ul>
        )}
      </SettingsCard>
      <SettingsCard icon="link" title={t("dev.webhooks")} description={t("dev.webhooksHint")}>
        <div className="flex flex-wrap items-end gap-3"><Field label={t("dev.url")} className="min-w-[12rem] flex-1"><Input dir="ltr" placeholder="https://" value={url} onChange={(e) => setUrl(e.target.value)} /></Field><Button loading={addHook.loading} disabled={url.trim().length < 8} onClick={() => void addHook.run()}><Icon name="plus" className="h-4 w-4" />{t("dev.addWebhook")}</Button></div>
        <div className="mt-3"><ErrorText error={addHook.error ?? delHook.error} /></div>
        {(hooks.data ?? []).length === 0 && <div className="mt-4"><Empty compact icon="link">{t("dev.webhooksHint")}</Empty></div>}
        {(hooks.data ?? []).length > 0 && (
          <ul className="mt-4 divide-y divide-line rounded-[14px] ring-1 ring-line">
            {(hooks.data ?? []).map((h) => (
              <li key={h.id} className="px-4 py-3 text-sm">
                <div className="flex flex-wrap items-center justify-between gap-2"><span dir="ltr" className="min-w-0 break-all font-mono text-xs text-ink-2">{h.url}</span><span className="flex flex-wrap items-center gap-2">{h.failed > 0 && <Badge tone="red">{t("dev.failed", { n: h.failed })}</Badge>}<Button size="sm" variant="secondary" onClick={() => setOpen(open === h.id ? null : h.id)}>{t("dev.deliveries")}</Button><Button size="sm" variant="ghost" onClick={() => setConfirm({ kind: "hook", id: h.id })}>{t("common.delete")}</Button></span></div>
                {open === h.id && <ul className="mt-2 space-y-1 rounded-[10px] bg-surface-2 p-3 text-xs">{(deliveries.data ?? []).map((d) => <li key={d.id} className="flex flex-wrap justify-between gap-2"><span>{d.event} · {d.status} · {d.attempts}× {d.responseCode ? `HTTP ${d.responseCode}` : ""} {d.lastError ?? ""}</span><span className="text-muted">{dateTime(d.createdAt, locale)}</span></li>)}</ul>}
              </li>
            ))}
          </ul>
        )}
      </SettingsCard>
      <ConfirmDialog open={!!confirm} onClose={() => setConfirm(null)} danger loading={revoke.loading || delHook.loading}
        onConfirm={() => confirm && void (confirm.kind === "key" ? revoke.run(confirm.id) : delHook.run(confirm.id))}
        title={confirm?.kind === "key" ? t("dev.revoke") : t("common.delete")} confirmLabel={confirm?.kind === "key" ? t("dev.revoke") : t("common.delete")} cancelLabel={t("common.cancel")} />
    </div>
  );
}
