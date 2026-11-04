"use client";

import Link from "next/link";
import { useState } from "react";
import { api } from "@/lib/client";
import { money } from "@/lib/format";
import { useAction, useApi, useMe } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Alert, Badge, Button, Card, Empty, ErrorText, Field, Input, Loading, Modal, Select, Stat, Table, Tabs, Td } from "../ui";

type Device = { id: string; name: string; code: string; branchName: string; receiptSize: string | null; lastSeenAt: string | null; revokedAt: string | null; openIssues: number };
type Member = { userId: string; name: string; hasPin: boolean; canRefund: boolean; canCash: boolean; canManage: boolean };
type ShiftRow = { id: string; openedAt: string; closedAt: string | null; status: string; openingFloatMinor: number; countedCashMinor: number | null; expectedCashMinor: number | null; differenceMinor: number | null;
  handoverKind: string | null; currency: string; deviceName: string; cashierName: string; handedToName: string | null; sales: number; salesMinor: number };
type Issue = { id: string; kind: string; detail: string; createdAt: string; resolvedAt: string | null; deviceName: string | null };
type CountRow = { id: string; name: string; status: string; createdAt: string; branchName: string; createdByName: string; lines: number; differences: number };
type Summary = { day: string; totals: { sales: number; totalMinor: number; discountMinor: number; offlineSales: number }; byMethod: { method: string; amountMinor: number }[];
  byCashier: { name: string; sales: number; totalMinor: number }[]; byDevice: { name: string; sales: number; totalMinor: number }[]; returns: { returns: number; refundMinor: number };
  topItems: { name: string; label: string | null; qty: number; totalMinor: number }[] };

type Tab = "today" | "shifts" | "terminals" | "cashiers" | "issues" | "counts" | "settings";

/** The manager's side of the till: the day, shifts and their reports, terminals, cashier PINs, sync issues, stock counts and the till settings. */
export default function PosHub() {
  const { t, locale, currency } = useI18n();
  const { can } = useMe();
  const manage = can("pos.manage");
  const [tab, setTab] = useState<Tab>("today");
  const issues = useApi<Issue[]>(manage ? "store/pos/issues" : null);
  const m = (v: number | null | undefined, cur = currency) => money(v ?? 0, cur, locale);
  const tabs: { key: Tab; label: string; count?: number }[] = [
    { key: "today", label: t("ph.today") },
    ...(manage ? [{ key: "shifts" as Tab, label: t("ph.shifts") }, { key: "terminals" as Tab, label: t("ph.terminals") }, { key: "cashiers" as Tab, label: t("ph.cashiers") },
      { key: "issues" as Tab, label: t("ph.issues"), count: issues.data?.length || undefined }] : []),
    ...(can("inventory.adjust") || manage ? [{ key: "counts" as Tab, label: t("ph.counts") }] : []),
    ...(manage ? [{ key: "settings" as Tab, label: t("ph.settings") }] : []),
  ];
  return (
    <div className="space-y-5">
      <Card className="flex flex-wrap items-center justify-between gap-3">
        <div><h2 className="text-lg font-bold">{t("ph.openTitle")}</h2><p className="text-sm text-slate-600">{t("ph.openHint")}</p></div>
        <div className="flex flex-wrap gap-2">
          <Link href="/pos" className="inline-flex items-center rounded-xl bg-brand-gradient px-5 py-2.5 text-sm font-bold text-white shadow-brand">{t("ph.openTerminal")} ↗</Link>
          <Link href="/dashboard/products/labels" className="inline-flex items-center rounded-xl border bg-white px-4 py-2 text-sm font-semibold hover:bg-slate-50">{t("pos.labels")}</Link>
        </div>
      </Card>
      <Tabs tabs={tabs} value={tab} onChange={setTab} />
      {tab === "today" && <Today m={m} />}
      {tab === "shifts" && <Shifts m={m} />}
      {tab === "terminals" && <Terminals />}
      {tab === "cashiers" && <Cashiers />}
      {tab === "issues" && <Issues data={issues.data} error={issues.error} reload={issues.reload} />}
      {tab === "counts" && <Counts />}
      {tab === "settings" && <Settings />}
    </div>
  );
}

