"use client";

import Link from "next/link";
import { useCallback, useState } from "react";
import { useNavTitle, useSection } from "@/components/dashboard/section";
import { api } from "@/lib/client";
import { Page, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { ConfirmDialog } from "@/components/overlay";
import { useToast } from "@/components/toast";
import { Badge, Button, Empty, ErrorText, Loading, PageHeader, Pager } from "@/components/ui";
import { dateTime, money } from "@/lib/format";
import { AccountStatus, CountryChips, CountryFlag, ProviderMark, type Account, type CatalogItem, type Country } from "@/components/dashboard/channels/common";
import { ConnectForm } from "@/components/dashboard/channels/connect-form";
import { Bar, Cell, DataTable, Panel, RowMenu, Row, SearchField } from "@/components/dashboard/sell/kit";

type Shipment = { id: string; provider: string; carrier: string; country: string; orderId?: string; orderNumber?: string; channelOrderId?: string; channelOrderNumber?: string; trackingNumber: string; trackingUrl?: string; labelUrl?: string; status: string; codMinor: number; lastEvent?: string; lastEventAt?: string; createdAt: string };
const DONE = ["DELIVERED", "RETURNED", "CANCELLED"];
const TONE: Record<string, "green" | "red" | "blue" | "slate" | "amber"> = { DELIVERED: "green", RETURNED: "slate", CANCELLED: "red", OUT_FOR_DELIVERY: "blue", IN_TRANSIT: "blue", CREATED: "amber", FAILED: "red" };

function Shipments({ canManage }: { canManage: boolean }) {
  const { t, locale, currency } = useI18n();
  const toast = useToast();
  const [q, setQ] = useState("");
  const [page, setPage] = useState(1);
  const setSearch = useCallback((v: string) => { setQ(v); setPage(1); }, []);
  const { data, loading, error, reload } = useApi<Page<Shipment>>(`store/shipping/shipments?page=${page}${q.trim() ? `&q=${encodeURIComponent(q.trim())}` : ""}`);
  const [err, setErr] = useState<unknown>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const refresh = async (id: string) => {
    setBusy(id); setErr(null);
    try { await api(`store/shipping/shipments/${id}/refresh`, { body: {} }); toast(t("sell.shipRefreshed")); await reload(); } catch (e) { setErr(e); } finally { setBusy(null); }
  };
  const [cancel, setCancel] = useState<Shipment | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const doCancel = async (id: string) => {
    setCancelling(true); setErr(null);
    try { await api(`store/shipping/shipments/${id}/cancel`, { body: {} }); toast(t("ship.cancel")); setCancel(null); await reload(); } catch (e) { setErr(e); setCancel(null); } finally { setCancelling(false); }
  };
  return (
    <div className="space-y-4">
      <Bar><SearchField value={q} onChange={setSearch} placeholder={t("ship.search")} /></Bar>
      <ErrorText error={error ?? err} />
      {data && data.data.length === 0 ? (
        <Empty icon="truck" title={q ? t("orders.noneMatch") : t("ship.none")} action={<Link href="/dashboard/orders" className="inline-flex h-10 items-center rounded-[11px] bg-ink px-4 text-[13px] font-semibold text-surface">{t("nav.orders")}</Link>}>
          {t("sell.ship.emptyHint")}
        </Empty>
      ) : (
        <>
          <DataTable loading={loading && !data} cols={[{ label: t("ship.tracking") }, { label: t("ship.carrier") }, { label: t("orders.number") }, { label: t("admin.status") }, { label: t("ship.lastEvent") }, { label: t("ship.cod"), end: true }, { label: "", end: true }]}>
            {data?.data.map((s) => (
              <Row key={s.id}>
                <Cell>{s.trackingUrl ? <a href={s.trackingUrl} target="_blank" rel="noreferrer" dir="ltr" className="font-mono text-[13px] font-semibold text-brand hover:underline">{s.trackingNumber}</a> : <span dir="ltr" className="font-mono text-[13px] font-semibold">{s.trackingNumber}</span>}<div className="text-[12px] text-faint tnum">{dateTime(s.createdAt, locale)}</div></Cell>
                <Cell><span className="flex items-center gap-2"><ProviderMark provider={s.provider} name={s.carrier} className="h-7 w-7 text-[10px]" /><span className="font-medium text-ink">{s.carrier}</span> <CountryFlag code={s.country} /></span></Cell>
                <Cell>{s.orderId ? <Link href={`/dashboard/orders/${s.orderId}`} className="font-mono text-[13px] font-semibold text-brand hover:underline" dir="ltr">{s.orderNumber}</Link> : s.channelOrderNumber ? <Link href="/dashboard/channels?tab=orders" className="font-mono text-[13px] font-semibold text-brand hover:underline">#{s.channelOrderNumber}</Link> : "—"}</Cell>
                <Cell><Badge tone={TONE[s.status] ?? "blue"}>{t(`ship.status.${s.status}`)}</Badge></Cell>
                <Cell className="max-w-[240px] text-[12.5px] text-ink-2">{s.lastEvent || "—"}{s.lastEventAt && <div className="text-faint tnum">{dateTime(s.lastEventAt, locale)}</div>}</Cell>
                <Cell end><span className="font-semibold tnum">{s.codMinor > 0 ? money(s.codMinor, currency, locale) : "—"}</span></Cell>
                <Cell end>
                  <div className="flex items-center justify-end gap-1">
                    {s.labelUrl && <a href={s.labelUrl} target="_blank" rel="noreferrer" className="inline-flex h-8 items-center gap-1 rounded-[9px] px-2.5 text-[12.5px] font-semibold text-brand hover:bg-brand-soft"><Icon name="print" className="h-3.5 w-3.5" />{t("ship.label")}</a>}
                    {!DONE.includes(s.status) && <Button size="sm" variant="ghost" loading={busy === s.id} onClick={() => void refresh(s.id)}><Icon name="return" className="h-3.5 w-3.5" />{t("ship.refresh")}</Button>}
                    {(canManage && !DONE.includes(s.status) || s.trackingUrl) && (
                      <RowMenu label={t("inbox.more")} items={[
                        ...(s.trackingUrl ? [{ label: t("ship.tracking"), icon: "external", onSelect: () => window.open(s.trackingUrl, "_blank", "noopener") }] : []),
                        ...(canManage && !DONE.includes(s.status) ? [{ label: t("ship.cancel"), icon: "xcircle", danger: true, onSelect: () => setCancel(s) }] : []),
                      ]} />
                    )}
                  </div>
                </Cell>
              </Row>
            ))}
          </DataTable>
          <Pager meta={data?.meta} onPage={setPage} />
        </>
      )}
      <ConfirmDialog open={!!cancel} onClose={() => setCancel(null)} danger title={t("ship.cancel")} body={t("ship.cancel.body", { n: cancel?.trackingNumber ?? "" })} confirmLabel={t("ship.cancel")} cancelLabel={t("common.cancel")} loading={cancelling}
        onConfirm={() => { if (cancel) void doCancel(cancel.id); }} />
    </div>
  );
}

function Carriers({ canManage }: { canManage: boolean }) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const [country, setCountry] = useState<Country>("EG");
  const [open, setOpen] = useState<CatalogItem | null>(null);
  const [drop, setDrop] = useState<Account | null>(null);
  const [err, setErr] = useState<unknown>(null);
  const catalog = useApi<CatalogItem[]>(`store/shipping/carriers/catalog?country=${country}`);
  const mine = useApi<Account[]>("store/shipping/carriers");
  const remove = async (id: string) => {
    setErr(null);
    try { await api(`store/shipping/carriers/${id}`, { method: "DELETE" }); toast(t("ch.disconnect")); await mine.reload(); } catch (e) { setErr(e); }
    setDrop(null);
  };
  const accountsFor = (id: string) => (mine.data ?? []).filter((a) => a.provider === id && a.country === country);
  return (
    <div className="space-y-5">
      <CountryChips value={country} onChange={setCountry} />
      <ErrorText error={catalog.error ?? err} />
      {catalog.loading && !catalog.data ? <Loading variant="cards" /> : (
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {catalog.data?.map((c) => {
            const accs = accountsFor(c.id);
            return (
              <Panel key={c.id} as="div" className="flex flex-col gap-3 p-5 transition hover:ring-line-strong">
                <div className="flex items-center gap-3">
                  <ProviderMark provider={c.id} name={c.name} className="h-12 w-12" />
                  <div className="min-w-0 flex-1"><p className="font-display text-[16px] font-semibold text-ink">{c.name}</p><p className="mt-0.5 flex gap-1 text-xs text-muted">{c.countries.map((k) => <CountryFlag key={k} code={k} />)}</p></div>
                  {c.status === "BETA" && <Badge tone="amber">{t("ch.beta")}</Badge>}
                  {accs.length > 0 && <span className="grid h-6 w-6 place-items-center rounded-full bg-emerald-50 text-emerald-600"><Icon name="check" className="h-3.5 w-3.5" /></span>}
                </div>
                <p className="flex-1 text-[13px] leading-relaxed text-muted">{locale === "ar" ? c.helpAr : c.helpEn}</p>
                <div className="flex flex-wrap gap-1.5">{c.can.map((k) => <span key={k} className="rounded-full bg-canvas px-2.5 py-0.5 text-[11.5px] font-medium text-ink-2 ring-1 ring-line">{t(`ch.can.${k}`)}</span>)}</div>
                {accs.map((a) => (
                  <div key={a.id} className="flex items-center justify-between gap-2 rounded-[12px] bg-emerald-50 px-3 py-2 text-[13px] text-emerald-900"><span className="truncate font-medium">{a.label}</span><span className="flex items-center gap-2"><AccountStatus status={a.status} />{canManage && <button type="button" onClick={() => setDrop(a)} className="text-xs font-semibold text-red-600 hover:underline">{t("ch.disconnect")}</button>}</span></div>
                ))}
                {c.status === "SOON" ? <Button disabled variant="secondary">{t("ch.soon")}</Button>
                  : canManage && <Button variant={accs.length ? "secondary" : "ink"} onClick={() => setOpen(c)}>{accs.length ? t("ch.addAnother") : t("ch.connectBtn")}</Button>}
              </Panel>
            );
          })}
        </div>
      )}
      {open && <ConnectForm carrier item={open} country={country} onClose={() => setOpen(null)} onDone={(name) => { setOpen(null); toast(t("ch.connected", { name })); void mine.reload(); }} />}
      <ConfirmDialog open={!!drop} onClose={() => setDrop(null)} danger title={t("ch.disconnect")} body={drop?.label} confirmLabel={t("ch.disconnect")} cancelLabel={t("common.cancel")} onConfirm={() => { if (drop) void remove(drop.id); }} />
    </div>
  );
}

export default function ShippingPage() {
  const { t } = useI18n();
  const me = useMe();
  const canManage = me.canWrite("shipping");
  const [tab] = useSection(["shipments", "carriers"] as const, "shipments");
  const title = useNavTitle(t("ship.title"));
  return (
    <div className="space-y-5">
      <PageHeader eyebrow={t("sell.eyebrow")} title={title} subtitle={t("ship.subtitle")} />
      {tab === "shipments" ? <Shipments canManage={canManage} /> : <Carriers canManage={canManage} />}
    </div>
  );
}
