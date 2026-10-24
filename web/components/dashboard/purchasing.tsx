"use client";

import { useState } from "react";
import { api } from "@/lib/client";
import { dateOnly, money, toMinor } from "@/lib/format";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Alert, Badge, Button, Card, Empty, ErrorText, Field, Input, Loading, Select, Tabs } from "../ui";

type Po = { id: string; number: string; status: string; totalMinor: number; supplierName?: string | null; itemCount: number; createdAt: string };
type PoItem = { id: string; productName: string; sku: string; comboKey?: string; quantity: number; unitCostMinor: number };
type PoDetail = Po & { items: PoItem[] };
type Supplier = { id: string; name: string; phone?: string | null; email?: string | null };
type Variant = { variantId: string; sku: string; name: string; variantLabel?: string | null };
type Branch = { id: string; name: string };
type Transfer = { id: string; quantity: number; productName: string; sku: string; fromName: string; toName: string; createdAt: string };
const TONE: Record<string, "blue" | "amber" | "green" | "red"> = { DRAFT: "blue", ORDERED: "amber", RECEIVED: "green", CANCELLED: "red" };

function Orders() {
  const { t, locale, currency } = useI18n();
  const list = useApi<Po[]>("store/purchase-orders");
  const suppliers = useApi<Supplier[]>("store/suppliers");
  const variants = useApi<Variant[]>("store/pos/labels");
  const [open, setOpen] = useState<string | null>(null);
  const detail = useApi<PoDetail>(open ? `store/purchase-orders/${open}` : null);
  const [f, setF] = useState({ supplierId: "", variantId: "", qty: "10", cost: "" });
  const act = useAction(async (path: string, body: unknown = {}) => { await api(path, { body }); await list.reload(); if (open) await detail.reload(); });
  const draft = useAction(async () => { await api("store/purchase-orders/draft-low-stock", { body: { supplierId: f.supplierId || undefined } }); await list.reload(); });
  const create = useAction(async () => { await api("store/purchase-orders", { body: { supplierId: f.supplierId || undefined, items: [{ variantId: f.variantId, quantity: Number(f.qty), unitCostMinor: f.cost ? toMinor(f.cost) : undefined }] } }); setF({ ...f, variantId: "", cost: "" }); await list.reload(); });
  if (!list.data) return <Loading />;
  return (
    <div className="space-y-5">
      <Card className="space-y-3">
        <h2 className="font-medium">{t("po.new")}</h2>
        <div className="grid gap-3 sm:grid-cols-4">
          <Field label={t("po.supplier")}><Select value={f.supplierId} onChange={(e) => setF({ ...f, supplierId: e.target.value })}><option value="">-</option>{suppliers.data?.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}</Select></Field>
          <Field label={t("po.product")}><Select value={f.variantId} onChange={(e) => setF({ ...f, variantId: e.target.value })}><option value="">-</option>{variants.data?.map((v) => <option key={v.variantId} value={v.variantId}>{v.name}{v.variantLabel ? ` (${v.variantLabel})` : ""} · {v.sku}</option>)}</Select></Field>
          <Field label={t("po.qty")}><Input dir="ltr" inputMode="numeric" value={f.qty} onChange={(e) => setF({ ...f, qty: e.target.value })} /></Field>
          <Field label={t("po.cost")}><Input dir="ltr" inputMode="decimal" value={f.cost} onChange={(e) => setF({ ...f, cost: e.target.value })} /></Field>
        </div>
        <ErrorText error={create.error ?? draft.error} />
        <div className="flex flex-wrap gap-2"><Button loading={create.loading} disabled={!f.variantId} onClick={() => void create.run()}>{t("po.create")}</Button><Button variant="secondary" loading={draft.loading} onClick={() => void draft.run()}>{t("po.draftLow")}</Button></div>
      </Card>
      <ErrorText error={act.error} />
      {list.data.length === 0 ? <Empty>{t("po.none")}</Empty> : list.data.map((p) => (
        <Card key={p.id} className="space-y-2">
          <button type="button" className="flex w-full flex-wrap items-center justify-between gap-2 text-start" onClick={() => setOpen(open === p.id ? null : p.id)}>
            <span><b dir="ltr">{p.number}</b> <span className="text-sm text-slate-500">{p.supplierName ?? ""} · {dateOnly(p.createdAt, locale)}</span></span>
            <span className="flex items-center gap-2"><span className="text-sm">{money(p.totalMinor, currency, locale)}</span><Badge tone={TONE[p.status]}>{t(`po.status.${p.status}`)}</Badge></span>
          </button>
          {open === p.id && detail.data && (
            <div className="space-y-2 border-t pt-2 text-sm">
              {detail.data.items.map((i) => <div key={i.id} className="flex justify-between"><span>{i.quantity} × {i.productName} <span className="text-slate-500" dir="ltr">{i.sku}</span></span><span>{money(i.unitCostMinor * i.quantity, currency, locale)}</span></div>)}
              <div className="flex flex-wrap gap-2 pt-1">
                {p.status === "DRAFT" && <Button size="sm" variant="secondary" onClick={() => void act.run(`store/purchase-orders/${p.id}/ordered`)}>{t("po.markOrdered")}</Button>}
                {(p.status === "DRAFT" || p.status === "ORDERED") && <Button size="sm" onClick={() => void act.run(`store/purchase-orders/${p.id}/receive`)}>{t("po.receive")}</Button>}
                {(p.status === "DRAFT" || p.status === "ORDERED") && <Button size="sm" variant="ghost" onClick={() => void act.run(`store/purchase-orders/${p.id}/cancel`)}>{t("po.cancel")}</Button>}
              </div>
            </div>
          )}
        </Card>
      ))}
    </div>
  );
}