function Today({ m }: { m: (v: number | null | undefined) => string }) {
  const { t } = useI18n();
  const [day, setDay] = useState("");
  const s = useApi<Summary>(`store/pos/summary${day ? `?day=${day}` : ""}`);
  if (!s.data) return s.error ? <ErrorText error={s.error} /> : <Loading />;
  const d = s.data;
  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2"><Input type="date" value={day || d.day} onChange={(e) => setDay(e.target.value)} className="max-w-48" /></div>
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label={t("ph.sales")} value={d.totals.sales} icon="bag" hint={d.totals.offlineSales ? t("ph.offlineSales", { n: String(d.totals.offlineSales) }) : undefined} />
        <Stat label={t("ph.revenue")} value={m(d.totals.totalMinor)} icon="wallet" tone="green" />
        <Stat label={t("ph.discounts")} value={m(d.totals.discountMinor)} icon="tag" tone="amber" />
        <Stat label={t("ph.returns")} value={`${d.returns.returns} · ${m(d.returns.refundMinor)}`} icon="return" tone="red" />
      </div>
      <div className="grid gap-4 lg:grid-cols-3">
        <Card><p className="mb-2 font-semibold">{t("ph.byMethod")}</p>{d.byMethod.map((x) => <Row key={x.method} k={t(`pay.${x.method}`)} v={m(x.amountMinor)} />)}{!d.byMethod.length && <p className="text-sm text-slate-500">—</p>}</Card>
        <Card><p className="mb-2 font-semibold">{t("ph.byCashier")}</p>{d.byCashier.map((x) => <Row key={x.name} k={`${x.name} (${x.sales})`} v={m(x.totalMinor)} />)}{!d.byCashier.length && <p className="text-sm text-slate-500">—</p>}</Card>
        <Card><p className="mb-2 font-semibold">{t("ph.byTerminal")}</p>{d.byDevice.map((x) => <Row key={x.name} k={`${x.name} (${x.sales})`} v={m(x.totalMinor)} />)}{!d.byDevice.length && <p className="text-sm text-slate-500">—</p>}</Card>
      </div>
      <Card><p className="mb-2 font-semibold">{t("ph.topItems")}</p>{d.topItems.map((x, i) => <Row key={i} k={`${x.qty} × ${x.name}${x.label ? ` (${x.label})` : ""}`} v={m(x.totalMinor)} />)}{!d.topItems.length && <p className="text-sm text-slate-500">—</p>}</Card>
    </div>
  );
}

function Row({ k, v }: { k: string; v: string }) { return <div className="flex justify-between gap-3 border-b border-slate-100 py-1.5 text-sm last:border-0"><span className="text-slate-600">{k}</span><b className="tabular-nums">{v}</b></div>; }

