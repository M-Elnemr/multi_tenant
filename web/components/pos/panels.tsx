"use client";

import { useEffect, useMemo, useState } from "react";
import { money } from "@/lib/format";
import { denominations, toMinor } from "@/lib/pos/money";
import { cashierByPin } from "@/lib/pos/pin";
import { matches, words } from "@/lib/pos/search";
import type { CatalogItem, Cashier, Count, Held, Invoice, Op, Shift } from "@/lib/pos/db";
import { useI18n } from "../i18n-provider";
import { Alert, Button, Field, Input, Select } from "../ui";

// ---- PIN ---------------------------------------------------------------------------------------------------------------------

/** A number pad for a cashier's PIN (also a manager's, to approve something); checked on the terminal, with no internet. */
export function PinPad({ cashiers, onOk, title, need, onRefresh }: { cashiers: Cashier[]; onOk: (c: Cashier) => void; title: string; need?: (c: Cashier) => boolean; onRefresh?: () => Promise<unknown> }) {
  const { t } = useI18n();
  const [pin, setPin] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const noPins = !cashiers.some((c) => c.hasPin);
  const check = async (p: string) => {
    setBusy(true);
    const c = await cashierByPin(p, cashiers);
    setBusy(false);
    if (!c) { setErr(t("term.pinWrong")); setPin(""); void onRefresh?.(); return; }   // a PIN set a minute ago on the dashboard arrives with the next refresh
    if (need && !need(c)) { setErr(t("term.pinNoRight")); setPin(""); return; }
    onOk(c);
  };
  const press = (d: string) => {
    setErr(null);
    if (d === "⌫") { setPin(pin.slice(0, -1)); return; }
    if (d === "✓") { if (pin.length >= 4) void check(pin); return; }
    if (pin.length < 6) setPin(pin + d);
  };
  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (/^\d$/.test(e.key)) press(e.key); else if (e.key === "Backspace") press("⌫"); else if (e.key === "Enter") press("✓"); };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  });
  return (
    <div className="mx-auto w-full max-w-xs space-y-4 text-center">
      <h2 className="text-lg font-bold">{title}</h2>
      {noPins ? <Alert tone="amber">{t("term.noPins")}</Alert> : (
        <>
          <div className="flex justify-center gap-3" dir="ltr">{[0, 1, 2, 3, 4, 5].map((i) => <span key={i} className={`h-4 w-4 rounded-full ${i < pin.length ? "bg-brand" : i < 4 ? "bg-slate-300" : "bg-slate-100"}`} />)}</div>
          {err && <p className="text-sm font-semibold text-red-600">{err}</p>}
          <div className="grid grid-cols-3 gap-2" dir="ltr">
            {["1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✓"].map((d) => (
              <button key={d} type="button" disabled={busy} onClick={() => press(d)} className={`h-16 rounded-2xl text-2xl font-bold shadow-sm active:scale-95 ${d === "✓" ? "bg-brand text-white" : "border border-slate-200 bg-white"}`}>{d}</button>
            ))}
          </div>
        </>
      )}
      {onRefresh && <button type="button" className="text-xs font-semibold text-brand" onClick={async () => { setBusy(true); await onRefresh(); setBusy(false); setErr(null); }}>{t("term.refreshCashiers")}</button>}
    </div>
  );
}

// ---- registering the terminal ----------------------------------------------------------------------------------------------

export function RegisterScreen({ onRegister, pending, revoked }: { onRegister: (name: string) => Promise<void>; pending: number; revoked: boolean }) {
  const { t } = useI18n();
  const [name, setName] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  return (
    <div className="mx-auto max-w-md space-y-4 rounded-3xl bg-white p-6 shadow-card">
      <div className="text-center text-4xl">🖥️</div>
      <h1 className="text-center text-xl font-bold">{revoked ? t("term.revokedTitle") : t("term.registerTitle")}</h1>
      <p className="text-sm text-slate-600">{revoked ? t("term.revokedHint") : t("term.registerHint")}</p>
      {pending > 0 && <Alert tone="amber">{t("term.pendingKept", { n: String(pending) })}</Alert>}
      <Field label={t("term.terminalName")}><Input value={name} onChange={(e) => setName(e.target.value)} placeholder={t("term.terminalNamePh")} /></Field>
      {err && <Alert>{err}</Alert>}
      <Button className="w-full" loading={busy} onClick={async () => {
        setBusy(true); setErr(null);
        try { await onRegister(name); } catch (e) { setErr((e as Error).message === "offline" ? t("term.needOnline") : t("term.registerFailed")); }
        setBusy(false);
      }}>{t("term.registerBtn")}</Button>
      <p className="text-center text-xs text-slate-500">{t("term.registerManager")} <a className="font-semibold text-brand" href="/dashboard/login?next=/pos">{t("term.signIn")}</a></p>
    </div>
  );
}

