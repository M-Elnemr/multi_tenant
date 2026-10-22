"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { Page, useAction, useApi } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Badge, Button, Empty, ErrorText, Field, FilterBar, Input, Loading, Modal, Pager, Table, Tabs, Td } from "@/components/ui";
import { dateTime, money } from "@/lib/format";
import { ShipModal } from "../shipping/ship-modal";
import { CountryFlag, ProviderMark, Row, STAGES, StageBadge } from "./common";

type OrderRow = { id: string; accountId: string; provider: string; accountLabel: string; country: string; number: string; status: string; stage: string; totalMinor: number; currency: string; customerName?: string; customerPhone?: string; itemCount: number; trackingNumber?: string; carrier?: string; placedAt?: string };
type Item = { title?: string; sku?: string; quantity?: number; unitMinor?: number; totalMinor?: number; imageUrl?: string };
type Detail = OrderRow & {
  externalId: string; customerEmail?: string; address: Record<string, string>; items: Item[]; providerName: string; syncedAt?: string;
  actions: { key: string; label: string; needsTracking: boolean }[]; shipments: { id: string; trackingNumber: string; status: string; labelUrl?: string; lastEvent?: string; carrier: string }[];
};

/** One order of a connected platform with everything the platform told us and the actions it allows. */
function OrderDetail({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const { t, locale } = useI18n();
  const { data, error, reload } = useApi<Detail>(`store/integrations/orders/${id}`);
  const [tracking, setTracking] = useState({ trackingNumber: "", carrier: "" });
  const [shipping, setShipping] = useState(false);
  const act = useAction(async (action: string, needsTracking: boolean) => {
    await api(`store/integrations/orders/${id}/actions`, { body: { action, params: needsTracking ? tracking : {} } });
    await reload(); onChanged();
  });
  const addr = data?.address ?? {};
  const addrLine = [addr.line1 ?? addr.address1 ?? addr.address_1, addr.line2, addr.city, addr.region, addr.zip, addr.country].filter(Boolean).join(", ");
  return (
    <Modal open onClose={onClose} title={data ? `${data.providerName} · #${data.number}` : t("common.loading")} wide>
      <ErrorText error={error} />
      {!data ? <Loading /> : (
        <div className="space-y-5">
          <div className="flex flex-wrap items-center gap-2"><StageBadge stage={data.stage} /><Badge tone="slate">{data.status}</Badge><span className="text-sm text-slate-500">{dateTime(data.placedAt, locale)}</span></div>
          <div className="grid gap-5 md:grid-cols-2">
            <dl className="rounded-2xl bg-slate-50 p-4">
              <Row label={t("orders.customer")}>{data.customerName || "—"}</Row>
              <Row label={t("ch.phone")}><span dir="ltr">{data.customerPhone || "—"}</span></Row>
              {data.customerEmail && <Row label={t("ch.email")}><span dir="ltr">{data.customerEmail}</span></Row>}
              <Row label={t("ch.address")}>{addrLine || "—"}</Row>
            </dl>
            <dl className="rounded-2xl bg-slate-50 p-4">
              <Row label={t("checkout.total")}><span className="text-base font-bold">{money(data.totalMinor, data.currency, locale)}</span></Row>
              <Row label={t("ch.platformOrder")}><span dir="ltr" className="font-mono text-xs">{data.externalId}</span></Row>
              <Row label={t("ch.tracking")}>{data.trackingNumber ? <span dir="ltr" className="font-mono">{data.carrier ? `${data.carrier} · ` : ""}{data.trackingNumber}</span> : "—"}</Row>
              <Row label={t("ch.synced")}>{dateTime(data.syncedAt, locale)}</Row>
            </dl>
          </div>
          <div>
            <h3 className="mb-2 font-bold">{t("ch.items")}</h3>
            <ul className="divide-y divide-slate-100 rounded-2xl border border-slate-200">
              {data.items.length === 0 && <li className="p-4 text-sm text-slate-500">—</li>}
              {data.items.map((it, i) => (
                <li key={i} className="flex items-center gap-3 p-3 text-sm">
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  {it.imageUrl ? <img src={it.imageUrl} alt="" className="h-12 w-12 rounded-lg object-cover" /> : <span className="grid h-12 w-12 place-items-center rounded-lg bg-slate-100 text-slate-400">▫</span>}
                  <span className="min-w-0 flex-1"><span className="block truncate font-medium">{it.title || it.sku}</span>{it.sku && <span className="font-mono text-xs text-slate-400">{it.sku}</span>}</span>
                  <span className="text-slate-500">× {it.quantity ?? 1}</span>
                  <span className="w-24 text-end font-semibold">{money(it.totalMinor ?? (it.unitMinor ?? 0) * (it.quantity ?? 1), data.currency, locale)}</span>
                </li>
              ))}
            </ul>
          </div>
          {data.shipments.length > 0 && (
            <div>
              <h3 className="mb-2 font-bold">{t("ship.shipments")}</h3>
              <ul className="space-y-2">{data.shipments.map((s) => (
                <li key={s.id} className="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-slate-200 p-3 text-sm">
                  <span><b>{s.carrier}</b> · <span dir="ltr" className="font-mono">{s.trackingNumber}</span></span>
                  <span className="flex items-center gap-2"><Badge tone={s.status === "DELIVERED" ? "green" : "blue"}>{t(`ship.status.${s.status}`)}</Badge>{s.labelUrl && <a href={s.labelUrl} target="_blank" rel="noreferrer" className="font-semibold text-brand hover:underline">{t("ship.label")}</a>}</span>
                </li>
              ))}</ul>
            </div>
          )}
          {data.actions.length > 0 && (
            <div className="space-y-3 rounded-2xl border border-slate-200 p-4">
              <h3 className="font-bold">{t("ch.actions")}</h3>
              {data.actions.some((a) => a.needsTracking) && (
                <div className="grid gap-3 sm:grid-cols-2">
                  <Field label={t("ch.trackingNo")}><Input dir="ltr" value={tracking.trackingNumber} onChange={(e) => setTracking({ ...tracking, trackingNumber: e.target.value })} /></Field>
                  <Field label={t("ch.carrierName")}><Input value={tracking.carrier} onChange={(e) => setTracking({ ...tracking, carrier: e.target.value })} /></Field>
                </div>
              )}
              <div className="flex flex-wrap gap-2">
                {data.actions.map((a) => <Button key={a.key} variant={/cancel/i.test(a.key) ? "danger" : "secondary"} size="sm" loading={act.loading} disabled={a.needsTracking && !tracking.trackingNumber.trim()} onClick={() => void act.run(a.key, a.needsTracking)}>{a.label}</Button>)}
                {["NEW", "PROCESSING"].includes(data.stage) && <Button size="sm" onClick={() => setShipping(true)}>{t("ship.shipWith")}</Button>}
              </div>
              <ErrorText error={act.error} />
            </div>
          )}
          {data.actions.length === 0 && ["NEW", "PROCESSING"].includes(data.stage) && <Button onClick={() => setShipping(true)}>{t("ship.shipWith")}</Button>}
          {shipping && <ShipModal channelOrderId={id} country={data.country} codMinor={data.totalMinor} currency={data.currency} onClose={() => setShipping(false)} onDone={() => { setShipping(false); void reload(); onChanged(); }} />}
        </div>
      )}
    </Modal>
  );
}

export function OrdersView({ accountId }: { accountId: string | null }) {
  const { t, locale } = useI18n();
  const [stage, setStage] = useState("");
  const [q, setQ] = useState("");
  const [page, setPage] = useState(1);
  const [open, setOpen] = useState<string | null>(null);
  const acc = accountId ? `&accountId=${accountId}` : "";
  const summary = useApi<Record<string, number>>(`store/integrations/orders/summary?x=1${acc}`);
  const qs = new URLSearchParams({ page: String(page) });
  if (stage) qs.set("stage", stage);
  if (q.trim()) qs.set("q", q.trim());
  const { data, loading, error, reload } = useApi<Page<OrderRow>>(`store/integrations/orders?${qs}${acc}`);
  const total = Object.values(summary.data ?? {}).reduce((a, b) => a + b, 0);
  return (
    <div className="space-y-4">
      <Tabs tabs={[{ key: "", label: t("ch.all"), count: total }, ...STAGES.map((s) => ({ key: s as string, label: t(`ch.stage.${s}`), count: summary.data?.[s] ?? 0 }))]} value={stage} onChange={(k) => { setStage(k); setPage(1); }} />
      <FilterBar><Input className="max-w-sm" placeholder={t("ch.orders.search")} value={q} onChange={(e) => { setQ(e.target.value); setPage(1); }} /></FilterBar>
      <ErrorText error={error} />
      {loading && !data ? <Loading /> : data && data.data.length === 0 ? <Empty>{t("ch.orders.none")}</Empty> : (
        <>
          <Table head={[t("orders.number"), t("ch.platform"), t("orders.customer"), t("admin.status"), t("checkout.total"), t("ch.tracking"), t("admin.created")]}>
            {data?.data.map((o) => (
              <tr key={o.id} onClick={() => setOpen(o.id)} className="cursor-pointer">
                <Td><button className="font-mono font-semibold text-brand">#{o.number}</button><div className="text-xs text-slate-400">{t("ch.itemsCount", { n: o.itemCount })}</div></Td>
                <Td><span className="flex items-center gap-2"><ProviderMark provider={o.provider} className="h-7 w-7 text-[10px]" /><span className="text-xs">{o.accountLabel} <CountryFlag code={o.country} /></span></span></Td>
                <Td>{o.customerName || "—"}<div dir="ltr" className="text-xs text-slate-500">{o.customerPhone}</div></Td>
                <Td><StageBadge stage={o.stage} /></Td>
                <Td className="font-semibold">{money(o.totalMinor, o.currency, locale)}</Td>
                <Td>{o.trackingNumber ? <span dir="ltr" className="font-mono text-xs">{o.trackingNumber}</span> : "—"}</Td>
                <Td>{dateTime(o.placedAt, locale)}</Td>
              </tr>
            ))}
          </Table>
          <Pager meta={data?.meta} onPage={setPage} />
        </>
      )}
      {open && <OrderDetail id={open} onClose={() => setOpen(null)} onChanged={() => { void reload(); void summary.reload(); }} />}
      <Alert tone="blue">{t("ch.orders.note")}</Alert>
    </div>
  );
}
