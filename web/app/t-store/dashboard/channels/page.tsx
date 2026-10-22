"use client";

import Link from "next/link";
import { Suspense, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Button, Empty, ErrorText, Loading, PageHeader, Tabs } from "@/components/ui";
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
  const [tab, setTab] = useState<Tab>("insights");
  const [picked, setPicked] = useState<string | null>(null);
  const account = accounts.find((a) => a.id === picked) ?? null;
  const accountId = account?.id ?? null;
  const connected = params.get("connected");
  const oauthError = params.get("error");
  const newOrders = accounts.filter((a) => !accountId || a.id === accountId).reduce((n, a) => n + a.newOrders, 0);

  if (loading && !data) return <Loading />;
  return (
    <>
      <PageHeader
        title={t("ch.title")}
        subtitle={t("ch.subtitle")}
        actions={canManage ? <Link href="/dashboard/channels/connect"><Button>＋ {t("ch.connect")}</Button></Link> : undefined}
      />
      {connected && <div className="mb-4"><Alert tone="green">{t("ch.connected", { name: connected })}</Alert></div>}
      {oauthError && <div className="mb-4"><Alert tone="red">{t(`ch.oauthError.${oauthError}`) === `ch.oauthError.${oauthError}` ? t("ch.oauthError.generic") : t(`ch.oauthError.${oauthError}`)}</Alert></div>}
      <ErrorText error={error} />
      {accounts.length === 0 ? (
        <Empty>
          <p className="mb-1 text-base font-bold text-ink">{t("ch.empty.title")}</p>
          <p className="mx-auto mb-4 max-w-md">{t("ch.empty.body")}</p>
          {canManage && <Link href="/dashboard/channels/connect"><Button>{t("ch.connect")}</Button></Link>}
        </Empty>
      ) : (
        <div className="space-y-5">
          <div className="-mx-1 flex gap-2 overflow-x-auto px-1 pb-1" role="tablist" aria-label={t("ch.platform")}>
            <button role="tab" aria-selected={!account} onClick={() => setPicked(null)} className={`flex shrink-0 items-center gap-2 rounded-2xl border px-4 py-2.5 text-sm font-semibold transition ${!account ? "border-brand bg-brand-soft text-brand-dark" : "border-slate-200 bg-white text-slate-600 hover:border-slate-300"}`}>
              <span aria-hidden>▦</span>{t("ch.allPlatforms")}<span className="rounded-full bg-white/70 px-1.5 text-xs">{accounts.length}</span>
            </button>
            {accounts.map((a) => (
              <button key={a.id} role="tab" aria-selected={a.id === picked} onClick={() => setPicked(a.id)} className={`flex shrink-0 items-center gap-2 rounded-2xl border px-3 py-2 text-sm font-semibold transition ${a.id === picked ? "border-brand bg-brand-soft text-brand-dark" : "border-slate-200 bg-white text-slate-600 hover:border-slate-300"}`}>
                <ProviderMark provider={a.provider} name={a.name} className="h-7 w-7 text-[10px]" />
                <span className="max-w-[140px] truncate">{a.name}</span><CountryFlag code={a.country} />
                {a.status === "ERROR" && <span className="h-2 w-2 rounded-full bg-red-500" title={a.lastError ?? ""} />}
                {a.newOrders > 0 && <span className="rounded-full bg-amber-500 px-1.5 text-[11px] font-bold text-white">{a.newOrders}</span>}
              </button>
            ))}
          </div>
          <Tabs<Tab>
            tabs={[{ key: "insights", label: t("ch.tab.insights") }, { key: "orders", label: t("ch.tab.orders"), count: newOrders || undefined }, { key: "products", label: t("ch.tab.products") }, { key: "customers", label: t("ch.tab.customers") }, { key: "platforms", label: t("ch.tab.platforms") }]}
            value={tab} onChange={setTab}
          />
          {tab === "insights" && <InsightsView accountId={accountId} onPick={(id) => setPicked(id)} onGo={(to, id) => { setPicked(id); setTab(to); }} />}
          {tab === "orders" && <OrdersView accountId={accountId} />}
          {tab === "products" && <ListingsView accountId={accountId} canImport={canManage} />}
          {tab === "customers" && <CustomersView accountId={accountId} />}
          {tab === "platforms" && <AccountsView accounts={accountId ? accounts.filter((a) => a.id === accountId) : accounts} canManage={canManage} onChanged={() => void reload()} onPick={(id) => { setPicked(id); setTab("insights"); }} />}
        </div>
      )}
    </>
  );
}
