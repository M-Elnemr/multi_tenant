"use client";

import Link from "next/link";
import { useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "../hooks";
import { useT } from "../i18n-provider";
import { Alert, Badge, Button, Card, ErrorText, Field, Input, Loading, PageHeader } from "../ui";
import type { ApiError } from "@/lib/client";
import { SetupPanel } from "./domain-setup/setup-panel";

type Domain = { id: string; host: string; kind: "SUBDOMAIN" | "CUSTOM"; primary: boolean; stopped?: boolean; verified: boolean; sslStatus?: string; alias?: string | null; dnsInstructions: { type: string; name: string; value: string }[] };

/** Own domain in three steps: add it, create two DNS records, click verify. Certificates are issued automatically. */
export default function DomainsPage() {
  const t = useT();
  const { data, loading, reload, error } = useApi<Domain[]>("tenant/domains");
  const [host, setHost] = useState("");
  const add = useAction(async () => { await api("tenant/domains", { body: { host } }); setHost(""); await reload(); });
  const verify = useAction(async (id: string) => { await api(`tenant/domains/${id}/verify`, { body: {} }); await reload(); });
  const makePrimary = useAction(async (id: string) => { await api(`tenant/domains/${id}/primary`, { body: {} }); await reload(); });
  const remove = useAction(async (id: string) => { await api(`tenant/domains/${id}`, { method: "DELETE" }); await reload(); });
  const notAllowed = (add.error as ApiError | undefined)?.code === "FEATURE_NOT_AVAILABLE";

  return (
    <>
      <PageHeader title={t("domains.title")} subtitle={t("domains.subtitle")} />
      {loading && !data ? <Loading /> : <ErrorText error={error} />}
      <div className="space-y-4">
        {data?.map((d) => (
          <Card key={d.id}>
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div>
                <p className="font-mono text-sm font-medium" dir="ltr">{d.host}</p>
                {d.alias && <p className="mt-0.5 text-xs text-slate-500">{t("domains.alsoWorks", { host: d.alias })}</p>}
                <div className="mt-1 flex flex-wrap gap-1.5">
                  {d.primary && <Badge tone="blue">{t("domains.primary")}</Badge>}
                  {d.stopped && <Badge tone="red">{t("domains.stopped")}</Badge>}
                  <Badge tone={d.verified ? "green" : "amber"}>{d.verified ? t("domains.verified") : t("domains.pending")}</Badge>
                  {d.kind === "SUBDOMAIN" && <Badge>{t("domains.free")}</Badge>}
                </div>
              </div>
              <div className="flex gap-2">
                {!d.verified && <Button size="sm" loading={verify.loading} onClick={() => verify.run(d.id)}>{t("domains.verify")}</Button>}
                {d.verified && !d.primary && !d.stopped && <Button size="sm" variant="secondary" onClick={() => makePrimary.run(d.id)}>{t("domains.makePrimary")}</Button>}
                {d.kind === "CUSTOM" && !d.primary && <Button size="sm" variant="ghost" onClick={() => remove.run(d.id)}>{t("common.delete")}</Button>}
              </div>
            </div>
            {d.kind === "CUSTOM" && !d.verified && <SetupPanel id={d.id} host={d.host} onVerified={() => void reload()} />}
            {d.kind === "CUSTOM" && d.verified && !d.stopped && (
              <div className="mt-4 flex items-start gap-3 rounded-2xl border border-emerald-200 bg-emerald-50 p-4">
                <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-emerald-500 text-lg font-bold text-white">✓</span>
                <div><p className="font-bold text-emerald-800">{t("domain.connected")}</p><p className="text-sm text-emerald-800/80">{t("domain.connectedBody", { host: d.host })}</p></div>
              </div>
            )}
          </Card>
        ))}
      </div>
      <Card className="mt-6">
        <h2 className="mb-3 font-medium">{t("domains.add")}</h2>
        <form onSubmit={(e) => { e.preventDefault(); void add.run(); }} className="space-y-3">
          <Field label={t("domains.host")} hint={t("domain.addHint")}><Input value={host} onChange={(e) => setHost(e.target.value)} placeholder="example.com" dir="ltr" required /></Field>
          {notAllowed ? <Alert tone="amber">{t("domains.upgrade")} <Link href="/dashboard/billing/pay?plan=STORE_PRO" className="font-medium underline">{t("sub.action")}</Link></Alert> : <ErrorText error={add.error} />}
          <Button type="submit" loading={add.loading}>{t("domains.addBtn")}</Button>
        </form>
        <ErrorText error={verify.error ?? makePrimary.error ?? remove.error} />
      </Card>
    </>
  );
}
