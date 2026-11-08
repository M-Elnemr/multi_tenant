"use client";

import { useState } from "react";
import { useSection } from "./section";
import { api } from "@/lib/client";
import { dateOnly, money, toMinor } from "@/lib/format";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Icon } from "../icons";
import { ConfirmDialog, Sheet } from "../overlay";
import { useToast } from "../toast";
import { Alert, Avatar, Badge, Button, Empty, ErrorText, Field, Input, KpiStrip, Loading, Section, Select, Table, Td } from "../ui";

type Po = { id: string; number: string; status: string; totalMinor: number; supplierName?: string | null; itemCount: number; createdAt: string };
type PoItem = { id: string; productName: string; sku: string; comboKey?: string; quantity: number; unitCostMinor: number };
type PoDetail = Po & { items: PoItem[] };
type Supplier = { id: string; name: string; phone?: string | null; email?: string | null };
type Variant = { variantId: string; sku: string; name: string; variantLabel?: string | null };
type Branch = { id: string; name: string };
type Transfer = { id: string; quantity: number; productName: string; sku: string; fromName: string; toName: string; createdAt: string };
const TONE: Record<string, "blue" | "amber" | "green" | "red"> = { DRAFT: "blue", ORDERED: "amber", RECEIVED: "green", CANCELLED: "red" };
const variantOptions = (v: Variant[] | null | undefined) => v?.map((x) => <option key={x.variantId} value={x.variantId}>{x.name}{x.variantLabel ? ` (${x.variantLabel})` : ""} · {x.sku}</option>);