// ---- shifts and the drawer ---------------------------------------------------------------------------------------------------

export function OpenShift({ currency, suggested, onOpen, cashierName }: { currency: string; suggested: number; cashierName: string; onOpen: (floatMinor: number) => void }) {
  const { t, locale } = useI18n();
  const [v, setV] = useState(suggested ? (suggested / 100).toString() : "");
  return (
    <div className="space-y-4">
      <p className="text-sm text-slate-600">{t("term.openShiftHint", { name: cashierName })}</p>
      <Field label={t("term.openingFloat")} hint={suggested ? t("term.floatFromHandover", { v: money(suggested, currency, locale) }) : undefined}>
        <Input autoFocus dir="ltr" inputMode="decimal" value={v} onChange={(e) => setV(e.target.value)} className="text-xl font-bold" placeholder="0.00" />
      </Field>
      <Button className="w-full py-3" onClick={() => onOpen(toMinor(v))}>{t("term.openShiftBtn")}</Button>
    </div>
  );
}

export function CashMovePanel({ currency, onSave }: { currency: string; onSave: (kind: "PAY_IN" | "PAY_OUT" | "DROP", amountMinor: number, reason: string) => void }) {
  const { t, locale } = useI18n();
  const [kind, setKind] = useState<"PAY_IN" | "PAY_OUT" | "DROP">("PAY_OUT");
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");
  const a = toMinor(amount);
  return (
    <div className="space-y-4">
      <div className="grid grid-cols-3 gap-2">
        {(["PAY_IN", "PAY_OUT", "DROP"] as const).map((k) => (
          <button key={k} type="button" onClick={() => setKind(k)} className={`rounded-xl border px-2 py-3 text-sm font-bold ${kind === k ? "border-brand bg-brand text-white" : "border-slate-200 bg-white"}`}>{t(`term.cash.${k}`)}</button>
        ))}
      </div>
      <p className="text-xs text-slate-500">{t(`term.cashHint.${kind}`)}</p>
      <Field label={t("term.amount")}><Input autoFocus dir="ltr" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} className="text-xl font-bold" /></Field>
      <Field label={t("term.reason")}><Input value={reason} onChange={(e) => setReason(e.target.value)} placeholder={t("term.reasonPh")} /></Field>
      <Button className="w-full" disabled={a <= 0 || !reason.trim()} onClick={() => onSave(kind, a, reason.trim())}>{t("common.save")} · {money(a, currency, locale)}</Button>
    </div>
  );
}

export type DrawerSummary = { floatMinor: number; cashSalesMinor: number; cashRefundsMinor: number; payInMinor: number; payOutMinor: number; dropMinor: number; expectedMinor: number;
  sales: number; salesMinor: number; byMethod: Record<string, number> };

