"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { dateTime } from "@/lib/format";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Alert, Badge, Button, Card, ErrorText, Field, Input } from "../ui";

type Key = { id: string; name: string; prefix: string; createdAt: string; lastUsedAt?: string | null; revokedAt?: string | null };
type Hook = { id: string; url: string; events: string[]; active: boolean; lastStatus?: string | null; failed: number };
type Delivery = { id: number; event: string; status: string; attempts: number; responseCode?: number | null; lastError?: string | null; createdAt: string };

/** Read-only API keys and signed webhooks for the shop's own systems. Secrets are shown once. */
export default function Developers() {
  const { t, locale } = useI18n();
  const keys = useApi<Key[]>("store/api-keys");
  const hooks = useApi<Hook[]>("store/webhooks");
  const [name, setName] = useState("");
  const [url, setUrl] = useState("");
  const [secret, setSecret] = useState<{ label: string; value: string } | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  const deliveries = useApi<Delivery[]>(open ? `store/webhooks/${open}/deliveries` : null);
  const origin = typeof window === "undefined" ? "" : window.location.origin;
  const addKey = useAction(async () => { const r = await api<{ key: string }>("store/api-keys", { body: { name } }); setSecret({ label: t("dev.keyOnce"), value: r.key }); setName(""); await keys.reload(); });
  const revoke = useAction(async (id: string) => { await api(`store/api-keys/${id}`, { method: "DELETE" }); await keys.reload(); });
  const addHook = useAction(async () => { const r = await api<{ secret: string }>("store/webhooks", { body: { url } }); setSecret({ label: t("dev.secretOnce"), value: r.secret }); setUrl(""); await hooks.reload(); });
  const delHook = useAction(async (id: string) => { await api(`store/webhooks/${id}`, { method: "DELETE" }); await hooks.reload(); });
  return (
    <div className="space-y-5">
      {secret && <Alert tone="green"><div className="font-semibold">{secret.label}</div><code dir="ltr" className="mt-1 block break-all rounded bg-white p-2 text-sm">{secret.value}</code></Alert>}
      <Card className="space-y-3">
        <div><h2 className="font-medium">{t("dev.keys")}</h2><p className="mt-1 text-sm text-slate-500">{t("dev.keysHint")}</p><code dir="ltr" className="mt-2 block break-all rounded bg-slate-50 p-2 text-xs">curl -H &quot;X-Api-Key: mk_live_…&quot; {origin}/api/v1/public/orders</code></div>
        <div className="flex flex-wrap items-end gap-3"><Field label={t("dev.keyName")}><Input value={name} onChange={(e) => setName(e.target.value)} /></Field><Button loading={addKey.loading} disabled={name.trim().length < 2} onClick={() => void addKey.run()}>{t("dev.createKey")}</Button></div>
        <ErrorText error={addKey.error ?? revoke.error} />
        {(keys.data ?? []).map((k) => <div key={k.id} className="flex flex-wrap items-center justify-between gap-2 border-t pt-2 text-sm"><span><b>{k.name}</b> <span dir="ltr" className="text-slate-500">{k.prefix}…</span> {k.revokedAt && <Badge tone="red">{t("dev.revoked")}</Badge>}<span className="block text-xs text-slate-500">{k.lastUsedAt ? `${t("dev.lastUsed")}: ${dateTime(k.lastUsedAt, locale)}` : t("dev.neverUsed")}</span></span>{!k.revokedAt && <Button size="sm" variant="ghost" onClick={() => void revoke.run(k.id)}>{t("dev.revoke")}</Button>}</div>)}
      </Card>
      <Card className="space-y-3">
        <div><h2 className="font-medium">{t("dev.webhooks")}</h2><p className="mt-1 text-sm text-slate-500">{t("dev.webhooksHint")}</p></div>
        <div className="flex flex-wrap items-end gap-3"><Field label={t("dev.url")}><Input dir="ltr" placeholder="https://" value={url} onChange={(e) => setUrl(e.target.value)} /></Field><Button loading={addHook.loading} disabled={url.trim().length < 8} onClick={() => void addHook.run()}>{t("dev.addWebhook")}</Button></div>
        <ErrorText error={addHook.error ?? delHook.error} />
        {(hooks.data ?? []).map((h) => (
          <div key={h.id} className="border-t pt-2 text-sm">
            <div className="flex flex-wrap items-center justify-between gap-2"><span dir="ltr" className="break-all">{h.url}</span><span className="flex items-center gap-2">{h.failed > 0 && <Badge tone="red">{t("dev.failed", { n: h.failed })}</Badge>}<Button size="sm" variant="secondary" onClick={() => setOpen(open === h.id ? null : h.id)}>{t("dev.deliveries")}</Button><Button size="sm" variant="ghost" onClick={() => void delHook.run(h.id)}>{t("common.delete")}</Button></span></div>
            {open === h.id && <ul className="mt-2 space-y-1 text-xs">{(deliveries.data ?? []).map((d) => <li key={d.id} className="flex justify-between"><span>{d.event} · {d.status} · {d.attempts}× {d.responseCode ? `HTTP ${d.responseCode}` : ""} {d.lastError ?? ""}</span><span className="text-slate-500">{dateTime(d.createdAt, locale)}</span></li>)}</ul>}
          </div>
        ))}
      </Card>
    </div>
  );
}