function Shifts({ m }: { m: (v: number | null | undefined, cur?: string) => string }) {
  const { t, locale } = useI18n();
  const list = useApi<ShiftRow[]>("store/pos/shifts");
  const [open, setOpen] = useState<string | null>(null);
  const detail = useApi<Record<string, unknown>>(open ? `store/pos/shifts/${open}` : null);
  const when = (iso: string | null) => (iso ? new Date(iso).toLocaleString(locale === "ar" ? "ar-EG" : "en-GB", { dateStyle: "short", timeStyle: "short" }) : "—");
  if (!list.data) return list.error ? <ErrorText error={list.error} /> : <Loading />;
  if (!list.data.length) return <Empty>{t("ph.noShifts")}</Empty>;
  const r = detail.data as null | { byMethod: { method: string; sales: number; amountMinor: number }[]; returns: { refundMethod: string; returns: number; refundMinor: number }[];
    cashMoves: { kind: string; amountMinor: number; reason: string; at: string; byName: string }[]; totals: { sales: number; totalMinor: number; discountMinor: number }; expectedNowMinor: number;
    topItems: { name: string; label: string | null; qty: number; totalMinor: number }[]; currency: string; openingFloatMinor: number; countedCashMinor: number | null; differenceMinor: number | null; status: string; note: string | null };
  return (
    <>
      <Table head={[t("ph.cashier"), t("ph.terminal"), t("ph.opened"), t("ph.closed"), t("ph.sales"), t("ph.expected"), t("ph.counted"), t("ph.difference")]}>
        {list.data.map((s) => (
          <tr key={s.id} className="cursor-pointer" onClick={() => setOpen(s.id)}>
            <Td className="font-semibold">{s.cashierName}</Td><Td>{s.deviceName}</Td><Td>{when(s.openedAt)}</Td><Td>{s.status === "OPEN" ? <Badge tone="green">{t("ph.open")}</Badge> : when(s.closedAt)}</Td>
            <Td>{s.sales} · {m(s.salesMinor, s.currency)}</Td><Td>{s.expectedCashMinor === null ? "—" : m(s.expectedCashMinor, s.currency)}</Td><Td>{s.countedCashMinor === null ? "—" : m(s.countedCashMinor, s.currency)}</Td>
            <Td>{s.differenceMinor === null ? "—" : <span className={s.differenceMinor === 0 ? "text-emerald-700" : s.differenceMinor > 0 ? "text-sky-700" : "font-bold text-red-600"}>{m(s.differenceMinor, s.currency)}</span>}</Td>
          </tr>
        ))}
      </Table>
      <Modal open={!!open} onClose={() => setOpen(null)} title={t("ph.shiftReport")} wide>
        {!r ? <Loading /> : (
          <div className="space-y-3 text-sm">
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
              <Stat label={t("ph.sales")} value={`${r.totals.sales}`} hint={m(r.totals.totalMinor, r.currency)} />
              <Stat label={t("ph.float")} value={m(r.openingFloatMinor, r.currency)} />
              <Stat label={t("ph.expected")} value={m(r.expectedNowMinor, r.currency)} />
              <Stat label={t("ph.counted")} value={r.countedCashMinor === null ? "—" : m(r.countedCashMinor, r.currency)} tone={r.differenceMinor && r.differenceMinor < 0 ? "red" : "green"} hint={r.differenceMinor === null ? undefined : `${t("ph.difference")}: ${m(r.differenceMinor, r.currency)}`} />
            </div>
            <Card><p className="mb-1 font-semibold">{t("ph.byMethod")}</p>{r.byMethod.map((x) => <Row key={x.method} k={`${t(`pay.${x.method}`)} (${x.sales})`} v={m(x.amountMinor, r.currency)} />)}</Card>
            {r.returns.length > 0 && <Card><p className="mb-1 font-semibold">{t("ph.returns")}</p>{r.returns.map((x, i) => <Row key={i} k={`${t(`pay.${x.refundMethod ?? "CASH"}`)} (${x.returns})`} v={`-${m(x.refundMinor, r.currency)}`} />)}</Card>}
            {r.cashMoves.length > 0 && <Card><p className="mb-1 font-semibold">{t("ph.cashMoves")}</p>{r.cashMoves.map((x, i) => <Row key={i} k={`${t(`term.cash.${x.kind}`)} · ${x.reason ?? ""} · ${x.byName}`} v={m(x.amountMinor, r.currency)} />)}</Card>}
            <Card><p className="mb-1 font-semibold">{t("ph.topItems")}</p>{r.topItems.map((x, i) => <Row key={i} k={`${x.qty} × ${x.name}${x.label ? ` (${x.label})` : ""}`} v={m(x.totalMinor, r.currency)} />)}</Card>
            {r.note && <p className="text-slate-600">📝 {r.note}</p>}
            <Button variant="secondary" onClick={() => window.print()}>{t("pos.print")}</Button>
          </div>
        )}
      </Modal>
    </>
  );
}

function Terminals() {
  const { t, locale } = useI18n();
  const list = useApi<Device[]>("store/pos/devices");
  const [edit, setEdit] = useState<Device | null>(null);
  const save = useAction(async (d: Device, name: string, size: string) => { await api(`store/pos/devices/${d.id}`, { method: "PATCH", body: { name, receiptSize: size } }); setEdit(null); await list.reload(); });
  const revoke = useAction(async (d: Device) => { await api(`store/pos/devices/${d.id}/revoke`, { body: {} }); await list.reload(); });
  if (!list.data) return list.error ? <ErrorText error={list.error} /> : <Loading />;
  return (
    <div className="space-y-3">
      <Alert tone="blue">{t("ph.registerHint")}</Alert>
      {!list.data.length ? <Empty>{t("ph.noTerminals")}</Empty> : (
        <Table head={[t("ph.terminal"), t("ph.branch"), t("ph.receipt"), t("ph.lastSeen"), t("ph.issues"), ""]}>
          {list.data.map((d) => (
            <tr key={d.id}>
              <Td className="font-semibold">{d.name} <span className="text-xs text-slate-500" dir="ltr">({d.code})</span>{d.revokedAt && <Badge tone="red">{t("ph.revoked")}</Badge>}</Td>
              <Td>{d.branchName}</Td><Td>{d.receiptSize ? (d.receiptSize === "A4" ? "A4" : `${d.receiptSize}mm`) : t("term.shopDefault")}</Td>
              <Td>{d.lastSeenAt ? new Date(d.lastSeenAt).toLocaleString(locale === "ar" ? "ar-EG" : "en-GB", { dateStyle: "short", timeStyle: "short" }) : "—"}</Td>
              <Td>{d.openIssues > 0 ? <Badge tone="amber">{d.openIssues}</Badge> : "—"}</Td>
              <Td>{!d.revokedAt && <div className="flex gap-2"><Button size="sm" variant="secondary" onClick={() => setEdit(d)}>{t("common.edit")}</Button><Button size="sm" variant="danger" loading={revoke.loading} onClick={() => { if (confirm(t("ph.revokeConfirm"))) void revoke.run(d); }}>{t("ph.revoke")}</Button></div>}</Td>
            </tr>
          ))}
        </Table>
      )}
      <ErrorText error={revoke.error} />
      {edit && <EditTerminal d={edit} onClose={() => setEdit(null)} onSave={(n, s) => void save.run(edit, n, s)} busy={save.loading} error={save.error} />}
    </div>
  );
}

