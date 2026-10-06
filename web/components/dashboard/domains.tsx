"use client";

import Link from "next/link";
import { useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "../hooks";
import { useT } from "../i18n-provider";
import { Alert, Badge, Button, Card, ErrorText, Field, Input, Loading, PageHeader } from "../ui";
import type { ApiError } from "@/lib/client";

type Domain = { id: string; host: string; kind: "SUBDOMAIN" | "CUSTOM"; primary: boolean; verified: boolean; sslStatus?: string; dnsInstructions: { type: string; name: string; value: string }[] };

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
                <div className="mt-1 flex flex-wrap gap-1.5">
                  {d.primary && <Badge tone="blue">{t("domains.primary")}</Badge>}
                  <Badge tone={d.verified ? "green" : "amber"}>{d.verified ? t("domains.verified") : t("domains.pending")}</Badge>
                  {d.kind === "SUBDOMAIN" && <Badge>{t("domains.free")}</Badge>}
                </div>
              </div>
              <div className="flex gap-2">
                {!d.verified && <Button size="sm" loading={verify.loading} onClick={() => verify.run(d.id)}>{t("domains.verify")}</Button>}
                {d.verified && !d.primary && <Button size="sm" variant="secondary" onClick={() => makePrimary.run(d.id)}>{t("domains.makePrimary")}</Button>}
                {d.kind === "CUSTOM" && !d.primary && <Button size="sm" variant="ghost" onClick={() => remove.run(d.id)}>{t("common.delete")}</Button>}
              </div>
            </div>
            {!d.verified && d.dnsInstructions.length > 0 && (
              <div className="mt-4 space-y-2 rounded-lg bg-slate-50 p-4 text-sm">
                <p className="font-medium">{t("domains.dnsTitle")}</p>
                <div className="overflow-x-auto" dir="ltr">
                  <table className="w-full text-start font-mono text-xs"><tbody>
                    {d.dnsInstructions.map((r) => (<tr key={r.type}><td className="pe-3 font-bold">{r.type}</td><td className="pe-3">{r.name}</td><td className="break-all">{r.value}</td></tr>))}
                  </tbody></table>
                </div>
                <p className="text-xs text-slate-500">{t("domains.dnsHelp")}</p>
              </div>
            )}
          </Card>
        ))}
      </div>
      <Card className="mt-6">
        <h2 className="mb-3 font-medium">{t("domains.add")}</h2>
        <form onSubmit={(e) => { e.preventDefault(); void add.run(); }} className="space-y-3">
          <Field label={t("domains.host")} hint={t("domains.hostHint")}><Input value={host} onChange={(e) => setHost(e.target.value)} placeholder="www.my-brand.com" dir="ltr" required /></Field>
          {notAllowed ? <Alert tone="amber">{t("domains.upgrade")} <Link href="/dashboard/billing" className="font-medium underline">{t("nav.billing")}</Link></Alert> : <ErrorText error={add.error} />}
          <Button type="submit" loading={add.loading}>{t("domains.addBtn")}</Button>
        </form>
        <ErrorText error={verify.error ?? makePrimary.error ?? remove.error} />
      </Card>
    </>
  );
}
