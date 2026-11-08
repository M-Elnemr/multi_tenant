"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { api } from "@/lib/client";
import { money } from "@/lib/format";
import { useAction, useApi, useMe } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Icon } from "../icons";
import { ConfirmDialog } from "../overlay";
import { useToast } from "../toast";
import { Alert, Badge, Button, Empty, ErrorText, Field, Input, KpiStrip, Loading, Modal, Select } from "../ui";
import { useSection } from "./section";
import { Block, Notice, Panel, Rise, RowMenu } from "./sell/kit";

type Device = { id: string; name: string; code: string; branchName: string; receiptSize: string | null; lastSeenAt: string | null; revokedAt: string | null; openIssues: number };
type Member = { userId: string; name: string; hasPin: boolean; canRefund: boolean; canCash: boolean; canManage: boolean };
type ShiftRow = { id: string; openedAt: string; closedAt: string | null; status: string; openingFloatMinor: number; countedCashMinor: number | null; expectedCashMinor: number | null; differenceMinor: number | null;
  handoverKind: string | null; currency: string; deviceName: string; cashierName: string; handedToName: string | null; sales: number; salesMinor: number };
type Issue = { id: string; kind: string; detail: string; createdAt: string; resolvedAt: string | null; deviceName: string | null };
type CountRow = { id: string; name: string; status: string; createdAt: string; branchName: string; createdByName: string; lines: number; differences: number };
type Summary = { day: string; totals: { sales: number; totalMinor: number; discountMinor: number; offlineSales: number }; byMethod: { method: string; amountMinor: number }[];
  byCashier: { name: string; sales: number; totalMinor: number }[]; byDevice: { name: string; sales: number; totalMinor: number }[]; returns: { returns: number; refundMinor: number };
  byHour: { hour: number; sales: number; totalMinor: number }[]; topItems: { name: string; label: string | null; qty: number; totalMinor: number }[] };

type Tab = "today" | "shifts" | "terminals" | "cashiers" | "issues" | "counts" | "settings";
type M = (v: number | null | undefined, cur?: string) => string;

const when = (iso: string | null, locale: string) => (iso ? new Date(iso).toLocaleString(locale === "ar" ? "ar-EG" : "en-GB", { dateStyle: "short", timeStyle: "short" }) : "—");

/** The manager's side of the till: the day, shifts and their reports, terminals, cashier PINs, sync issues, stock counts and the till settings. Everything wraps: nothing scrolls sideways. */
export default function PosHub() {
  const { t, locale, currency } = useI18n();
  const { can } = useMe();
  const manage = can("pos.manage");
  const issues = useApi<Issue[]>(manage ? "store/pos/issues" : null);
  const devices = useApi<Device[]>(manage ? "store/pos/devices" : null);
  const m: M = (v, cur = currency) => money(v ?? 0, cur, locale);
  const tabs: { key: Tab; label: string; count?: number }[] = [
    { key: "today", label: t("ph.today") },
    ...(manage ? [{ key: "shifts" as Tab, label: t("ph.shifts") }, { key: "terminals" as Tab, label: t("ph.terminals") }, { key: "cashiers" as Tab, label: t("ph.cashiers") },
      { key: "issues" as Tab, label: t("ph.issues"), count: issues.data?.length || undefined }] : []),
    ...(can("inventory.adjust") || manage ? [{ key: "counts" as Tab, label: t("ph.counts") }] : []),
    ...(manage ? [{ key: "settings" as Tab, label: t("ph.settings") }] : []),
  ];
  // each part has its own item in the side menu (flat menu), so the hub shows one part at a time with no tab bar
  const [tab] = useSection<Tab>(tabs.map((x) => x.key), "today");
  const noTerminals = manage && devices.data && devices.data.filter((x) => !x.revokedAt).length === 0;
  return (
    <div className="min-w-0 space-y-5">
      {noTerminals && (tab === "today" || tab === "terminals") && <GetStarted />}
      {tab !== "issues" && !!issues.data?.length && (
        <Notice icon="alert" action={<Link href="/dashboard/pos?tab=issues" className="inline-flex items-center gap-1 text-[12.5px] font-semibold text-amber-900 hover:underline">{t("ph.issues")}<Icon name="chevron" className="h-3.5 w-3.5 flip-rtl" /></Link>}>
          <span className="font-semibold">{t("ph.issues")}: <span className="tnum">{issues.data.length}</span></span>
        </Notice>
      )}
      {tab === "today" && <Today m={m} />}
      {tab === "shifts" && <Shifts m={m} />}
      {tab === "terminals" && <Terminals list={devices} />}
      {tab === "cashiers" && <Cashiers />}
      {tab === "issues" && <Issues data={issues.data} error={issues.error} reload={issues.reload} />}
      {tab === "counts" && <Counts m={m} />}
      {tab === "settings" && <Settings />}
    </div>
  );
}

