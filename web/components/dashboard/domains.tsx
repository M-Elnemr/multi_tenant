"use client";

import Link from "next/link";
import { useState } from "react";
import { api } from "@/lib/client";
import { useAction, useApi } from "../hooks";
import { useT } from "../i18n-provider";
import { Alert, Badge, Button, ErrorText, Field, Input, KpiStrip, Loading, PageHeader, Tabs } from "../ui";
import { Icon } from "../icons";
import { ConfirmDialog, Sheet } from "../overlay";
import { useToast } from "../toast";
import { SettingsCard } from "./kit";
import type { ApiError } from "@/lib/client";
import { SetupPanel } from "./domain-setup/setup-panel";
import { useSection } from "./section";
import FindDomainScreen from "./domains/search";
import { DomainOrderPanel } from "./domains/order-panel";
import { isTerminal, type OrderListItem } from "./domains/types";

type Domain = {
  id: string; host: string; kind: "SUBDOMAIN" | "CUSTOM"; primary: boolean; stopped?: boolean; verified: boolean; sslStatus?: string; alias?: string | null; dnsInstructions: { type: string; name: string; value: string }[];
  /** Additive fields the backend adds to this same response for the purchase flow; absent on domains created before it, and defaults to "connected". */
  source?: "CONNECTED" | "PURCHASED"; domainOrderId?: string | null;
};

const daysUntil = (iso: string) => Math.floor((new Date(iso).getTime() - Date.now()) / 86_400_000);