/** The drawer at the end of a shift: count the cash (by note and coin, or one amount), see over/short, then hand it to the next cashier or to the safe. */
export function CloseShift({ shift, summary, currency, cashiers, onClose, onPrintX }: {
  shift: Shift; summary: DrawerSummary; currency: string; cashiers: Cashier[];
  onClose: (countedMinor: number, denoms: Record<string, number>, note: string, handover: { kind: "NEXT_CASHIER" | "SAFE"; to: Cashier | null }) => void; onPrintX: () => void;
}) {
  const { t, locale } = useI18n();
  const m = (v: number) => money(v, currency, locale);
  const [byNote, setByNote] = useState<Record<string, string>>({});
  const [total, setTotal] = useState("");
  const [note, setNote] = useState("");
  const [kind, setKind] = useState<"NEXT_CASHIER" | "SAFE">("NEXT_CASHIER");
  const [next, setNext] = useState<Cashier | null>(null);
  const [askPin, setAskPin] = useState(false);
  const fromNotes = useMemo(() => Object.entries(byNote).reduce((n, [d, c]) => n + Number(d) * (parseInt(c || "0", 10) || 0), 0), [byNote]);
  const counted = total.trim() ? toMinor(total) : fromNotes;
  const diff = counted - summary.expectedMinor;
  if (askPin) return <PinPad cashiers={cashiers.filter((c) => c.userId !== shift.cashierId)} title={t("term.nextCashierPin")} onOk={(c) => { setNext(c); setAskPin(false); }} />;
  return (
    <div className="space-y-4">
      <div className="grid gap-2 text-sm sm:grid-cols-2">
        <Line k={t("term.openingFloat")} v={m(summary.floatMinor)} />
        <Line k={t("term.cashSales")} v={m(summary.cashSalesMinor)} />
        <Line k={t("term.cashRefunds")} v={`-${m(summary.cashRefundsMinor)}`} />
        <Line k={t("term.cash.PAY_IN")} v={m(summary.payInMinor)} />
        <Line k={t("term.cash.PAY_OUT")} v={`-${m(summary.payOutMinor)}`} />
        <Line k={t("term.cash.DROP")} v={`-${m(summary.dropMinor)}`} />
      </div>
      <div className="flex justify-between rounded-xl bg-slate-900 p-3 text-white"><span>{t("term.expectedCash")}</span><b className="tabular-nums">{m(summary.expectedMinor)}</b></div>
      <div>
        <p className="mb-2 text-sm font-semibold">{t("term.countByNote")}</p>
        <div className="grid grid-cols-3 gap-2 sm:grid-cols-5">
          {denominations(currency).map((d) => (
            <label key={d} className="min-w-0 rounded-xl border border-slate-200 p-2 text-center text-xs">
              <span className="block truncate font-bold">{m(d)}</span>
              <input dir="ltr" inputMode="numeric" className="mt-1 w-full min-w-0 rounded-lg border border-slate-200 px-1 py-1 text-center text-sm" value={byNote[d] ?? ""} onChange={(e) => setByNote({ ...byNote, [d]: e.target.value.replace(/\D/g, "") })} />
              {(parseInt(byNote[d] || "0", 10) || 0) > 0 && <span className="mt-0.5 block truncate text-[11px] text-slate-500">= {m(d * (parseInt(byNote[d] || "0", 10) || 0))}</span>}
            </label>
          ))}
        </div>
      </div>
      <Field label={t("term.orTotal")}><Input dir="ltr" inputMode="decimal" value={total} onChange={(e) => setTotal(e.target.value)} placeholder={(fromNotes / 100).toFixed(2)} /></Field>
      <div className={`flex justify-between rounded-xl p-3 font-bold ${diff === 0 ? "bg-emerald-50 text-emerald-800" : diff > 0 ? "bg-sky-50 text-sky-800" : "bg-red-50 text-red-700"}`}>
        <span>{diff === 0 ? t("term.balanced") : diff > 0 ? t("term.over") : t("term.short")}</span><span className="tabular-nums">{m(Math.abs(diff))}</span>
      </div>
      <div className="grid grid-cols-2 gap-2">
        <button type="button" onClick={() => setKind("NEXT_CASHIER")} className={`rounded-xl border px-2 py-3 text-sm font-bold ${kind === "NEXT_CASHIER" ? "border-brand bg-brand-soft text-brand-dark" : "border-slate-200"}`}>{t("term.toNextCashier")}</button>
        <button type="button" onClick={() => { setKind("SAFE"); setNext(null); }} className={`rounded-xl border px-2 py-3 text-sm font-bold ${kind === "SAFE" ? "border-brand bg-brand-soft text-brand-dark" : "border-slate-200"}`}>{t("term.toSafe")}</button>
      </div>
      {kind === "NEXT_CASHIER" && (next
        ? <p className="text-sm">{t("term.handTo")}: <b>{next.name}</b> <button type="button" className="text-brand" onClick={() => setNext(null)}>({t("term.change2")})</button></p>
        : <Button variant="secondary" className="w-full" onClick={() => setAskPin(true)}>{t("term.nextCashierSign")}</Button>)}
      <Field label={t("term.note")}><Input value={note} onChange={(e) => setNote(e.target.value)} /></Field>
      <div className="sticky bottom-0 -mx-1 flex gap-2 bg-white px-1 pt-2">
        <Button variant="secondary" onClick={onPrintX}>{t("term.printX")}</Button>
        <Button className="flex-1" disabled={kind === "NEXT_CASHIER" && !next} onClick={() => onClose(counted, Object.fromEntries(Object.entries(byNote).filter(([, c]) => c).map(([k, c]) => [k, parseInt(c, 10) || 0])), note, { kind, to: next })}>{t("term.closeShiftBtn")}</Button>
      </div>
    </div>
  );
}

function Line({ k, v }: { k: string; v: string }) { return <div className="flex min-w-0 justify-between gap-2 rounded-lg bg-slate-50 px-3 py-2"><span className="min-w-0 truncate text-slate-600">{k}</span><b className="shrink-0 tabular-nums">{v}</b></div>; }