function GetStarted() {
  const { t } = useI18n();
  const steps = [t("ph.gs1"), t("ph.gs2"), t("ph.gs3")];
  return (
    <Panel className="bg-[linear-gradient(135deg,color-mix(in_srgb,var(--brand)_7%,transparent),transparent)] p-5">
      <p className="mb-3 font-display text-[15px] font-semibold text-ink">{t("ph.gsTitle")}</p>
      <ol className="grid gap-3 sm:grid-cols-3">
        {steps.map((s, i) => <li key={i} className="flex min-w-0 gap-3 rounded-[14px] bg-surface p-3 text-[13px] ring-1 ring-line"><span className="grid h-7 w-7 shrink-0 place-items-center rounded-full bg-ink text-[12px] font-bold text-surface">{i + 1}</span><span className="min-w-0 text-ink-2">{s}</span></li>)}
      </ol>
    </Panel>
  );
}

/** Label/value pairs: 1 column on phones, more on wider screens (instead of wide tables that scroll sideways). */
function Pairs({ items, cols = "sm:grid-cols-2 lg:grid-cols-4" }: { items: [string, React.ReactNode][]; cols?: string }) {
  return (
    <dl className={`grid gap-x-4 gap-y-2 text-sm ${cols}`}>
      {items.map(([k, v], i) => <div key={i} className="flex min-w-0 justify-between gap-2 sm:block"><dt className="shrink-0 text-[11.5px] text-muted">{k}</dt><dd className="min-w-0 truncate font-semibold text-ink tnum">{v}</dd></div>)}
    </dl>
  );
}

function Line({ k, v }: { k: string; v: string }) { return <div className="flex min-w-0 justify-between gap-3 border-b border-line py-2 text-[13.5px] last:border-0"><span className="min-w-0 truncate text-ink-2">{k}</span><b className="shrink-0 text-ink tnum">{v}</b></div>; }
const dash = <p className="py-2 text-sm text-faint">—</p>;

