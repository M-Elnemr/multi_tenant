"use client";

import Link from "next/link";
import { Suspense, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { Alert, Empty, ErrorText, Loading, PageHeader } from "@/components/ui";
import { useNavTitle, useSection } from "@/components/dashboard/section";
import { AccountsView, CustomersView } from "@/components/dashboard/channels/accounts";
import { CountryFlag, ProviderMark, type Account } from "@/components/dashboard/channels/common";
import { InsightsView } from "@/components/dashboard/channels/insights";
import { ListingsView } from "@/components/dashboard/channels/listings";
import { OrdersView } from "@/components/dashboard/channels/orders";

type Tab = "insights" | "orders" | "products" | "customers" | "platforms";

export default function ChannelsPage() {
  return <Suspense fallback={<Loading />}><Channels /></Suspense>;
}

function Channels() {
  const { t } = useI18n();
  const params = useSearchParams();
  const me = useMe();
  const canManage = me.canWrite("integrations");
  const { data, error, loading, reload } = useApi<Account[]>("store/integrations/accounts");
  const accounts = (data ?? []).filter((a) => a.kind === "MARKETPLACE" || a.kind === "STORE_PLATFORM");
  const [tab, setTab] = useSection<Tab>(["insights", "orders", "products", "customers", "platforms"], "insights");
  const title = useNavTitle(t("ch.title"));
  const [picked, setPicked] = useState<string | null>(null);
  const account = accounts.find((a) => a.id === picked) ?? null;
  const accountId = account?.id ?? null;
  const connected = params.get("connected");
  const oauthError = params.get("error");
  const newOrders = accounts.filter((a) => !accountId || a.id === accountId).reduce((n, a) => n + a.newOrders, 0);
  const connectBtn = "inline-flex h-10 items-center gap-1.5 rounded-[10px] bg-ink px-4 text-sm font-semibold text-surface shadow-[0_1px_2px_rgb(16_18_24/.2)] transition hover:opacity-90";

  if (loading && !data) return <Loading />;
  return (
    <>
      <PageHeader eyebrow={t("navgrp.reports")} title={title} subtitle={t("ch.subtitle")}
        actions={canManage ? <Link href="/dashboard/channels/connect" className={connectBtn}><Icon name="plus" className="h-4 w-4" />{t("ch.connect")}</Link> : undefined} />
      <div className="space-y-5">
        {connected && <Alert tone="green">{t("ch.connected", { name: connected })}</Alert>}
        {oauthError && <Alert tone="red">{t(`ch.oauthError.${oauthError}`) === `ch.oauthError.${oauthError}` ? t("ch.oauthError.generic") : t(`ch.oauthError.${oauthError}`)}</Alert>}
        <ErrorText error={error} />
        {accounts.length === 0 ? (
          <Empty icon="globe" title={t("ch.empty.title")} action={canManage ? <Link href="/dashboard/channels/connect" className={connectBtn}>{t("ch.connect")}</Link> : undefined}>{t("ch.empty.body")}</Empty>
        ) : (
          <>
            <div className="-mx-1 overflow-x-auto px-1 pb-1 [scrollbar-width:none]" role="tablist" aria-label={t("ch.platform")}>
              <div className="flex w-max gap-2">
                <button role="tab" aria-selected={!account} onClick={() => setPicked(null)} className={`chip !min-h-11 ${!account ? "chip-on" : ""}`}>
                  <Icon name="grid" className="h-4 w-4" />{t("ch.allPlatforms")}<span className={`rounded-full px-1.5 text-[11px] tnum ${!account ? "bg-white/20" : "bg-[color-mix(in_srgb,var(--ink)_8%,transparent)]"}`}>{accounts.length}</span>
                </button>
                {accounts.map((a) => (
                  <button key={a.id} role="tab" aria-selected={a.id === picked} onClick={() => setPicked(a.id)} className={`chip !min-h-11 !ps-1.5 ${a.id === picked ? "chip-on" : ""}`}>
                    <ProviderMark provider={a.provider} name={a.name} className="!h-7 !w-7 !rounded-full text-[10px]" />
                    <span className="max-w-[140px] truncate">{a.name}</span><CountryFlag code={a.country} />
                    {a.status === "ERROR" && <span className="h-2 w-2 rounded-full bg-red-500" title={a.lastError ?? ""} />}
                    {a.newOrders > 0 && <span className="rounded-full bg-amber-500 px-1.5 text-[11px] font-bold text-white tnum">{a.newOrders}</span>}
                  </button>
                ))}
              </div>
            </div>
            {tab !== "orders" && newOrders > 0 && (
              <button type="button" onClick={() => setTab("orders")} className="flex w-full items-center gap-3 rounded-[14px] bg-amber-50 px-4 py-3 text-start text-[13.5px] font-semibold text-amber-900 ring-1 ring-inset ring-amber-600/20 transition hover:bg-amber-100">
                <Icon name="box" className="h-4 w-4 shrink-0" /><span className="min-w-0 flex-1">{t("nav.mpOrders")}: <span className="tnum">{newOrders}</span></span><Icon name="arrow" className="h-4 w-4 flip-rtl" />
              </button>
            )}
            {tab === "insights" && <InsightsView accountId={accountId} onPick={(id) => setPicked(id)} onGo={(to, id) => { setPicked(id); setTab(to); }} />}
            {tab === "orders" && <OrdersView accountId={accountId} />}
            {tab === "products" && <ListingsView accountId={accountId} canImport={canManage} />}
            {tab === "customers" && <CustomersView accountId={accountId} />}
            {tab === "platforms" && <AccountsView accounts={accountId ? accounts.filter((a) => a.id === accountId) : accounts} canManage={canManage} onChanged={() => void reload()} onPick={(id) => { setPicked(id); setTab("insights"); }} />}
          </>
        )}
      </div>
    </>
  );
}
