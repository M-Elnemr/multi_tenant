"use client";

import Link from "next/link";
import { useState } from "react";
import { api } from "@/lib/client";
import { Page, useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Badge, Button, Card, Empty, ErrorText, Input, Loading, Modal, PageHeader, Pager, Table, Tabs, Td } from "@/components/ui";
import { dateTime, money } from "@/lib/format";
import { AccountStatus, CountryChips, CountryFlag, ProviderMark, type Account, type CatalogItem, type Country } from "@/components/dashboard/channels/common";
import { ConnectForm } from "@/components/dashboard/channels/connect-form";

type Shipment = { id: string; provider: string; carrier: string; country: string; orderId?: string; orderNumber?: string; channelOrderId?: string; channelOrderNumber?: string; trackingNumber: string; trackingUrl?: string; labelUrl?: string; status: string; codMinor: number; lastEvent?: string; lastEventAt?: string; createdAt: string };
const DONE = ["DELIVERED", "RETURNED", "CANCELLED"];
const TONE: Record<string, "green" | "red" | "blue" | "slate" | "amber"> = { DELIVERED: "green", RETURNED: "slate", CANCELLED: "red", OUT_FOR_DELIVERY: "blue", IN_TRANSIT: "blue", CREATED: "amber", FAILED: "red" };