function Today({ m }: { m: M }) {
  const { t } = useI18n();
  const [day, setDay] = useState("");
  const s = useApi<Summary>(`store/pos/summary${day ? `?day=${day}` : ""}`);
  if (!s.data) return s.error ? <ErrorText error={s.error} /> : <Loading />;
  const d = s.data;
  const hours = d.byHour ?? [];
  const peak = Math.max(1, ...hours.map((h) => h.totalMinor));
  return (
    <Rise className="min-w-0 space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Input type="date" value={day || d.day} onChange={(e) => setDay(e.target.value)} className="max-w-48" aria-label={t("term.date")} />
        <Link href="/dashboard/products/labels" className="inline-flex h-10 items-center gap-1.5 rounded-[11px] px-3.5 text-[13px] font-semibold text-ink-2 ring-1 ring-line-strong hover:bg-surface"><Icon name="tag" className="h-4 w-4" />{t("pos.labels")}</Link>
      </div>
      <KpiStrip items={[
        { label: t("ph.sales"), value: d.totals.sales, icon: "bag", tone: "brand", hint: d.totals.offlineSales ? t("ph.offlineSales", { n: String(d.totals.offlineSales) }) : undefined },
        { label: t("ph.revenue"), value: m(d.totals.totalMinor), icon: "wallet", tone: "green" },
        { label: t("ph.discounts"), value: m(d.totals.discountMinor), icon: "tag", tone: "amber" },
        { label: t("ph.returns"), value: `${d.returns.returns} · ${m(d.returns.refundMinor)}`, icon: "return", tone: "red" },
      ]} />
      {hours.length > 0 && (
        <Block title={t("ph.byHour")} icon="chart">
          <div className="flex h-32 min-w-0 items-end gap-1" dir="ltr">
            {Array.from({ length: 24 }, (_, h) => hours.find((x) => x.hour === h)).map((x, h) => (
              <div key={h} className="group relative flex h-full min-w-0 flex-1 items-end" title={x ? `${h}:00 · ${x.sales} · ${m(x.totalMinor)}` : `${h}:00`}>
                <div className={`w-full rounded-t-[4px] transition-colors ${x ? "bg-brand/70 group-hover:bg-brand" : "bg-[color-mix(in_srgb,var(--ink)_7%,transparent)]"}`} style={{ height: `${x ? Math.max(4, (x.totalMinor / peak) * 100) : 2}%` }} />
              </div>
            ))}
          </div>
          <div className="mt-1.5 flex justify-between text-[10px] text-faint tnum" dir="ltr"><span>00</span><span>06</span><span>12</span><span>18</span><span>23</span></div>
        </Block>
      )}
      <div className="grid gap-5 lg:grid-cols-3">
        <Block title={t("ph.byMethod")}>{d.byMethod.map((x) => <Line key={x.method} k={t(`pay.${x.method}`)} v={m(x.amountMinor)} />)}{!d.byMethod.length && dash}</Block>
        <Block title={t("ph.byCashier")}>{d.byCashier.map((x) => <Line key={x.name} k={`${x.name} (${x.sales})`} v={m(x.totalMinor)} />)}{!d.byCashier.length && dash}</Block>
        <Block title={t("ph.byTerminal")}>{d.byDevice.map((x) => <Line key={x.name} k={`${x.name} (${x.sales})`} v={m(x.totalMinor)} />)}{!d.byDevice.length && dash}</Block>
      </div>
      <Block title={t("ph.topItems")} icon="star">{d.topItems.map((x, i) => <Line key={i} k={`${x.qty} × ${x.name}${x.label ? ` (${x.label})` : ""}`} v={m(x.totalMinor)} />)}{!d.topItems.length && dash}</Block>
    </Rise>
  );
}