function EditTerminal({ d, onClose, onSave, busy, error }: { d: Device; onClose: () => void; onSave: (name: string, size: string) => void; busy: boolean; error: unknown }) {
  const { t } = useI18n();
  const [name, setName] = useState(d.name);
  const [size, setSize] = useState(d.receiptSize ?? "");
  return (
    <Modal open onClose={onClose} title={d.name}>
      <div className="space-y-3">
        <Field label={t("term.terminalName")}><Input value={name} onChange={(e) => setName(e.target.value)} /></Field>
        <Field label={t("term.receiptSize")}><Select value={size} onChange={(e) => setSize(e.target.value)}><option value="">{t("term.shopDefault")}</option><option value="58">58mm</option><option value="80">80mm</option><option value="A4">A4</option></Select></Field>
        <ErrorText error={error} />
        <Button className="w-full" loading={busy} onClick={() => onSave(name, size)}>{t("common.save")}</Button>
      </div>
    </Modal>
  );
}

function Cashiers() {
  const { t } = useI18n();
  const list = useApi<Member[]>("store/pos/team");
  const [who, setWho] = useState<Member | null>(null);
  const [pin, setPin] = useState("");
  const save = useAction(async () => { if (!who) return; await api(`store/pos/team/${who.userId}/pin`, { method: "PUT", body: { pin } }); setWho(null); setPin(""); await list.reload(); });
  const clear = useAction(async (mm: Member) => { await api(`store/pos/team/${mm.userId}/pin`, { method: "DELETE" }); await list.reload(); });
  if (!list.data) return list.error ? <ErrorText error={list.error} /> : <Loading />;
  return (
    <div className="space-y-3">
      <Alert tone="blue">{t("ph.pinHint")}</Alert>
      <Table head={[t("ph.cashier"), t("ph.rights"), t("ph.pin"), ""]}>
        {list.data.map((x) => (
          <tr key={x.userId}>
            <Td className="font-semibold">{x.name}</Td>
            <Td><div className="flex flex-wrap gap-1">{x.canManage && <Badge tone="blue">{t("ph.rManage")}</Badge>}{x.canRefund && <Badge>{t("ph.rRefund")}</Badge>}{x.canCash && <Badge>{t("ph.rCash")}</Badge>}</div></Td>
            <Td>{x.hasPin ? <Badge tone="green">{t("ph.pinSet")}</Badge> : <Badge tone="amber">{t("ph.noPin")}</Badge>}</Td>
            <Td><div className="flex gap-2"><Button size="sm" variant="secondary" onClick={() => { setWho(x); setPin(""); }}>{x.hasPin ? t("ph.changePin") : t("ph.setPin")}</Button>{x.hasPin && <Button size="sm" variant="ghost" onClick={() => void clear.run(x)}>{t("ph.clearPin")}</Button>}</div></Td>
          </tr>
        ))}
      </Table>
      <p className="text-xs text-slate-500">{t("ph.whoIsCashier")}</p>
      <Modal open={!!who} onClose={() => setWho(null)} title={`${t("ph.setPin")} · ${who?.name ?? ""}`}>
        <div className="space-y-3">
          <Field label={t("ph.pin")} hint={t("ph.pinRule")}><Input dir="ltr" inputMode="numeric" maxLength={6} value={pin} onChange={(e) => setPin(e.target.value.replace(/\D/g, ""))} /></Field>
          <ErrorText error={save.error} />
          <Button className="w-full" disabled={pin.length < 4} loading={save.loading} onClick={() => void save.run()}>{t("common.save")}</Button>
        </div>
      </Modal>
    </div>
  );
}