function Shipments({ canManage }: { canManage: boolean }) {
  const { t, locale, currency } = useI18n();
  const [q, setQ] = useState("");
  const [page, setPage] = useState(1);
  const { data, loading, error, reload } = useApi<Page<Shipment>>(`store/shipping/shipments?page=${page}${q.trim() ? `&q=${encodeURIComponent(q.trim())}` : ""}`);
  const refresh = useAction(async (id: string) => { await api(`store/shipping/shipments/${id}/refresh`, { body: {} }); await reload(); });
  const [cancel, setCancel] = useState<Shipment | null>(null);
  const doCancel = useAction(async (id: string) => { await api(`store/shipping/shipments/${id}/cancel`, { body: {} }); setCancel(null); await reload(); });
  return (
    <div className="space-y-4">
      <Input className="max-w-sm" placeholder={t("ship.search")} value={q} onChange={(e) => { setQ(e.target.value); setPage(1); }} />
      <ErrorText error={error ?? refresh.error} />
      {loading && !data ? <Loading /> : data && data.data.length === 0 ? <Empty>{t("ship.none")}</Empty> : (
        <>
          <Table head={[t("ship.tracking"), t("ship.carrier"), t("orders.number"), t("admin.status"), t("ship.lastEvent"), t("ship.cod"), ""]}>
            {data?.data.map((s) => (
              <tr key={s.id}>
                <Td>{s.trackingUrl ? <a href={s.trackingUrl} target="_blank" rel="noreferrer" dir="ltr" className="font-mono text-brand hover:underline">{s.trackingNumber}</a> : <span dir="ltr" className="font-mono">{s.trackingNumber}</span>}<div className="text-xs text-slate-400">{dateTime(s.createdAt, locale)}</div></Td>
                <Td><span className="flex items-center gap-2"><ProviderMark provider={s.provider} name={s.carrier} className="h-7 w-7 text-[10px]" />{s.carrier} <CountryFlag code={s.country} /></span></Td>
                <Td>{s.orderId ? <Link href={`/dashboard/orders/${s.orderId}`} className="font-mono text-brand">{s.orderNumber}</Link> : s.channelOrderNumber ? <Link href="/dashboard/channels" className="font-mono text-brand">#{s.channelOrderNumber}</Link> : "—"}</Td>
                <Td><Badge tone={TONE[s.status] ?? "blue"}>{t(`ship.status.${s.status}`)}</Badge></Td>
                <Td className="max-w-[220px] text-xs text-slate-600">{s.lastEvent || "—"}{s.lastEventAt && <div className="text-slate-400">{dateTime(s.lastEventAt, locale)}</div>}</Td>
                <Td>{s.codMinor > 0 ? money(s.codMinor, currency, locale) : "—"}</Td>
                <Td><span className="flex gap-1">
                  {s.labelUrl && <a href={s.labelUrl} target="_blank" rel="noreferrer" className="rounded-lg px-2 py-1 text-sm font-semibold text-brand hover:bg-brand-soft">{t("ship.label")}</a>}
                  {!DONE.includes(s.status) && <Button size="sm" variant="ghost" loading={refresh.loading} onClick={() => void refresh.run(s.id)}>{t("ship.refresh")}</Button>}
                  {canManage && !DONE.includes(s.status) && <Button size="sm" variant="ghost" onClick={() => setCancel(s)}>{t("ship.cancel")}</Button>}
                </span></Td>
              </tr>
            ))}
          </Table>
          <Pager meta={data?.meta} onPage={setPage} />
        </>
      )}
      {cancel && (
        <Modal open onClose={() => setCancel(null)} title={t("ship.cancel")}>
          <p className="mb-4 text-sm">{t("ship.cancel.body", { n: cancel.trackingNumber })}</p>
          <ErrorText error={doCancel.error} />
          <div className="mt-3 flex justify-end gap-2"><Button variant="secondary" onClick={() => setCancel(null)}>{t("common.cancel")}</Button><Button variant="danger" loading={doCancel.loading} onClick={() => void doCancel.run(cancel.id)}>{t("ship.cancel")}</Button></div>
        </Modal>
      )}
    </div>
  );
}

function Carriers({ canManage }: { canManage: boolean }) {
  const { t, locale } = useI18n();
  const [country, setCountry] = useState<Country>("EG");
  const [open, setOpen] = useState<CatalogItem | null>(null);
  const [msg, setMsg] = useState("");
  const catalog = useApi<CatalogItem[]>(`store/shipping/carriers/catalog?country=${country}`);
  const mine = useApi<Account[]>("store/shipping/carriers");
  const remove = useAction(async (id: string) => { await api(`store/shipping/carriers/${id}`, { method: "DELETE" }); await mine.reload(); });
  const accountsFor = (id: string) => (mine.data ?? []).filter((a) => a.provider === id && a.country === country);
  return (
    <div className="space-y-5">
      <CountryChips value={country} onChange={setCountry} />
      {msg && <Alert tone="green">{msg}</Alert>}
      <ErrorText error={catalog.error ?? remove.error} />
      {catalog.loading && !catalog.data ? <Loading /> : (
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {catalog.data?.map((c) => (
            <Card key={c.id} hover className="flex flex-col gap-3">
              <div className="flex items-center gap-3">
                <ProviderMark provider={c.id} name={c.name} className="h-12 w-12" />
                <div className="min-w-0 flex-1"><p className="text-lg font-bold">{c.name}</p><p className="flex gap-1 text-xs text-slate-500">{c.countries.map((k) => <CountryFlag key={k} code={k} />)}</p></div>
                {c.status === "BETA" && <Badge tone="amber">{t("ch.beta")}</Badge>}
              </div>
              <p className="flex-1 text-sm text-slate-600">{locale === "ar" ? c.helpAr : c.helpEn}</p>
              <div className="flex flex-wrap gap-1.5">{c.can.map((k) => <span key={k} className="rounded-full bg-slate-100 px-2.5 py-0.5 text-xs font-medium text-slate-600">{t(`ch.can.${k}`)}</span>)}</div>
              {accountsFor(c.id).map((a) => (
                <div key={a.id} className="flex items-center justify-between gap-2 rounded-xl bg-emerald-50 px-3 py-2 text-sm"><span className="truncate font-medium">{a.label}</span><span className="flex items-center gap-2"><AccountStatus status={a.status} />{canManage && <button onClick={() => void remove.run(a.id)} className="text-xs font-semibold text-red-600 hover:underline">{t("ch.disconnect")}</button>}</span></div>
              ))}
              {c.status === "SOON" ? <Button disabled variant="secondary">{t("ch.soon")}</Button>
                : canManage && <Button variant={accountsFor(c.id).length ? "secondary" : "primary"} onClick={() => setOpen(c)}>{accountsFor(c.id).length ? t("ch.addAnother") : t("ch.connectBtn")}</Button>}
            </Card>
          ))}
        </div>
      )}
      {open && <ConnectForm carrier item={open} country={country} onClose={() => setOpen(null)} onDone={(name) => { setOpen(null); setMsg(t("ch.connected", { name })); void mine.reload(); }} />}
    </div>
  );
}

export default function ShippingPage() {
  const { t } = useI18n();
  const me = useMe();
  const canManage = me.canWrite("shipping");
  const [tab, setTab] = useState<"shipments" | "carriers">("shipments");
  return (
    <>
      <PageHeader title={t("ship.title")} subtitle={t("ship.subtitle")} />
      <div className="space-y-5">
        <Tabs tabs={[{ key: "shipments", label: t("ship.shipments") }, { key: "carriers", label: t("ship.carriers") }]} value={tab} onChange={setTab} />
        {tab === "shipments" ? <Shipments canManage={canManage} /> : <Carriers canManage={canManage} />}
      </div>
    </>
  );
}