function Orders() {
  const { t, locale, currency } = useI18n();
  const toast = useToast();
  const list = useApi<Po[]>("store/purchase-orders");
  const suppliers = useApi<Supplier[]>("store/suppliers");
  const variants = useApi<Variant[]>("store/pos/labels");
  const [open, setOpen] = useState<string | null>(null);
  const [cancelId, setCancelId] = useState<string | null>(null);
  const detail = useApi<PoDetail>(open ? `store/purchase-orders/${open}` : null);
  const [f, setF] = useState({ supplierId: "", variantId: "", qty: "10", cost: "" });
  const act = useAction(async (path: string, body: unknown = {}, done?: string) => { await api(path, { body }); if (done) toast(done); await list.reload(); if (open) await detail.reload(); });
  const draft = useAction(async () => { await api("store/purchase-orders/draft-low-stock", { body: { supplierId: f.supplierId || undefined } }); toast(t("cat.poDrafted")); await list.reload(); });
  const create = useAction(async () => { await api("store/purchase-orders", { body: { supplierId: f.supplierId || undefined, items: [{ variantId: f.variantId, quantity: Number(f.qty), unitCostMinor: f.cost ? toMinor(f.cost) : undefined }] } }); setF({ ...f, variantId: "", cost: "" }); toast(t("cat.poCreated")); await list.reload(); });
  if (!list.data) return <Loading variant="table" />;
  const count = (s: string) => list.data!.filter((p) => p.status === s).length;
  const owed = list.data.filter((p) => p.status === "ORDERED").reduce((s, p) => s + p.totalMinor, 0);
  const d = detail.data;
  return (
    <div className="space-y-5">
      <KpiStrip items={[
        { label: t("po.status.DRAFT"), value: count("DRAFT"), icon: "file", tone: "slate" },
        { label: t("po.status.ORDERED"), value: count("ORDERED"), icon: "truck", tone: "amber", hint: owed > 0 ? money(owed, currency, locale) : undefined },
        { label: t("po.status.RECEIVED"), value: count("RECEIVED"), icon: "check", tone: "green" },
        { label: t("po.status.CANCELLED"), value: count("CANCELLED"), icon: "close", tone: "red" },
      ]} />
      <Section title={t("po.new")} icon="plus">
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Field label={t("po.supplier")}><Select value={f.supplierId} onChange={(e) => setF({ ...f, supplierId: e.target.value })}><option value="">-</option>{suppliers.data?.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}</Select></Field>
          <Field label={t("po.product")}><Select value={f.variantId} onChange={(e) => setF({ ...f, variantId: e.target.value })}><option value="">-</option>{variantOptions(variants.data)}</Select></Field>
          <Field label={t("po.qty")}><Input dir="ltr" inputMode="numeric" value={f.qty} onChange={(e) => setF({ ...f, qty: e.target.value })} /></Field>
          <Field label={t("po.cost")}><Input dir="ltr" inputMode="decimal" value={f.cost} onChange={(e) => setF({ ...f, cost: e.target.value })} /></Field>
        </div>
        <div className="mt-4"><ErrorText error={create.error ?? draft.error} /></div>
        <div className="mt-4 flex flex-wrap gap-2"><Button variant="ink" loading={create.loading} disabled={!f.variantId} onClick={() => void create.run()}>{t("po.create")}</Button><Button variant="secondary" loading={draft.loading} onClick={() => void draft.run()}><Icon name="alert" className="h-4 w-4" />{t("po.draftLow")}</Button></div>
      </Section>
      <ErrorText error={act.error} />
      {list.data.length === 0 ? <Empty icon="truck" title={t("po.none")}>{t("cat.poEmptyBody")}</Empty> : (
        <Table head={[t("po.number"), t("po.supplier"), t("admin.created"), t("po.total"), t("admin.status"), ""]}>
          {list.data.map((p) => (
            <tr key={p.id} className="cursor-pointer" onClick={() => setOpen(p.id)}>
              <Td><span className="font-semibold text-ink" dir="ltr">{p.number}</span></Td>
              <Td>{p.supplierName ?? "—"}</Td>
              <Td className="text-muted">{dateOnly(p.createdAt, locale)}</Td>
              <Td className="font-semibold text-ink tnum">{money(p.totalMinor, currency, locale)}</Td>
              <Td><Badge tone={TONE[p.status]}>{t(`po.status.${p.status}`)}</Badge></Td>
              <Td className="w-10 text-end"><Icon name="chevron" className="h-4 w-4 text-faint flip-rtl" /></Td>
            </tr>
          ))}
        </Table>
      )}
      <Sheet open={!!open} onClose={() => setOpen(null)} width="30rem" title={d ? d.number : t("po.title")}
        footer={d && (d.status === "DRAFT" || d.status === "ORDERED") ? (
          <div className="flex flex-wrap gap-2">
            <Button variant="ink" loading={act.loading} onClick={() => void act.run(`store/purchase-orders/${d.id}/receive`, {}, t("cat.poReceived"))}><Icon name="check" className="h-4 w-4" />{t("po.receive")}</Button>
            {d.status === "DRAFT" && <Button variant="secondary" loading={act.loading} onClick={() => void act.run(`store/purchase-orders/${d.id}/ordered`, {}, t("cat.poOrdered"))}>{t("po.markOrdered")}</Button>}
            <Button variant="ghost" className="ms-auto !text-red-600" onClick={() => setCancelId(d.id)}>{t("po.cancel")}</Button>
          </div>
        ) : undefined}>
        {!d ? <Loading variant="list" /> : (
          <div className="space-y-4">
            <div className="flex flex-wrap items-center gap-2 text-sm text-muted"><Badge tone={TONE[d.status]}>{t(`po.status.${d.status}`)}</Badge><span>{d.supplierName ?? ""}</span><span>·</span><span>{dateOnly(d.createdAt, locale)}</span></div>
            <ul className="divide-y divide-line overflow-hidden rounded-[14px] ring-1 ring-line">
              {d.items.map((i) => (
                <li key={i.id} className="flex items-center justify-between gap-3 px-4 py-3 text-sm">
                  <span className="min-w-0"><span className="block truncate font-semibold text-ink">{i.productName}</span><span className="block text-xs text-muted" dir="ltr">{i.sku} · {i.quantity} × {money(i.unitCostMinor, currency, locale)}</span></span>
                  <span className="shrink-0 font-semibold text-ink tnum">{money(i.unitCostMinor * i.quantity, currency, locale)}</span>
                </li>
              ))}
            </ul>
            <div className="flex items-center justify-between rounded-[14px] bg-surface-2 px-4 py-3"><span className="text-sm font-medium text-muted">{t("po.total")}</span><span className="font-display text-lg font-semibold text-ink tnum">{money(d.totalMinor, currency, locale)}</span></div>
          </div>
        )}
      </Sheet>
      <ConfirmDialog open={!!cancelId} onClose={() => setCancelId(null)} danger loading={act.loading} title={t("po.cancel")} body={t("cat.poCancelBody")} confirmLabel={t("po.cancel")} cancelLabel={t("common.cancel")}
        onConfirm={() => { if (cancelId) void act.run(`store/purchase-orders/${cancelId}/cancel`, {}, t("cat.poCancelled")).then(() => setCancelId(null)); }} />
    </div>
  );
}