function Issues({ data, error, reload }: { data: Issue[] | null; error: unknown; reload: () => Promise<void> }) {
  const { t, locale } = useI18n();
  const resolve = useAction(async (i: Issue) => { await api(`store/pos/issues/${i.id}/resolve`, { body: {} }); await reload(); });
  if (!data) return error ? <ErrorText error={error} /> : <Loading />;
  if (!data.length) return <Empty>{t("ph.noIssues")}</Empty>;
  return (
    <div className="space-y-2">
      <p className="text-sm text-slate-600">{t("ph.issuesHint")}</p>
      {data.map((i) => {
        let d: Record<string, unknown> = {};
        try { d = JSON.parse(i.detail); } catch { d = {}; }
        return (
          <Card key={i.id} className="flex flex-wrap items-start justify-between gap-3">
            <div className="min-w-0 flex-1 text-sm">
              <div className="flex items-center gap-2"><Badge tone={i.kind === "NEGATIVE_STOCK" || i.kind === "FAILED" ? "red" : "amber"}>{t(`ph.kind.${i.kind}`)}</Badge><span className="text-xs text-slate-500">{i.deviceName} · {new Date(i.createdAt).toLocaleString(locale === "ar" ? "ar-EG" : "en-GB")}</span></div>
              <p className="mt-1">{issueText(i.kind, d, t)}</p>
            </div>
            <Button size="sm" variant="secondary" loading={resolve.loading} onClick={() => void resolve.run(i)}>{t("ph.resolve")}</Button>
          </Card>
        );
      })}
      <ErrorText error={resolve.error} />
    </div>
  );
}

function issueText(kind: string, d: Record<string, unknown>, t: (k: string, p?: Record<string, string>) => string): string {
  const s = (k: string) => String(d[k] ?? "");
  switch (kind) {
    case "NEGATIVE_STOCK": return t("ph.why.NEGATIVE_STOCK", { name: s("name"), sold: s("sold"), missing: s("notInStock"), order: s("orderNumber") });
    case "PRICE_CHANGED": return t("ph.why.PRICE_CHANGED", { name: s("name"), was: (Number(d.soldAtMinor) / 100).toFixed(2), now: (Number(d.priceNowMinor) / 100).toFixed(2) });
    case "PRODUCT_INACTIVE": return t("ph.why.PRODUCT_INACTIVE", { name: s("name") });
    case "RETURN_EXCEEDS": return t("ph.why.RETURN_EXCEEDS");
    case "SHIFT_MISMATCH": return d.why ? t("ph.why.PAYMENTS", { why: s("why") }) : t("ph.why.SHIFT_MISMATCH", { a: (Number(d.terminalExpected) / 100).toFixed(2), b: (Number(d.serverExpected) / 100).toFixed(2) });
    case "CLOCK": return t("ph.why.CLOCK");
    default: return t("ph.why.FAILED", { error: s("error") });
  }
}