// ---- invoices and returns ----------------------------------------------------------------------------------------------------

export function InvoicesPanel({ invoices, currency, onReprint, onReturn, findRemote, canRefund }: {
  invoices: Invoice[]; currency: string; canRefund: boolean;
  onReprint: (inv: Invoice) => void; onReturn: (inv: Invoice) => void; findRemote: (q: string) => Promise<Invoice | null>;
}) {
  const { t, locale } = useI18n();
  const [q, setQ] = useState("");
  const [open, setOpen] = useState<Invoice | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const list = useMemo(() => {
    const ws = words(q);
    return invoices.filter((i) => matches(ws, i.number, i.customerName, i.phone)).sort((a, b) => b.at.localeCompare(a.at)).slice(0, 80);
  }, [invoices, q]);
  if (open) {
    const left = open.lines.reduce((n, l) => n + l.qty - l.returned, 0);
    return (
      <div className="space-y-3">
        <button type="button" className="text-sm font-semibold text-brand" onClick={() => setOpen(null)}>← {t("term.back")}</button>
        <div className="flex items-center justify-between"><b dir="ltr">{open.number}</b><span className="text-xs text-slate-500">{new Date(open.at).toLocaleString(locale === "ar" ? "ar-EG" : "en-GB")}</span></div>
        <ul className="divide-y rounded-xl border text-sm">
          {open.lines.map((l, i) => <li key={i} className="flex justify-between p-2"><span>{l.qty} × {l.name}{l.label ? ` (${l.label})` : ""}{l.returned > 0 && <span className="ms-2 text-xs text-amber-700">↩ {l.returned}</span>}</span><span className="tabular-nums">{money(l.qty * l.unitMinor - (l.discountMinor ?? 0), open.currency, locale)}</span></li>)}
        </ul>
        <div className="flex justify-between text-lg font-bold"><span>{t("term.total")}</span><span className="tabular-nums">{money(open.totalMinor, open.currency, locale)}</span></div>
        <p className="text-xs text-slate-500">{open.payments.map((p) => `${t(`pay.${p.method}`)} ${money(p.amountMinor, open.currency, locale)}`).join(" · ")} · {open.synced ? t("term.synced") : t("term.notSynced")}</p>
        <div className="flex gap-2">
          <Button variant="secondary" className="flex-1" onClick={() => onReprint(open)}>{t("term.reprint")}</Button>
          {canRefund && left > 0 && <Button className="flex-1" onClick={() => onReturn(open)}>{t("term.returnItems")}</Button>}
        </div>
      </div>
    );
  }
  return (
    <div className="space-y-3">
      <div className="flex min-w-0 gap-2">
        <Input autoFocus value={q} onChange={(e) => { setQ(e.target.value); setMsg(null); }} placeholder={t("term.invoiceSearch")} className="min-w-0" />
        <Button variant="secondary" className="shrink-0" onClick={async () => {
          if (!q.trim()) return;
          setMsg(null);
          try { const r = await findRemote(q.trim()); if (r) setOpen(r); else setMsg(t("term.notFound")); } catch (e) { setMsg((e as Error).message === "offline" ? t("term.needOnlineSearch") : t("term.notFound")); }
        }}>{t("term.searchServer")}</Button>
      </div>
      {msg && <Alert tone="amber">{msg}</Alert>}
      <ul className="max-h-[60vh] divide-y overflow-y-auto rounded-xl border">
        {list.length === 0 && <li className="p-6 text-center text-sm text-slate-500">{t("term.noInvoices")}</li>}
        {list.map((i) => (
          <li key={i.id}><button type="button" onClick={() => setOpen(i)} className="flex w-full min-w-0 items-center justify-between gap-2 p-3 text-start text-sm hover:bg-slate-50">
            <span className="min-w-0 truncate"><b dir="ltr">{i.number}</b><span className="ms-2 text-xs text-slate-500">{new Date(i.at).toLocaleTimeString(locale === "ar" ? "ar-EG" : "en-GB", { hour: "2-digit", minute: "2-digit" })}</span>{!i.synced && <span className="ms-2 text-xs text-amber-600">● {t("term.notSynced")}</span>}</span>
            <span className="font-bold tabular-nums">{money(i.totalMinor, i.currency ?? currency, locale)}</span>
          </button></li>
        ))}
      </ul>
    </div>
  );
}