function Suppliers() {
  const { t } = useI18n();
  const toast = useToast();
  const list = useApi<Supplier[]>("store/suppliers");
  const [f, setF] = useState({ name: "", phone: "", email: "" });
  const [del, setDel] = useState<Supplier | null>(null);
  const add = useAction(async () => { await api("store/suppliers", { body: f }); setF({ name: "", phone: "", email: "" }); toast(t("cat.supplierAdded")); await list.reload(); });
  const remove = useAction(async (id: string) => { await api(`store/suppliers/${id}`, { method: "DELETE" }); setDel(null); toast(t("cat.supplierRemoved")); await list.reload(); });
  return (
    <div className="space-y-5">
      <Section title={t("po.addSupplier")} icon="plus">
        <div className="grid gap-3 sm:grid-cols-3"><Field label={t("po.supplierName")}><Input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} /></Field><Field label={t("register.phone")}><Input dir="ltr" value={f.phone} onChange={(e) => setF({ ...f, phone: e.target.value })} /></Field><Field label={t("register.email")}><Input dir="ltr" value={f.email} onChange={(e) => setF({ ...f, email: e.target.value })} /></Field></div>
        <div className="mt-4"><ErrorText error={add.error} /></div>
        <div className="mt-4"><Button variant="ink" loading={add.loading} disabled={f.name.trim().length < 2} onClick={() => void add.run()}>{t("po.addSupplier")}</Button></div>
      </Section>
      <ErrorText error={remove.error} />
      {!list.data ? <Loading variant="list" /> : list.data.length === 0 ? <Empty icon="users" title={t("cat.suppliersEmpty")}>{t("cat.suppliersEmptyBody")}</Empty> : (
        <ul className="divide-y divide-line overflow-hidden rounded-[14px] bg-surface shadow-card">
          {list.data.map((s) => (
            <li key={s.id} className="flex items-center gap-3 px-4 py-3">
              <Avatar name={s.name} />
              <span className="min-w-0 flex-1"><span className="block truncate text-sm font-semibold text-ink">{s.name}</span><span className="block truncate text-xs text-muted" dir="ltr">{[s.phone, s.email].filter(Boolean).join(" · ") || "—"}</span></span>
              <button type="button" onClick={() => setDel(s)} aria-label={t("common.delete")} className="grid h-9 w-9 place-items-center rounded-[10px] text-muted transition hover:bg-red-50 hover:text-red-600 pointer-coarse:h-11 pointer-coarse:w-11"><Icon name="close" className="h-4 w-4" /></button>
            </li>
          ))}
        </ul>
      )}
      <ConfirmDialog open={!!del} onClose={() => setDel(null)} danger loading={remove.loading} title={t("common.delete")} body={del?.name} confirmLabel={t("common.delete")} cancelLabel={t("common.cancel")} onConfirm={() => del && void remove.run(del.id)} />
    </div>
  );
}

function Transfers() {
  const { t, locale } = useI18n();
  const toast = useToast();
  const branches = useApi<Branch[]>("store/branches");
  const variants = useApi<Variant[]>("store/pos/labels");
  const list = useApi<Transfer[]>("store/inventory/transfers");
  const [f, setF] = useState({ variantId: "", from: "", to: "", qty: "1" });
  const go = useAction(async () => { await api("store/inventory/transfer", { body: { variantId: f.variantId, fromBranch: f.from, toBranch: f.to, quantity: Number(f.qty) } }); toast(t("cat.transferDone")); await list.reload(); });
  return (
    <div className="space-y-5">
      <Section title={t("po.transfer")} subtitle={t("po.transferHint")} icon="truck">
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Field label={t("po.product")}><Select value={f.variantId} onChange={(e) => setF({ ...f, variantId: e.target.value })}><option value="">-</option>{variantOptions(variants.data)}</Select></Field>
          <Field label={t("po.from")}><Select value={f.from} onChange={(e) => setF({ ...f, from: e.target.value })}><option value="">-</option>{branches.data?.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}</Select></Field>
          <Field label={t("po.to")}><Select value={f.to} onChange={(e) => setF({ ...f, to: e.target.value })}><option value="">-</option>{branches.data?.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}</Select></Field>
          <Field label={t("po.qty")}><Input dir="ltr" inputMode="numeric" value={f.qty} onChange={(e) => setF({ ...f, qty: e.target.value })} /></Field>
        </div>
        {(branches.data?.length ?? 0) < 2 && <div className="mt-4"><Alert tone="amber">{t("po.needTwoBranches")}</Alert></div>}
        <div className="mt-4"><ErrorText error={go.error} /></div>
        <div className="mt-4"><Button variant="ink" loading={go.loading} disabled={!f.variantId || !f.from || !f.to} onClick={() => void go.run()}>{t("po.transfer")}</Button></div>
      </Section>
      {!list.data ? <Loading variant="list" /> : list.data.length === 0 ? <Empty icon="truck" title={t("cat.transfersEmpty")}>{t("cat.transfersEmptyBody")}</Empty> : (
        <ul className="divide-y divide-line overflow-hidden rounded-[14px] bg-surface shadow-card">
          {list.data.map((x) => (
            <li key={x.id} className="flex flex-wrap items-center gap-x-4 gap-y-1 px-4 py-3 text-sm">
              <span className="min-w-0 flex-1"><span className="block truncate font-semibold text-ink">{x.productName}</span><span className="block text-xs text-muted" dir="ltr">{x.sku}</span></span>
              <span className="inline-flex items-center gap-2 text-ink-2">{x.fromName}<Icon name="arrow" className="h-3.5 w-3.5 text-faint flip-rtl" />{x.toName}</span>
              <span className="rounded-full bg-surface-2 px-2.5 py-0.5 text-[12.5px] font-semibold text-ink tnum">× {x.quantity}</span>
              <span className="text-xs text-muted">{dateOnly(x.createdAt, locale)}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export default function Purchasing() {
  const [tab] = useSection(["orders", "suppliers", "transfers"] as const, "orders");
  return (
    <div className="space-y-5">
      {tab === "orders" ? <Orders /> : tab === "suppliers" ? <Suppliers /> : <Transfers />}
    </div>
  );
}