function Counts() {
  const { t, locale } = useI18n();
  const { can } = useMe();
  const list = useApi<CountRow[]>("store/pos/counts");
  const [open, setOpen] = useState<string | null>(null);
  const detail = useApi<{ status: string; lines: { variantId: string; name: string; sku: string; comboKey: string | null; expectedAtCount: number; counted: number; difference: number; onHandNow: number; costPriceMinor: number | null }[] }>(open ? `store/pos/counts/${open}` : null);
  const apply = useAction(async () => { await api(`store/pos/counts/${open}/apply`, { body: {} }); await detail.reload(); await list.reload(); });
  const cancel = useAction(async () => { await api(`store/pos/counts/${open}/cancel`, { body: {} }); setOpen(null); await list.reload(); });
  if (!list.data) return list.error ? <ErrorText error={list.error} /> : <Loading />;
  return (
    <div className="space-y-3">
      <Alert tone="blue">{t("ph.countsHint")}</Alert>
      {!list.data.length ? <Empty>{t("ph.noCounts")}</Empty> : (
        <Table head={[t("term.countName"), t("ph.branch"), t("ph.by"), t("term.lines"), t("term.differences"), t("ph.status")]}>
          {list.data.map((c) => (
            <tr key={c.id} className="cursor-pointer" onClick={() => setOpen(c.id)}>
              <Td className="font-semibold">{c.name}<span className="block text-xs text-slate-500">{new Date(c.createdAt).toLocaleString(locale === "ar" ? "ar-EG" : "en-GB", { dateStyle: "short", timeStyle: "short" })}</span></Td>
              <Td>{c.branchName}</Td><Td>{c.createdByName}</Td><Td>{c.lines}</Td><Td>{c.differences}</Td><Td><Badge tone={c.status === "APPLIED" ? "green" : c.status === "CANCELLED" ? "slate" : "amber"}>{t(`ph.cs.${c.status}`)}</Badge></Td>
            </tr>
          ))}
        </Table>
      )}
      <Modal open={!!open} onClose={() => setOpen(null)} title={t("ph.countTitle")} wide>
        {!detail.data ? <Loading /> : (
          <div className="space-y-3">
            <Table head={[t("ph.product"), t("term.expected"), t("term.counted"), t("ph.difference"), t("ph.onHandNow")]}>
              {detail.data.lines.map((l) => (
                <tr key={l.variantId}><Td className="font-semibold">{l.name}<span className="block text-xs text-slate-500" dir="ltr">{l.sku}</span></Td><Td>{l.expectedAtCount}</Td><Td>{l.counted}</Td>
                  <Td><span className={l.difference === 0 ? "text-slate-500" : l.difference > 0 ? "font-bold text-sky-700" : "font-bold text-red-600"}>{l.difference > 0 ? `+${l.difference}` : l.difference}</span></Td><Td>{l.onHandNow}</Td></tr>
              ))}
            </Table>
            {["COUNTING", "REVIEW"].includes(detail.data.status) && (
              <div className="flex gap-2">
                <Button variant="secondary" loading={cancel.loading} onClick={() => void cancel.run()}>{t("ph.cancelCount")}</Button>
                {can("inventory.adjust") && <Button className="flex-1" loading={apply.loading} onClick={() => { if (confirm(t("ph.applyConfirm"))) void apply.run(); }}>{t("ph.applyCount")}</Button>}
              </div>
            )}
            <p className="text-xs text-slate-500">{t("ph.applyHint")}</p>
            <ErrorText error={apply.error ?? cancel.error} />
          </div>
        )}
      </Modal>
    </div>
  );
}

function Settings() {
  const { t } = useI18n();
  const s = useApi<{ receiptSize: string; receiptFooter: string; posMaxDiscountPct: number }>("store/pos/settings");
  const [form, setForm] = useState<{ receiptSize: string; receiptFooter: string; posMaxDiscountPct: string } | null>(null);
  const f = form ?? (s.data ? { receiptSize: s.data.receiptSize, receiptFooter: s.data.receiptFooter, posMaxDiscountPct: String(s.data.posMaxDiscountPct) } : null);
  const save = useAction(async () => { if (!f) return; await api("store/pos/settings", { method: "PUT", body: { receiptSize: f.receiptSize, receiptFooter: f.receiptFooter, posMaxDiscountPct: Number(f.posMaxDiscountPct) } }); setForm(null); await s.reload(); });
  if (!f) return s.error ? <ErrorText error={s.error} /> : <Loading />;
  return (
    <Card className="max-w-xl space-y-4">
      <Field label={t("term.receiptSize")} hint={t("ph.sizeHint")}><Select value={f.receiptSize} onChange={(e) => setForm({ ...f, receiptSize: e.target.value })}><option value="58">58mm</option><option value="80">80mm</option><option value="A4">A4</option></Select></Field>
      <Field label={t("ph.footer")} hint={t("ph.footerHint")}><Input value={f.receiptFooter} onChange={(e) => setForm({ ...f, receiptFooter: e.target.value })} maxLength={300} /></Field>
      <Field label={t("ph.maxDiscount")} hint={t("ph.maxDiscountHint")}><Input dir="ltr" inputMode="numeric" value={f.posMaxDiscountPct} onChange={(e) => setForm({ ...f, posMaxDiscountPct: e.target.value.replace(/\D/g, "") })} /></Field>
      <ErrorText error={save.error} />
      <Button loading={save.loading} onClick={() => void save.run()}>{t("common.save")}</Button>
    </Card>
  );
}