/** A return: the pieces that came back (up to what is left of each line) and how the money goes back. */
export function ReturnPanel({ invoice, methods, onDone }: { invoice: Invoice; methods: string[]; onDone: (lines: { variantId: string; name: string; qty: number; unitMinor: number }[], method: string, reason: string) => void }) {
  const { t, locale } = useI18n();
  const [qty, setQty] = useState<Record<number, number>>({});
  const [method, setMethod] = useState("CASH");
  const [reason, setReason] = useState("");
  const lines = invoice.lines.map((l, i) => ({ ...l, i, left: Math.max(0, l.qty - l.returned), unit: l.qty > 0 ? Math.round((l.qty * l.unitMinor - (l.discountMinor ?? 0)) / l.qty) : l.unitMinor }));
  const chosen = lines.filter((l) => (qty[l.i] ?? 0) > 0);
  // the refund is what was paid for those pieces: each line already carries its share of every discount (as the server computes it)
  const refund = chosen.reduce((n, l) => n + l.unit * (qty[l.i] ?? 0), 0);
  return (
    <div className="space-y-3">
      <p className="text-sm"><b dir="ltr">{invoice.number}</b></p>
      <ul className="divide-y rounded-xl border">
        {lines.map((l) => (
          <li key={l.i} className="flex items-center gap-2 p-2 text-sm">
            <span className="min-w-0 flex-1">{l.name}{l.label ? ` (${l.label})` : ""}<span className="block text-xs text-slate-500">{t("term.canReturn", { n: String(l.left) })} · {money(l.unit, invoice.currency, locale)}</span></span>
            <div className="flex items-center rounded-full border" dir="ltr">
              <button type="button" className="h-9 w-9" onClick={() => setQty({ ...qty, [l.i]: Math.max(0, (qty[l.i] ?? 0) - 1) })}>−</button>
              <span className="w-7 text-center font-bold">{qty[l.i] ?? 0}</span>
              <button type="button" className="h-9 w-9" disabled={(qty[l.i] ?? 0) >= l.left} onClick={() => setQty({ ...qty, [l.i]: Math.min(l.left, (qty[l.i] ?? 0) + 1) })}>+</button>
            </div>
          </li>
        ))}
      </ul>
      <Field label={t("term.refundBy")}><Select value={method} onChange={(e) => setMethod(e.target.value)}>{methods.map((x) => <option key={x} value={x}>{t(`pay.${x}`)}</option>)}</Select></Field>
      <Field label={t("term.reason")}><Input value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
      <div className="flex justify-between text-lg font-bold"><span>{t("term.refund")}</span><span className="tabular-nums">{money(refund, invoice.currency, locale)}</span></div>
      <Button className="w-full" disabled={chosen.length === 0} onClick={() => onDone(chosen.map((l) => ({ variantId: l.variantId, name: l.name, qty: qty[l.i], unitMinor: l.unit })), method, reason.trim())}>{t("term.confirmReturn")}</Button>
    </div>
  );
}

// ---- held carts --------------------------------------------------------------------------------------------------------------

export function HeldPanel({ held, currency, onRecall, onDrop }: { held: Held[]; currency: string; onRecall: (h: Held) => void; onDrop: (h: Held) => void }) {
  const { t, locale } = useI18n();
  if (held.length === 0) return <p className="p-6 text-center text-sm text-slate-500">{t("term.noHeld")}</p>;
  return (
    <ul className="divide-y rounded-xl border">
      {held.sort((a, b) => b.at.localeCompare(a.at)).map((h) => (
        <li key={h.id} className="flex items-center gap-2 p-3 text-sm">
          <div className="min-w-0 flex-1"><b>{h.note || h.lines.map((l) => l.name).slice(0, 2).join("، ")}</b><span className="block text-xs text-slate-500">{h.lines.reduce((n, l) => n + l.qty, 0)} {t("term.pieces")} · {money(h.lines.reduce((n, l) => n + l.qty * l.unitMinor - l.discountMinor, 0) - h.cartDiscountMinor, currency, locale)} · {h.cashierName}</span></div>
          <Button size="sm" onClick={() => onRecall(h)}>{t("term.recall")}</Button>
          <button type="button" className="px-2 text-slate-400 hover:text-red-600" onClick={() => onDrop(h)}>✕</button>
        </li>
      ))}
    </ul>
  );
}

// ---- stock count -------------------------------------------------------------------------------------------------------------