/** Domains: the free address plus two ways to get your own — connect one you already own, or buy one without leaving the dashboard. */
export default function DomainsPage() {
  const t = useT();
  const toast = useToast();
  const [confirm, setConfirm] = useState<Domain | null>(null);
  const [detailsFor, setDetailsFor] = useState<Domain | null>(null);
  const { data, loading, reload, error } = useApi<Domain[]>("tenant/domains");
  const orders = useApi<OrderListItem[]>("store/domains/orders");
  const [tab, setTab] = useSection(["my", "buy"] as const, "my");
  const [host, setHost] = useState("");
  const add = useAction(async () => { await api("tenant/domains", { body: { host } }); setHost(""); await reload(); });
  const verify = useAction(async (id: string) => { await api(`tenant/domains/${id}/verify`, { body: {} }); await reload(); });
  const makePrimary = useAction(async (id: string) => { await api(`tenant/domains/${id}/primary`, { body: {} }); await reload(); toast(t("domains.primary")); });
  const remove = useAction(async (id: string) => { await api(`tenant/domains/${id}`, { method: "DELETE" }); setConfirm(null); await reload(); toast(t("common.delete")); });
  const notAllowed = (add.error as ApiError | undefined)?.code === "FEATURE_NOT_AVAILABLE";

  const domainsList = data ?? [];
  const ordersList = orders.data ?? [];
  const activeCount = domainsList.filter((d) => d.verified && !d.stopped).length;
  const domainsPending = domainsList.filter((d) => !d.verified).length;
  const ordersAwaitingDomain = ordersList.filter((o) => !isTerminal(o.status) && o.status !== "LIVE" && !o.tenantDomainId).length;
  const expiringSoon = ordersList.filter((o) => o.expiresAt && daysUntil(o.expiresAt) >= 0 && daysUntil(o.expiresAt) <= 30).length;

  return (
    <>
      <PageHeader eyebrow={t("nav.settings")} title={t("domains.title")} subtitle={t("domains.subtitle")} />
      <KpiStrip className="mb-5" items={[
        { label: t("domainBuy.kpiActive"), value: activeCount, icon: "check", tone: "green" },
        { label: t("domainBuy.kpiPending"), value: domainsPending + ordersAwaitingDomain, icon: "clock", tone: "amber" },
        { label: t("domainBuy.kpiExpiring"), value: expiringSoon, icon: "alert", tone: expiringSoon > 0 ? "red" : "slate" },
      ]} />
      <div className="mb-5"><Tabs tabs={[{ key: "my", label: t("domainBuy.tabMy") }, { key: "buy", label: t("domainBuy.tabBuy") }]} value={tab} onChange={setTab} /></div>

      {tab === "buy" ? (
        <FindDomainScreen onOrdered={() => { setTab("my"); void reload(); void orders.reload(); toast(t("domainBuy.ordered")); }} />
      ) : (
        <>
          {loading && !data ? <Loading /> : <ErrorText error={error} />}
          <div className="max-w-3xl space-y-4">
            {data?.map((d) => {
              const inFlight = d.source === "PURCHASED" && !!d.domainOrderId && !d.verified;
              return (
                <div key={d.id} className="rounded-[18px] bg-surface p-5 ring-1 ring-line">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <div>
                      <p className="font-mono text-sm font-medium" dir="ltr">{d.host}</p>
                      {d.alias && <p className="mt-0.5 text-xs text-muted">{t("domains.alsoWorks", { host: d.alias })}</p>}
                      <div className="mt-1 flex flex-wrap gap-1.5">
                        {d.primary && <Badge tone="blue">{t("domains.primary")}</Badge>}
                        {d.stopped && <Badge tone="red">{t("domains.stopped")}</Badge>}
                        <Badge tone={d.verified ? "green" : "amber"}>{d.verified ? t("domains.verified") : t("domains.pending")}</Badge>
                        {d.kind === "SUBDOMAIN" && <Badge>{t("domains.free")}</Badge>}
                        {d.kind === "CUSTOM" && (d.source === "PURCHASED" ? <Badge tone="violet">{t("domainBuy.sourcePurchased")}</Badge> : <Badge>{t("domainBuy.sourceConnected")}</Badge>)}
                      </div>
                    </div>
                    <div className="flex gap-2">
                      {!d.verified && d.source !== "PURCHASED" && <Button size="sm" loading={verify.loading} onClick={() => verify.run(d.id)}>{t("domains.verify")}</Button>}
                      {d.verified && !d.primary && !d.stopped && <Button size="sm" variant="secondary" onClick={() => makePrimary.run(d.id)}>{t("domains.makePrimary")}</Button>}
                      {d.source === "PURCHASED" && d.domainOrderId && d.verified && <Button size="sm" variant="secondary" onClick={() => setDetailsFor(d)}>{t("domainBuy.details")}</Button>}
                      {d.kind === "CUSTOM" && !d.primary && d.source !== "PURCHASED" && <Button size="sm" variant="ghost" onClick={() => setConfirm(d)}>{t("common.delete")}</Button>}
                    </div>
                  </div>
                  {d.kind === "CUSTOM" && !d.verified && d.source !== "PURCHASED" && <SetupPanel id={d.id} host={d.host} onVerified={() => void reload()} />}
                  {inFlight && d.domainOrderId && (
                    <div className="mt-4"><DomainOrderPanel orderId={d.domainOrderId} sslStatus={d.sslStatus} onLive={() => { void reload(); void orders.reload(); }} /></div>
                  )}
                  {d.kind === "CUSTOM" && d.verified && !d.stopped && (
                    <div className="mt-4 flex items-start gap-3 rounded-[14px] bg-emerald-50 p-4 ring-1 ring-inset ring-emerald-600/15">
                      <span className="grid h-9 w-9 shrink-0 place-items-center rounded-full bg-emerald-500 text-white"><Icon name="check" className="h-5 w-5" /></span>
                      <div><p className="font-semibold text-emerald-800">{t("domain.connected")}</p><p className="text-sm text-emerald-800/80">{t("domain.connectedBody", { host: d.host })}</p></div>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
          <SettingsCard className="mt-6" icon="globe" title={t("domains.add")}>
            <form onSubmit={(e) => { e.preventDefault(); void add.run(); }} className="space-y-3">
              <Field label={t("domains.host")} hint={t("domain.addHint")}><Input value={host} onChange={(e) => setHost(e.target.value)} placeholder="example.com" dir="ltr" required /></Field>
              {notAllowed ? <Alert tone="amber">{t("domains.upgrade")} <Link href="/dashboard/billing/pay?plan=STORE_PRO" className="font-medium underline">{t("sub.action")}</Link></Alert> : <ErrorText error={add.error} />}
              <Button type="submit" loading={add.loading}><Icon name="plus" className="h-4 w-4" />{t("domains.addBtn")}</Button>
            </form>
            <ErrorText error={verify.error ?? makePrimary.error ?? remove.error} />
          </SettingsCard>
        </>
      )}

      <ConfirmDialog open={!!confirm} onClose={() => setConfirm(null)} danger loading={remove.loading} onConfirm={() => confirm && void remove.run(confirm.id)}
        title={t("common.delete")} body={confirm?.host} confirmLabel={t("common.delete")} cancelLabel={t("common.cancel")} />

      <Sheet open={!!detailsFor} onClose={() => setDetailsFor(null)} title={detailsFor?.host} width="32rem">
        {detailsFor?.domainOrderId && <DomainOrderPanel orderId={detailsFor.domainOrderId} sslStatus={detailsFor.sslStatus} />}
      </Sheet>
    </>
  );
}