function Suppliers() {
  const { t } = useI18n();
  const list = useApi<Supplier[]>("store/suppliers");
  const [f, setF] = useState({ name: "", phone: "", email: "" });
  const add = useAction(async () => { await api("store/suppliers", { body: f }); setF({ name: "", phone: "", email: "" }); await list.reload(); });
  const del = useAction(async (id: string) => { await api(`store/suppliers/${id}`, { method: "DELETE" }); await list.reload(); });
  return (
    <div className="space-y-5">
      <Card className="space-y-3">
        <div className="grid gap-3 sm:grid-cols-3"><Field label={t("po.supplierName")}><Input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} /></Field><Field label={t("register.phone")}><Input dir="ltr" value={f.phone} onChange={(e) => setF({ ...f, phone: e.target.value })} /></Field><Field label={t("register.email")}><Input dir="ltr" value={f.email} onChange={(e) => setF({ ...f, email: e.target.value })} /></Field></div>
        <ErrorText error={add.error ?? del.error} /><Button loading={add.loading} disabled={f.name.trim().length < 2} onClick={() => void add.run()}>{t("po.addSupplier")}</Button>
      </Card>
      {(list.data ?? []).map((s) => <Card key={s.id} className="flex items-center justify-between text-sm"><span><b>{s.name}</b> <span className="text-slate-500" dir="ltr">{s.phone ?? ""} {s.email ?? ""}</span></span><Button size="sm" variant="ghost" onClick={() => void del.run(s.id)}>{t("common.delete")}</Button></Card>)}
    </div>
  );
}

function Transfers() {
  const { t, locale } = useI18n();
  const branches = useApi<Branch[]>("store/branches");
  const variants = useApi<Variant[]>("store/pos/labels");
  const list = useApi<Transfer[]>("store/inventory/transfers");
  const [f, setF] = useState({ variantId: "", from: "", to: "", qty: "1" });
  const go = useAction(async () => { await api("store/inventory/transfer", { body: { variantId: f.variantId, fromBranch: f.from, toBranch: f.to, quantity: Number(f.qty) } }); await list.reload(); });
  return (
    <div className="space-y-5">
      <Card className="space-y-3">
        <p className="text-sm text-slate-500">{t("po.transferHint")}</p>
        <div className="grid gap-3 sm:grid-cols-4">
          <Field label={t("po.product")}><Select value={f.variantId} onChange={(e) => setF({ ...f, variantId: e.target.value })}><option value="">-</option>{variants.data?.map((v) => <option key={v.variantId} value={v.variantId}>{v.name}{v.variantLabel ? ` (${v.variantLabel})` : ""} · {v.sku}</option>)}</Select></Field>
          <Field label={t("po.from")}><Select value={f.from} onChange={(e) => setF({ ...f, from: e.target.value })}><option value="">-</option>{branches.data?.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}</Select></Field>
          <Field label={t("po.to")}><Select value={f.to} onChange={(e) => setF({ ...f, to: e.target.value })}><option value="">-</option>{branches.data?.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}</Select></Field>
          <Field label={t("po.qty")}><Input dir="ltr" inputMode="numeric" value={f.qty} onChange={(e) => setF({ ...f, qty: e.target.value })} /></Field>
        </div>
        {(branches.data?.length ?? 0) < 2 && <Alert tone="amber">{t("po.needTwoBranches")}</Alert>}
        <ErrorText error={go.error} /><Button loading={go.loading} disabled={!f.variantId || !f.from || !f.to} onClick={() => void go.run()}>{t("po.transfer")}</Button>
      </Card>
      {(list.data ?? []).map((x) => <Card key={x.id} className="text-sm">{x.quantity} × {x.productName} <span className="text-slate-500" dir="ltr">{x.sku}</span>: {x.fromName} → {x.toName} <span className="text-xs text-slate-500">{dateOnly(x.createdAt, locale)}</span></Card>)}
    </div>
  );
}

export default function Purchasing() {
  const { t } = useI18n();
  const [tab, setTab] = useState<"orders" | "suppliers" | "transfers">("orders");
  return (
    <div className="space-y-5">
      <Tabs tabs={[{ key: "orders", label: t("po.tab.orders") }, { key: "suppliers", label: t("po.tab.suppliers") }, { key: "transfers", label: t("po.tab.transfers") }]} value={tab} onChange={setTab} />
      {tab === "orders" ? <Orders /> : tab === "suppliers" ? <Suppliers /> : <Transfers />}
    </div>
  );
}