/** Counting the shelf: scan or search a product, type how many are there; the difference with what the terminal expected is shown. Works offline; the manager applies it. */
export function CountPanel({ count, catalog, onStart, onSet, onSend, find }: {
  count: Count | null; catalog: CatalogItem[]; onStart: (name: string) => void; onSet: (item: CatalogItem, counted: number) => void; onSend: () => void; find: (code: string) => Promise<CatalogItem | undefined>;
}) {
  const { t } = useI18n();
  const [name, setName] = useState("");
  const [q, setQ] = useState("");
  const [pick, setPick] = useState<CatalogItem | null>(null);
  const [n, setN] = useState("");
  if (!count) {
    return (
      <div className="space-y-3">
        <p className="text-sm text-slate-600">{t("term.countHint")}</p>
        <Field label={t("term.countName")}><Input value={name} onChange={(e) => setName(e.target.value)} placeholder={t("term.countNamePh")} /></Field>
        <Button className="w-full" onClick={() => onStart(name.trim() || t("term.countNamePh"))}>{t("term.startCount")}</Button>
      </div>
    );
  }
  const ws = words(q);
  const results = ws.length ? catalog.filter((c) => c.active && matches(ws, c.name, c.label, c.sku, c.barcode)).slice(0, 8) : [];
  const lines = Object.entries(count.lines);
  const diffs = lines.filter(([, l]) => l.counted !== l.expected).length;
  const nameOf = (id: string) => { const c = catalog.find((x) => x.variantId === id); return c ? `${c.name}${c.label ? ` (${c.label})` : ""}` : id.slice(0, 8); };
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between"><b>{count.name}</b><span className="text-xs text-slate-500">{lines.length} {t("term.lines")} · {diffs} {t("term.differences")}</span></div>
      {count.status === "REVIEW" ? <Alert tone="green">{t("term.countSent")}</Alert> : (
        <>
          <div className="flex gap-2">
            <Input autoFocus value={q} onChange={(e) => setQ(e.target.value)} placeholder={t("term.countSearch")} onKeyDown={async (e) => { if (e.key === "Enter") { const f = await find(q); if (f) { setPick(f); setQ(""); } } }} />
          </div>
          {results.length > 0 && !pick && <ul className="divide-y rounded-xl border">{results.map((r) => <li key={r.variantId}><button type="button" className="w-full p-2 text-start text-sm hover:bg-slate-50" onClick={() => { setPick(r); setQ(""); }}>{r.name}{r.label ? ` (${r.label})` : ""} <span className="text-xs text-slate-500">· {t("term.expected")} {r.stock}</span></button></li>)}</ul>}
          {pick && (
            <div className="space-y-2 rounded-xl border border-brand/40 bg-brand-soft p-3">
              <p className="text-sm font-semibold">{pick.name}{pick.label ? ` (${pick.label})` : ""} · {t("term.expected")} {count.lines[pick.variantId]?.expected ?? pick.stock}</p>
              <div className="flex gap-2">
                <Input autoFocus dir="ltr" inputMode="numeric" value={n} onChange={(e) => setN(e.target.value.replace(/\D/g, ""))} onKeyDown={(e) => { if (e.key === "Enter" && n !== "") { onSet(pick, parseInt(n, 10)); setPick(null); setN(""); } }} placeholder={t("term.counted")} />
                <Button disabled={n === ""} onClick={() => { onSet(pick, parseInt(n, 10)); setPick(null); setN(""); }}>{t("common.save")}</Button>
              </div>
            </div>
          )}
        </>
      )}
      <ul className="max-h-[40vh] divide-y overflow-y-auto rounded-xl border text-sm">
        {lines.map(([id, l]) => (
          <li key={id} className="flex justify-between p-2"><span>{nameOf(id)}</span><span className={l.counted === l.expected ? "text-slate-500" : l.counted > l.expected ? "font-bold text-sky-700" : "font-bold text-red-600"}>{l.counted} / {l.expected}</span></li>
        ))}
      </ul>
      {count.status !== "REVIEW" && <Button className="w-full" disabled={lines.length === 0} onClick={onSend}>{t("term.sendCount")}</Button>}
    </div>
  );
}

// ---- sync and settings -------------------------------------------------------------------------------------------------------