function Shifts({ m }: { m: M }) {
  const { t, locale } = useI18n();
  const list = useApi<ShiftRow[]>("store/pos/shifts");
  const [open, setOpen] = useState<string | null>(null);
  const detail = useApi<Record<string, unknown>>(open ? `store/pos/shifts/${open}` : null);
  if (!list.data) return list.error ? <ErrorText error={list.error} /> : <Loading variant="cards" />;
  if (!list.data.length) return <Empty icon="clock" title={t("ph.noShifts")} />;
  const r = detail.data as null | { byMethod: { method: string; sales: number; amountMinor: number }[]; returns: { refundMethod: string; returns: number; refundMinor: number }[];
    cashMoves: { kind: string; amountMinor: number; reason: string; at: string; byName: string }[]; totals: { sales: number; totalMinor: number; discountMinor: number }; expectedNowMinor: number;
    topItems: { name: string; label: string | null; qty: number; totalMinor: number }[]; currency: string; openingFloatMinor: number; countedCashMinor: number | null; differenceMinor: number | null; status: string; note: string | null };
  return (
    <>
      <div className="grid gap-3 xl:grid-cols-2">
        {list.data.map((s) => (
          <button key={s.id} type="button" onClick={() => setOpen(s.id)} className="min-w-0 rounded-[18px] bg-surface p-4 text-start ring-1 ring-line transition hover:ring-line-strong hover:shadow-[var(--shadow-float)]">
            <div className="mb-3 flex min-w-0 items-center justify-between gap-2">
              <div className="min-w-0"><b className="block truncate text-ink">{s.cashierName}</b><span className="block truncate text-[12px] text-muted">{s.deviceName} · {when(s.openedAt, locale)}{s.closedAt ? ` → ${when(s.closedAt, locale)}` : ""}</span></div>
              {s.status === "OPEN" ? <Badge tone="green">{t("ph.open")}</Badge> : s.differenceMinor === null ? null : (
                <span className={`shrink-0 rounded-full px-2.5 py-0.5 text-xs font-bold ring-1 ring-inset ${s.differenceMinor === 0 ? "bg-emerald-50 text-emerald-700 ring-emerald-600/15" : s.differenceMinor > 0 ? "bg-sky-50 text-sky-700 ring-sky-600/15" : "bg-red-50 text-red-700 ring-red-600/15"}`}>
                  {s.differenceMinor === 0 ? t("term.balanced") : `${s.differenceMinor > 0 ? t("term.over") : t("term.short")} ${m(Math.abs(s.differenceMinor), s.currency)}`}
                </span>
              )}
            </div>
            <Pairs items={[[t("ph.sales"), `${s.sales} · ${m(s.salesMinor, s.currency)}`], [t("ph.float"), m(s.openingFloatMinor, s.currency)], [t("ph.expected"), s.expectedCashMinor === null ? "—" : m(s.expectedCashMinor, s.currency)], [t("ph.counted"), s.countedCashMinor === null ? "—" : m(s.countedCashMinor, s.currency)]]} />
          </button>
        ))}
      </div>
      <Modal open={!!open} onClose={() => setOpen(null)} title={t("ph.shiftReport")} wide>
        {!r ? <Loading /> : (
          <div className="space-y-4 text-sm">
            <KpiStrip items={[
              { label: t("ph.sales"), value: `${r.totals.sales}`, hint: m(r.totals.totalMinor, r.currency) },
              { label: t("ph.float"), value: m(r.openingFloatMinor, r.currency) },
              { label: t("ph.expected"), value: m(r.expectedNowMinor, r.currency) },
              { label: t("ph.counted"), value: r.countedCashMinor === null ? "—" : m(r.countedCashMinor, r.currency), hint: r.differenceMinor === null ? undefined : `${t("ph.difference")}: ${m(r.differenceMinor, r.currency)}` },
            ]} />
            <Block title={t("ph.byMethod")}>{r.byMethod.map((x) => <Line key={x.method} k={`${t(`pay.${x.method}`)} (${x.sales})`} v={m(x.amountMinor, r.currency)} />)}</Block>
            {r.returns.length > 0 && <Block title={t("ph.returns")}>{r.returns.map((x, i) => <Line key={i} k={`${t(`pay.${x.refundMethod ?? "CASH"}`)} (${x.returns})`} v={`-${m(x.refundMinor, r.currency)}`} />)}</Block>}
            {r.cashMoves.length > 0 && <Block title={t("ph.cashMoves")}>{r.cashMoves.map((x, i) => <Line key={i} k={`${t(`term.cash.${x.kind}`)} · ${x.reason ?? ""} · ${x.byName}`} v={m(x.amountMinor, r.currency)} />)}</Block>}
            <Block title={t("ph.topItems")}>{r.topItems.map((x, i) => <Line key={i} k={`${x.qty} × ${x.name}${x.label ? ` (${x.label})` : ""}`} v={m(x.totalMinor, r.currency)} />)}</Block>
            {r.note && <p className="rounded-[12px] bg-canvas px-3 py-2 text-ink-2">{r.note}</p>}
            <Button variant="secondary" onClick={() => window.print()}><Icon name="print" className="h-4 w-4" />{t("pos.print")}</Button>
          </div>
        )}
      </Modal>
    </>
  );
}