export function SyncPanel({ pending, failed, online, lastSync, onSync, onDismiss, syncing }: {
  pending: number; failed: Op[]; online: boolean; lastSync: string | null; syncing: boolean; onSync: () => void; onDismiss: (seq: number) => void;
}) {
  const { t, locale } = useI18n();
  return (
    <div className="space-y-3 text-sm">
      <div className={`rounded-xl p-3 font-semibold ${online ? "bg-emerald-50 text-emerald-800" : "bg-amber-50 text-amber-900"}`}>{online ? t("term.onlineNow") : t("term.offlineNow")}</div>
      <p>{t("term.pendingCount", { n: String(pending) })}</p>
      <p className="text-slate-500">{t("term.lastSync")}: {lastSync ? new Date(lastSync).toLocaleString(locale === "ar" ? "ar-EG" : "en-GB") : "—"}</p>
      <Button className="w-full" loading={syncing} onClick={onSync}>{t("term.syncNow")}</Button>
      {failed.length > 0 && (
        <div className="space-y-2">
          <Alert tone="amber">{t("term.failedHint")}</Alert>
          <ul className="divide-y rounded-xl border">
            {failed.map((f) => <li key={f.seq} className="flex items-center justify-between gap-2 p-2"><span>{t(`term.op.${f.type}`)} · <span className="text-xs text-slate-500">{f.error}</span></span><button type="button" className="text-xs text-slate-500 underline" onClick={() => onDismiss(f.seq)}>{t("term.dismiss")}</button></li>)}
          </ul>
        </div>
      )}
    </div>
  );
}

export function SettingsPanel({ size, shopSize, autoPrint, sound, onSize, onAutoPrint, onSound, deviceName, branchName, onForget, canForget }: {
  size: "58" | "80" | "A4" | null; shopSize: string; autoPrint: boolean; sound: boolean; deviceName: string; branchName: string; canForget: boolean;
  onSize: (s: "58" | "80" | "A4" | null) => void; onAutoPrint: (v: boolean) => void; onSound: (v: boolean) => void; onForget: () => void;
}) {
  const { t } = useI18n();
  return (
    <div className="space-y-4 text-sm">
      <p className="text-slate-600">{deviceName} · {branchName}</p>
      <Field label={t("term.receiptSize")} hint={t("term.receiptSizeHint", { s: shopSize === "A4" ? "A4" : `${shopSize}mm` })}>
        <Select value={size ?? ""} onChange={(e) => onSize((e.target.value || null) as "58" | "80" | "A4" | null)}>
          <option value="">{t("term.shopDefault")}</option><option value="58">58mm</option><option value="80">80mm</option><option value="A4">A4</option>
        </Select>
      </Field>
      <label className="flex items-center gap-2"><input type="checkbox" checked={autoPrint} onChange={(e) => onAutoPrint(e.target.checked)} /> {t("term.autoPrint")}</label>
      <label className="flex items-center gap-2"><input type="checkbox" checked={sound} onChange={(e) => onSound(e.target.checked)} /> {t("term.sound")}</label>
      <div className="border-t pt-3">
        <Button variant="danger" disabled={!canForget} onClick={onForget}>{t("term.forget")}</Button>
        <p className="mt-1 text-xs text-slate-500">{canForget ? t("term.forgetHint") : t("term.forgetBlocked")}</p>
      </div>
    </div>
  );
}

// ---- the menu, variants, categories, X report -------------------------------------------------------------------------

export type MenuItem = { key: string; label: string; icon: string; onClick: () => void; badge?: string | number; tone?: "danger" };

/** Everything that is not selling, as big buttons (no crowded top bar, nothing to scroll sideways). */
export function MenuSheet({ items }: { items: MenuItem[] }) {
  return (
    <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
      {items.map((i) => (
        <button key={i.key} type="button" onClick={i.onClick} className={`relative flex min-h-20 min-w-0 flex-col items-center justify-center gap-1 rounded-2xl border p-3 text-center text-sm font-semibold active:scale-[0.98] ${i.tone === "danger" ? "border-red-200 bg-red-50 text-red-700" : "border-slate-200 bg-white hover:border-brand"}`}>
          <span className="text-2xl leading-none">{i.icon}</span><span className="line-clamp-2">{i.label}</span>
          {i.badge ? <span className="absolute end-2 top-2 rounded-full bg-amber-500 px-1.5 text-[11px] font-bold text-white">{i.badge}</span> : null}
        </button>
      ))}
    </div>
  );
}

/** «Size: M, Colour: Black» → { Size: "M", Colour: "Black" }; null when the label is not option pairs. */
function options(label: string | null): Record<string, string> | null {
  if (!label) return null;
  const out: Record<string, string> = {};
  for (const part of label.split(/,\s*/)) {
    const i = part.indexOf(":");
    if (i <= 0) return null;
    out[part.slice(0, i).trim()] = part.slice(i + 1).trim();
  }
  return Object.keys(out).length ? out : null;
}