function Terminals({ list }: { list: ReturnType<typeof useApi<Device[]>> }) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const [edit, setEdit] = useState<Device | null>(null);
  const [drop, setDrop] = useState<Device | null>(null);
  const [openedAt] = useState(() => Date.now());   // "online now" means seen in the 3 minutes before this tab was opened
  const save = useAction(async (d: Device, name: string, size: string) => { await api(`store/pos/devices/${d.id}`, { method: "PATCH", body: { name, receiptSize: size } }); setEdit(null); toast(t("common.saved")); await list.reload(); });
  const revoke = useAction(async (d: Device) => { await api(`store/pos/devices/${d.id}/revoke`, { body: {} }); toast(t("ph.revoked")); setDrop(null); await list.reload(); });
  if (!list.data) return list.error ? <ErrorText error={list.error} /> : <Loading variant="cards" />;
  const live = (d: Device) => !!d.lastSeenAt && openedAt - new Date(d.lastSeenAt).getTime() < 3 * 60_000;
  return (
    <div className="min-w-0 space-y-4">
      <Alert tone="blue">{t("ph.registerHint")}</Alert>
      {!list.data.length ? <Empty icon="monitor" title={t("ph.noTerminals")} /> : (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {list.data.map((d) => (
            <Panel key={d.id} as="div" className={`min-w-0 space-y-4 p-4 ${d.revokedAt ? "opacity-60" : ""}`}>
              <div className="flex min-w-0 items-center gap-3">
                <span className="grid h-10 w-10 shrink-0 place-items-center rounded-[12px] bg-canvas text-ink-2 ring-1 ring-line"><Icon name="monitor" className="h-5 w-5" /></span>
                <div className="min-w-0 flex-1"><b className="block truncate text-ink">{d.name}</b><span className="text-[12px] text-muted" dir="ltr">{d.code}</span></div>
                {d.revokedAt ? <Badge tone="red">{t("ph.revoked")}</Badge> : <span className={`flex shrink-0 items-center gap-1.5 text-xs font-semibold ${live(d) ? "text-emerald-700" : "text-muted"}`}><span className={`h-2 w-2 rounded-full ${live(d) ? "bg-emerald-500" : "bg-line-strong"}`} />{live(d) ? t("ph.liveNow") : t("ph.notLive")}</span>}
                {!d.revokedAt && <RowMenu label={t("inbox.more")} items={[{ label: t("common.edit"), icon: "edit", onSelect: () => setEdit(d) }, "divider", { label: t("ph.revoke"), icon: "xcircle", danger: true, onSelect: () => setDrop(d) }]} />}
              </div>
              <Pairs cols="grid-cols-1 sm:grid-cols-2" items={[[t("ph.branch"), d.branchName], [t("ph.receipt"), d.receiptSize ? (d.receiptSize === "A4" ? "A4" : `${d.receiptSize}mm`) : t("term.shopDefault")], [t("ph.lastSeen"), when(d.lastSeenAt, locale)], [t("ph.issues"), d.openIssues > 0 ? <Badge tone="amber">{d.openIssues}</Badge> : "—"]]} />
            </Panel>
          ))}
        </div>
      )}
      <ErrorText error={revoke.error} />
      {edit && <EditTerminal d={edit} onClose={() => setEdit(null)} onSave={(n, s) => void save.run(edit, n, s)} busy={save.loading} error={save.error} />}
      <ConfirmDialog open={!!drop} onClose={() => setDrop(null)} danger title={t("ph.revoke")} body={t("ph.revokeConfirm")} confirmLabel={t("ph.revoke")} cancelLabel={t("common.cancel")} loading={revoke.loading} onConfirm={() => { if (drop) void revoke.run(drop); }} />
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
  const toast = useToast();
  const list = useApi<Member[]>("store/pos/team");
  const [who, setWho] = useState<Member | null>(null);
  const [pin, setPin] = useState("");
  const save = useAction(async () => { if (!who) return; await api(`store/pos/team/${who.userId}/pin`, { method: "PUT", body: { pin } }); setWho(null); setPin(""); toast(t("ph.pinSet")); await list.reload(); });
  const clear = useAction(async (mm: Member) => { await api(`store/pos/team/${mm.userId}/pin`, { method: "DELETE" }); toast(t("ph.clearPin")); await list.reload(); });
  if (!list.data) return list.error ? <ErrorText error={list.error} /> : <Loading variant="cards" />;
  return (
    <div className="min-w-0 space-y-4">
      <Alert tone="blue">{t("ph.pinHint")}</Alert>
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
        {list.data.map((x) => (
          <Panel key={x.userId} as="div" className="min-w-0 space-y-3 p-4">
            <div className="flex min-w-0 items-center gap-3">
              <span className="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-canvas text-[13px] font-bold text-ink-2 ring-1 ring-line">{x.name.trim().slice(0, 1).toUpperCase()}</span>
              <b className="min-w-0 flex-1 truncate text-ink">{x.name}</b>
              {x.hasPin ? <Badge tone="green">{t("ph.pinSet")}</Badge> : <Badge tone="amber">{t("ph.noPin")}</Badge>}
            </div>
            <div className="flex flex-wrap gap-1">{x.canManage && <Badge tone="blue">{t("ph.rManage")}</Badge>}{x.canRefund && <Badge>{t("ph.rRefund")}</Badge>}{x.canCash && <Badge>{t("ph.rCash")}</Badge>}</div>
            <div className="flex flex-wrap gap-2"><Button size="sm" variant="secondary" onClick={() => { setWho(x); setPin(""); }}><Icon name="lock" className="h-3.5 w-3.5" />{x.hasPin ? t("ph.changePin") : t("ph.setPin")}</Button>{x.hasPin && <Button size="sm" variant="ghost" loading={clear.loading} onClick={() => void clear.run(x)}>{t("ph.clearPin")}</Button>}</div>
          </Panel>
        ))}
      </div>
      <p className="text-xs text-muted">{t("ph.whoIsCashier")}</p>
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
  const toast = useToast();
  const resolve = useAction(async (ids: string[]) => { for (const id of ids) await api(`store/pos/issues/${id}/resolve`, { body: {} }); toast(t("ph.resolve")); await reload(); });
  const groups = useMemo(() => {
    const g = new Map<string, Issue[]>();
    for (const i of data ?? []) g.set(i.kind, [...(g.get(i.kind) ?? []), i]);
    return [...g.entries()];
  }, [data]);
  if (!data) return error ? <ErrorText error={error} /> : <Loading variant="list" />;
  if (!data.length) return <Empty icon="check" title={t("ph.noIssues")} />;
  return (
    <div className="min-w-0 space-y-5">
      <p className="text-sm text-muted">{t("ph.issuesHint")}</p>
      {groups.map(([kind, list]) => (
        <section key={kind} className="space-y-2">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex items-center gap-2"><Badge tone={kind === "NEGATIVE_STOCK" || kind === "FAILED" ? "red" : "amber"}>{t(`ph.kind.${kind}`)}</Badge><span className="text-sm text-muted tnum">{list.length}</span></div>
            <div className="flex gap-2">
              {kind === "NEGATIVE_STOCK" && <Link href="/dashboard/inventory" className="inline-flex h-8 items-center rounded-[9px] px-3 text-[13px] font-semibold text-ink-2 ring-1 ring-line-strong hover:bg-surface">{t("ph.openStock")}</Link>}
              <Button size="sm" variant="secondary" loading={resolve.loading} onClick={() => void resolve.run(list.map((x) => x.id))}>{t("ph.resolveAll")}</Button>
            </div>
          </div>
          <Panel as="div" className="divide-y divide-line">
            {list.map((i) => {
              let d: Record<string, unknown> = {};
              try { d = JSON.parse(i.detail); } catch { d = {}; }
              return (
                <div key={i.id} className="flex flex-wrap items-start justify-between gap-3 px-4 py-3">
                  <div className="min-w-0 flex-1 text-sm">
                    <p className="break-words text-ink">{issueText(i.kind, d, t)}</p>
                    <span className="text-xs text-muted">{i.deviceName} · {when(i.createdAt, locale)}</span>
                  </div>
                  <Button size="sm" variant="ghost" loading={resolve.loading} onClick={() => void resolve.run([i.id])}><Icon name="check" className="h-3.5 w-3.5" />{t("ph.resolve")}</Button>
                </div>
              );
            })}
          </Panel>
        </section>
      ))}
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

function Counts({ m }: { m: M }) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const { can } = useMe();
  const list = useApi<CountRow[]>("store/pos/counts");
  const [open, setOpen] = useState<string | null>(null);
  const [confirmApply, setConfirmApply] = useState(false);
  const detail = useApi<{ status: string; lines: { variantId: string; name: string; sku: string; comboKey: string | null; expectedAtCount: number; counted: number; difference: number; onHandNow: number; costPriceMinor: number | null }[] }>(open ? `store/pos/counts/${open}` : null);
  const apply = useAction(async () => { await api(`store/pos/counts/${open}/apply`, { body: {} }); toast(t("ph.applyCount")); setConfirmApply(false); await detail.reload(); await list.reload(); });
  const cancel = useAction(async () => { await api(`store/pos/counts/${open}/cancel`, { body: {} }); toast(t("ph.cancelCount")); setOpen(null); await list.reload(); });
  if (!list.data) return list.error ? <ErrorText error={list.error} /> : <Loading variant="cards" />;
  const value = detail.data ? detail.data.lines.reduce((n, l) => n + (l.costPriceMinor ?? 0) * l.difference, 0) : 0;
  return (
    <div className="min-w-0 space-y-4">
      <Alert tone="blue">{t("ph.countsHint")}</Alert>
      {!list.data.length ? <Empty icon="inventory" title={t("ph.noCounts")} /> : (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {list.data.map((c) => (
            <button key={c.id} type="button" onClick={() => setOpen(c.id)} className="min-w-0 space-y-3 rounded-[18px] bg-surface p-4 text-start ring-1 ring-line transition hover:ring-line-strong hover:shadow-[var(--shadow-float)]">
              <div className="flex min-w-0 items-center justify-between gap-2"><b className="min-w-0 truncate text-ink">{c.name}</b><Badge tone={c.status === "APPLIED" ? "green" : c.status === "CANCELLED" ? "slate" : "amber"}>{t(`ph.cs.${c.status}`)}</Badge></div>
              <Pairs cols="grid-cols-1 sm:grid-cols-2" items={[[t("ph.branch"), c.branchName], [t("ph.by"), c.createdByName], [t("term.lines"), c.lines], [t("term.differences"), c.differences], [t("term.date"), when(c.createdAt, locale)]]} />
            </button>
          ))}
        </div>
      )}
      <Modal open={!!open} onClose={() => setOpen(null)} title={t("ph.countTitle")} wide>
        {!detail.data ? <Loading /> : (
          <div className="space-y-3">
            <ul className="divide-y divide-line overflow-hidden rounded-[14px] ring-1 ring-line">
              {detail.data.lines.map((l) => (
                <li key={l.variantId} className="flex min-w-0 items-center justify-between gap-3 p-3 text-sm">
                  <div className="min-w-0"><b className="block truncate text-ink">{l.name}</b><span className="block truncate text-xs text-muted" dir="ltr">{l.sku}</span></div>
                  <div className="shrink-0 text-end">
                    <span className={`block font-bold tnum ${l.difference === 0 ? "text-muted" : l.difference > 0 ? "text-sky-700" : "text-red-600"}`}>{l.difference > 0 ? `+${l.difference}` : l.difference}</span>
                    <span className="block text-xs text-muted tnum">{l.counted} / {l.expectedAtCount} · {t("ph.onHandNow")} {l.onHandNow}</span>
                  </div>
                </li>
              ))}
            </ul>
            {value !== 0 && <p className={`text-sm font-semibold ${value < 0 ? "text-red-600" : "text-sky-700"}`}>{t("ph.countValue", { v: m(value) })}</p>}
            {["COUNTING", "REVIEW"].includes(detail.data.status) && (
              <div className="flex flex-wrap gap-2">
                <Button variant="secondary" loading={cancel.loading} onClick={() => void cancel.run()}>{t("ph.cancelCount")}</Button>
                {can("inventory.adjust") && <Button variant="ink" className="flex-1" loading={apply.loading} onClick={() => setConfirmApply(true)}>{t("ph.applyCount")}</Button>}
              </div>
            )}
            <p className="text-xs text-muted">{t("ph.applyHint")}</p>
            <ErrorText error={apply.error ?? cancel.error} />
          </div>
        )}
      </Modal>
      <ConfirmDialog open={confirmApply} onClose={() => setConfirmApply(false)} title={t("ph.applyCount")} body={t("ph.applyConfirm")} confirmLabel={t("ph.applyCount")} cancelLabel={t("common.cancel")} loading={apply.loading} onConfirm={() => void apply.run()} />
    </div>
  );
}

function Settings() {
  const { t } = useI18n();
  const toast = useToast();
  const s = useApi<{ receiptSize: string; receiptFooter: string; posMaxDiscountPct: number }>("store/pos/settings");
  const [form, setForm] = useState<{ receiptSize: string; receiptFooter: string; posMaxDiscountPct: string } | null>(null);
  const f = form ?? (s.data ? { receiptSize: s.data.receiptSize, receiptFooter: s.data.receiptFooter, posMaxDiscountPct: String(s.data.posMaxDiscountPct) } : null);
  const save = useAction(async () => { if (!f) return; await api("store/pos/settings", { method: "PUT", body: { receiptSize: f.receiptSize, receiptFooter: f.receiptFooter, posMaxDiscountPct: Number(f.posMaxDiscountPct) } }); setForm(null); toast(t("common.saved")); await s.reload(); });
  if (!f) return s.error ? <ErrorText error={s.error} /> : <Loading variant="detail" />;
  return (
    <Block title={t("ph.settings")} icon="settings" className="max-w-xl">
      <div className="space-y-4">
        <Field label={t("term.receiptSize")} hint={t("ph.sizeHint")}><Select value={f.receiptSize} onChange={(e) => setForm({ ...f, receiptSize: e.target.value })}><option value="58">58mm</option><option value="80">80mm</option><option value="A4">A4</option></Select></Field>
        <Field label={t("ph.footer")} hint={t("ph.footerHint")}><Input value={f.receiptFooter} onChange={(e) => setForm({ ...f, receiptFooter: e.target.value })} maxLength={300} /></Field>
        <Field label={t("ph.maxDiscount")} hint={t("ph.maxDiscountHint")}><Input dir="ltr" inputMode="numeric" value={f.posMaxDiscountPct} onChange={(e) => setForm({ ...f, posMaxDiscountPct: e.target.value.replace(/\D/g, "") })} /></Field>
        <ErrorText error={save.error} />
        <Button variant="ink" loading={save.loading} disabled={!form} onClick={() => void save.run()}>{t("common.save")}</Button>
      </div>
    </Block>
  );
}