/** A product that comes in sizes or colours: pick each option as chips, see that one's price and stock, add it (a quantity too). */
export function VariantPicker({ variants, currency, onAdd }: { variants: CatalogItem[]; currency: string; onAdd: (v: CatalogItem, qty: number) => void }) {
  const { t, locale } = useI18n();
  const parsed = variants.map((v) => ({ v, o: options(v.label) }));
  const structured = parsed.every((p) => p.o);
  const names = structured ? [...new Set(parsed.flatMap((p) => Object.keys(p.o!)))] : [];
  const [pick, setPick] = useState<Record<string, string>>({});
  const [qty, setQty] = useState(1);
  const chosen = structured ? parsed.find((p) => names.every((n) => pick[n] && p.o![n] === pick[n]))?.v ?? null : null;
  const valuesOf = (n: string) => [...new Set(parsed.map((p) => p.o![n]).filter(Boolean))];
  // a value is shown faded when no variant with the other picks so far has it in stock
  const stockFor = (n: string, val: string) => parsed.filter((p) => p.o![n] === val && names.every((k) => k === n || !pick[k] || p.o![k] === pick[k])).reduce((s, p) => s + Math.max(0, p.v.stock), 0);
  if (!structured) {
    return (
      <ul className="divide-y rounded-xl border">
        {variants.map((v) => (
          <li key={v.variantId}><button type="button" onClick={() => onAdd(v, 1)} className="flex w-full min-w-0 items-center justify-between gap-2 p-3 text-start text-sm hover:bg-slate-50">
            <span className="min-w-0 truncate">{v.label ?? v.sku}</span><span className="shrink-0"><b className="tabular-nums">{money(v.priceMinor, currency, locale)}</b> <span className={v.stock > 0 ? "text-xs text-slate-500" : "text-xs font-bold text-red-600"}>· {v.stock}</span></span>
          </button></li>
        ))}
      </ul>
    );
  }
  return (
    <div className="space-y-4">
      {names.map((n) => (
        <div key={n}>
          <p className="mb-1.5 text-sm font-semibold text-slate-700">{n}</p>
          <div className="flex flex-wrap gap-2">
            {valuesOf(n).map((val) => {
              const on = pick[n] === val, st = stockFor(n, val);
              return <button key={val} type="button" onClick={() => setPick({ ...pick, [n]: val })} className={`min-w-12 rounded-xl border px-3 py-2 text-sm font-semibold ${on ? "border-brand bg-brand text-white" : st > 0 ? "border-slate-200 bg-white" : "border-dashed border-slate-300 bg-slate-50 text-slate-400"}`}>{val}</button>;
            })}
          </div>
        </div>
      ))}
      <div className="flex items-center justify-between rounded-xl bg-slate-50 p-3 text-sm">
        {chosen ? <><span>{t("term.stockNow")} <b className={chosen.stock > 0 ? "" : "text-red-600"}>{chosen.stock}</b></span><b className="text-lg tabular-nums">{money(chosen.priceMinor, currency, locale)}</b></> : <span className="text-slate-500">{t("term.pickOptions")}</span>}
      </div>
      <div className="flex gap-2">
        <div className="flex items-center rounded-xl border" dir="ltr">
          <button type="button" className="h-11 w-11 text-lg" onClick={() => setQty(Math.max(1, qty - 1))}>−</button>
          <span className="w-8 text-center font-bold">{qty}</span>
          <button type="button" className="h-11 w-11 text-lg" onClick={() => setQty(qty + 1)}>+</button>
        </div>
        <Button className="flex-1" disabled={!chosen} onClick={() => chosen && onAdd(chosen, qty)}>{t("term.addToCart")}</Button>
      </div>
    </div>
  );
}

export function CategorySheet({ cats, current, onPick }: { cats: string[]; current: string; onPick: (c: string) => void }) {
  return (
    <div className="flex flex-wrap gap-2">
      {cats.map((c) => <button key={c} type="button" onClick={() => onPick(c)} className={`rounded-full px-3.5 py-2 text-sm font-semibold ${current === c ? "bg-brand text-white" : "bg-white ring-1 ring-slate-200"}`}>{c}</button>)}
    </div>
  );
}

/** The X report on screen first (printing is one tap away). */
export function ReportView({ rows, onPrint }: { rows: [string, string][]; onPrint: () => void }) {
  const { t } = useI18n();
  return (
    <div className="space-y-3">
      <div className="space-y-1 text-sm">
        {rows.map(([k, v], i) => k === "—" ? <hr key={i} className="my-2 border-dashed" /> : <div key={i} className="flex min-w-0 justify-between gap-3"><span className="min-w-0 truncate text-slate-600">{k}</span><b className="shrink-0 tabular-nums">{v}</b></div>)}
      </div>
      <Button className="w-full" onClick={onPrint}>{t("term.print")}</Button>
    </div>
  );
}
