"use client";

import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { money } from "@/lib/format";
import { all, byCode, del, kvGet, kvSet, put, req, uuid, wipe, type CartLine, type CatalogItem, type Cashier, type Count, type DeviceInfo, type Held, type Invoice, type Op, type Payment, type Shift, type Shop } from "@/lib/pos/db";
import { allocate, toMinor, totals } from "@/lib/pos/money";
import * as sync from "@/lib/pos/sync";
import { useI18n } from "../i18n-provider";
import { Alert, Button, Input, Modal } from "../ui";
import { useWedgeScanner } from "../dashboard/pos-picker";
import { PayPanel } from "./pay";
import { CashMovePanel, CloseShift, CountPanel, HeldPanel, InvoicesPanel, OpenShift, PinPad, RegisterScreen, ReturnPanel, SettingsPanel, SyncPanel, type DrawerSummary } from "./panels";
import { Receipt, type PrintDoc } from "./receipt";

type Data = {
  device: DeviceInfo | undefined; shop: Shop | null; cashiers: Cashier[]; methods: string[]; catalog: CatalogItem[];
  shift: Shift | null; held: Held[]; invoices: Invoice[]; count: Count | null; failed: Op[];
  local: { size: "58" | "80" | "A4" | null; autoPrint: boolean }; handover: { to: string; amountMinor: number } | null;
};
type Panel = null | "pay" | "invoices" | "held" | "cash" | "close" | "count" | "sync" | "settings" | "switch" | "approve" | { return: Invoice } | { edit: number };
type Done = { invoice: Invoice };

const IDLE_LOCK_MS = 15 * 60_000;

/** The till terminal: sells from its own copy of the catalog (scan, search or tap), with or without internet, and keeps the drawer, returns, held carts and stock counts. */
export default function Terminal() {
  const { t, locale } = useI18n();
  const status = useSyncExternalStore(sync.subscribe, sync.getStatus, sync.getStatus);
  const [d, setD] = useState<Data | null>(null);
  const [cashier, setCashier] = useState<Cashier | null>(null);
  const [panel, setPanel] = useState<Panel>(null);
  const [lines, setLines] = useState<CartLine[]>([]);
  const [cartDisc, setCartDisc] = useState("");
  const [cartPct, setCartPct] = useState(false);
  const [customer, setCustomer] = useState({ name: "", phone: "" });
  const [heldId, setHeldId] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [cat, setCat] = useState<string>("");
  const [msg, setMsg] = useState<{ tone: "red" | "amber" | "green"; text: string } | null>(null);
  const [done, setDone] = useState<Done | null>(null);
  const [print, setPrint] = useState<PrintDoc | null>(null);
  const [busy, setBusy] = useState(false);
  const [approvedBy, setApprovedBy] = useState<Cashier | null>(null);
  const search = useRef<HTMLInputElement>(null);
  const lastAct = useRef(0);

  const load = useCallback(async () => {
    const [device, shop, cashiers, methods, catalog, shift, held, invoices, counts, ops, local, handover] = await Promise.all([
      sync.device(), sync.shop(), sync.cashiers(), sync.methods(), sync.catalog(), kvGet<Shift>("shift"), all<Held>("held"), all<Invoice>("invoices"), all<Count>("counts"),
      all<Op>("outbox"), kvGet<Data["local"]>("local"), kvGet<Data["handover"]>("handover"),
    ]);
    setD({ device, shop: shop ?? null, cashiers, methods, catalog, shift: shift ?? null, held, invoices, count: counts.find((c) => c.status === "COUNTING") ?? counts[0] ?? null,
      failed: ops.filter((o) => o.state === "failed"), local: local ?? { size: null, autoPrint: true }, handover: handover ?? null });
  }, []);

  useEffect(() => {
    lastAct.current = Date.now();
    void sync.start();
    sync.keepForOffline();
    void Promise.resolve().then(load);
    return sync.onChange(() => void load());
  }, [load]);

  // the till locks itself after a quiet while: the next person signs in with their PIN
  useEffect(() => {
    const touch = () => { lastAct.current = Date.now(); };
    window.addEventListener("pointerdown", touch);
    window.addEventListener("keydown", touch);
    const id = setInterval(() => { if (cashier && !done && lines.length === 0 && Date.now() - lastAct.current > IDLE_LOCK_MS) setCashier(null); }, 30_000);
    return () => { window.removeEventListener("pointerdown", touch); window.removeEventListener("keydown", touch); clearInterval(id); };
  }, [cashier, done, lines.length]);

  const shop = d?.shop ?? null;
  const currency = shop?.currency ?? "EGP";
  const m = useCallback((v: number) => money(v, currency, locale), [currency, locale]);
  const receiptSize = d?.local.size ?? d?.device?.receiptSize ?? shop?.receiptSize ?? "80";

  // ---- the cart ---------------------------------------------------------------------------------------------------------

  const cartDiscMinor = useMemo(() => {
    const sub = lines.reduce((n, l) => n + l.unitMinor * l.qty - l.discountMinor, 0);
    if (!cartDisc.trim()) return 0;
    if (cartPct) { const p = Math.min(100, Math.max(0, Number(cartDisc.replace(",", ".")) || 0)); return Math.round((sub * p) / 100); }
    return Math.min(sub, toMinor(cartDisc));
  }, [cartDisc, cartPct, lines]);
  const tot = useMemo(() => totals(lines, cartDiscMinor, shop), [lines, cartDiscMinor, shop]);
  const overLimit = shop ? tot.discountMinor > Math.round((tot.subtotalMinor * (shop.posMaxDiscountPct ?? 0)) / 100) : false;

  const add = useCallback((it: CatalogItem) => {
    setMsg(it.stock <= 0 ? { tone: "amber", text: t("term.outOfStockSold", { name: it.name }) } : null);
    setLines((cur) => {
      const i = cur.findIndex((l) => l.variantId === it.variantId && !l.note && l.discountMinor === 0);
      if (i >= 0) return cur.map((l, k) => (k === i ? { ...l, qty: l.qty + 1 } : l));
      return [...cur, { variantId: it.variantId, name: it.name, label: it.label, sku: it.sku, unitMinor: it.priceMinor, qty: 1, discountMinor: 0, note: null, stock: it.stock }];
    });
  }, [t]);

  const scan = useCallback(async (code: string) => {
    if (!cashier || panel === "pay") return;
    if (done) setDone(null);   // scanning on the "sale complete" screen starts the next sale
    const it = await byCode(code);
    if (it) { add(it); setQ(""); } else setMsg({ tone: "red", text: t("term.noCode", { code }) });
  }, [add, cashier, panel, done, t]);
  useWedgeScanner((c) => void scan(c), !!cashier && !!d?.shift && panel === null);

  const results = useMemo(() => {
    if (!d) return [];
    const s = q.trim().toLowerCase();
    return d.catalog.filter((c) => c.active && (!cat || c.categoryAr === cat || c.categoryEn === cat)
      && (!s || c.name.toLowerCase().includes(s) || c.sku.toLowerCase().includes(s) || (c.barcode ?? "").includes(s) || (c.label ?? "").toLowerCase().includes(s))).slice(0, 60);
  }, [d, q, cat]);
  const cats = useMemo(() => {
    const set = new Map<string, number>();
    for (const c of d?.catalog ?? []) if (c.active) { const k = locale === "ar" ? c.categoryAr || c.categoryEn : c.categoryEn || c.categoryAr; if (k) set.set(k, (set.get(k) ?? 0) + 1); }
    return [...set.entries()].sort((a, b) => b[1] - a[1]).slice(0, 14).map(([k]) => k);
  }, [d, locale]);

  const clearCart = () => { setLines([]); setCartDisc(""); setCartPct(false); setCustomer({ name: "", phone: "" }); setHeldId(null); setApprovedBy(null); setMsg(null); };

  // ---- the drawer -------------------------------------------------------------------------------------------------------

  const drawer: DrawerSummary | null = useMemo(() => {
    if (!d?.shift) return null;
    const mine = d.invoices.filter((i) => i.shiftId === d.shift!.id);
    const byMethod: Record<string, number> = {};
    for (const i of mine) for (const p of i.payments) byMethod[p.method] = (byMethod[p.method] ?? 0) + p.amountMinor;
    const cashSalesMinor = byMethod.CASH ?? 0;
    const cashRefundsMinor = d.shift.returns.filter((r) => r.method === "CASH").reduce((n, r) => n + r.refundMinor, 0);
    const sum = (k: string) => d.shift!.moves.filter((x) => x.kind === k).reduce((n, x) => n + x.amountMinor, 0);
    const payInMinor = sum("PAY_IN"), payOutMinor = sum("PAY_OUT"), dropMinor = sum("DROP");
    return { floatMinor: d.shift.openingFloatMinor, cashSalesMinor, cashRefundsMinor, payInMinor, payOutMinor, dropMinor,
      expectedMinor: d.shift.openingFloatMinor + cashSalesMinor - cashRefundsMinor + payInMinor - payOutMinor - dropMinor,
      sales: mine.length, salesMinor: mine.reduce((n, i) => n + i.totalMinor, 0), byMethod };
  }, [d]);

  const report = (title: string): PrintDoc => {
    const x = drawer!;
    const rows: [string, string][] = [
      [t("term.cashier"), d?.shift?.cashierName ?? ""], [t("term.openedAt"), new Date(d!.shift!.openedAt).toLocaleString(locale === "ar" ? "ar-EG" : "en-GB")], ["—", ""],
      [t("term.sales"), `${x.sales} · ${m(x.salesMinor)}`], ...Object.entries(x.byMethod).map(([k, v]) => [t(`pay.${k}`), m(v)] as [string, string]), ["—", ""],
      [t("term.openingFloat"), m(x.floatMinor)], [t("term.cashSales"), m(x.cashSalesMinor)], [t("term.cashRefunds"), `-${m(x.cashRefundsMinor)}`],
      [t("term.cash.PAY_IN"), m(x.payInMinor)], [t("term.cash.PAY_OUT"), `-${m(x.payOutMinor)}`], [t("term.cash.DROP"), `-${m(x.dropMinor)}`], ["—", ""],
      [t("term.expectedCash"), m(x.expectedMinor)],
    ];
    return { kind: "report", title, rows, at: new Date().toISOString() };
  };

  // ---- actions ----------------------------------------------------------------------------------------------------------

  const openShift = async (floatMinor: number) => {
    if (!cashier) return;
    const id = uuid();
    await sync.enqueue("SHIFT_OPEN", cashier.userId, { shiftId: id, openingFloatMinor: floatMinor }, (s) => {
      s.kv.put({ id, cashierId: cashier.userId, cashierName: cashier.name, openedAt: new Date().toISOString(), openingFloatMinor: floatMinor, moves: [], returns: [] } satisfies Shift, "shift");
      s.kv.delete("handover");
    });
  };

  const startPay = () => {
    if (!lines.length) return;
    if (overLimit && !cashier?.canManage && !approvedBy) { setPanel("approve"); return; }
    setPanel("pay");
  };

  const pay = async (payments: Payment[], tenderedMinor: number, changeMinor: number) => {
    if (!cashier || !d?.shift) return;
    setBusy(true);
    try {
      const orderId = uuid();
      const at = new Date().toISOString();
      const shared = allocate(lines, cartDiscMinor);
      let invoice: Invoice | null = null;
      const shift = d.shift;
      await sync.enqueue("SALE", cashier.userId, {
        orderId, shiftId: shift.id, offline: !navigator.onLine, discountMinor: cartDiscMinor, payments, tenderedMinor, changeMinor,
        customerName: customer.name.trim() || null, phone: customer.phone.trim() || null, approvedBy: approvedBy?.userId ?? null,
        items: lines.map((l) => ({ variantId: l.variantId, qty: l.qty, unitMinor: l.unitMinor, discountMinor: l.discountMinor, note: l.note, name: l.name, label: l.label, sku: l.sku })),
      }, async (s) => {
        const number = await sync.nextInvoiceNumber(s);
        invoice = {
          id: orderId, number, at, cashierId: cashier.userId, cashierName: cashier.name, shiftId: shift.id, currency,
          lines: shared.map((l) => ({ variantId: l.variantId, name: l.name, label: l.label, sku: l.sku, qty: l.qty, unitMinor: l.unitMinor, discountMinor: l.discountMinor, returned: 0, note: l.note })),
          subtotalMinor: tot.subtotalMinor, discountMinor: tot.discountMinor, totalMinor: tot.totalMinor, vatMinor: tot.vatMinor, payments, tenderedMinor, changeMinor,
          customerName: customer.name.trim() || undefined, phone: customer.phone.trim() || undefined, synced: false, source: "local",
        };
        s.invoices.put(invoice);
        for (const l of lines) {
          const it = (await req(s.catalog.get(l.variantId))) as CatalogItem | undefined;
          if (it) s.catalog.put({ ...it, stock: it.stock - l.qty });
        }
        if (heldId) s.held.delete(heldId);
        return { number };
      });
      clearCart();
      setPanel(null);
      if (invoice) {
        setDone({ invoice });
        if (d.local.autoPrint) setPrint({ kind: "sale", invoice });
      }
    } catch (e) {
      setMsg({ tone: "red", text: `${t("term.saveFailed")} ${(e as Error).message}` });
    } finally {
      setBusy(false);
    }
  };

  const hold = async () => {
    if (!lines.length || !cashier) return;
    const h: Held = { id: heldId ?? uuid(), at: new Date().toISOString(), lines, cartDiscountMinor: cartDiscMinor, note: customer.name, cashierName: cashier.name };
    await put("held", h);
    clearCart();
    sync.changed();
    setMsg({ tone: "green", text: t("term.held") });
  };

  const recall = async (h: Held) => {
    if (lines.length) await hold();
    setLines(h.lines); setCartDisc(h.cartDiscountMinor ? (h.cartDiscountMinor / 100).toString() : ""); setCartPct(false); setCustomer({ name: h.note, phone: "" }); setHeldId(h.id); setPanel(null);
  };

  const giveBack = async (inv: Invoice, back: { variantId: string; name: string; qty: number; unitMinor: number }[], method: string, reason: string) => {
    if (!cashier || !d?.shift) return;
    const refundMinor = back.reduce((n, l) => n + l.qty * l.unitMinor, 0);
    const id = uuid();
    const shift = d.shift;
    await sync.enqueue("RETURN", cashier.userId, { orderId: inv.id, number: inv.number, shiftId: shift.id, refundMethod: method, reason: reason || null, items: back.map((l) => ({ variantId: l.variantId, qty: l.qty })) }, async (s) => {
      const cur = ((await req(s.invoices.get(inv.id))) as Invoice | undefined) ?? inv;
      const left = new Map(back.map((b) => [b.variantId, b.qty]));
      s.invoices.put({ ...cur, lines: cur.lines.map((l) => { const n = Math.min(left.get(l.variantId) ?? 0, l.qty - l.returned); if (n > 0) left.set(l.variantId, (left.get(l.variantId) ?? 0) - n); return { ...l, returned: l.returned + n }; }) });
      for (const b of back) { const it = (await req(s.catalog.get(b.variantId))) as CatalogItem | undefined; if (it) s.catalog.put({ ...it, stock: it.stock + b.qty }); }
      const sh = (await req(s.kv.get("shift"))) as Shift | undefined;
      if (sh) s.kv.put({ ...sh, returns: [...sh.returns, { id, invoiceId: inv.id, number: inv.number, at: new Date().toISOString(), refundMinor, method, lines: back }] }, "shift");
    });
    setPanel(null);
    setPrint({ kind: "return", invoice: { ...inv, cashierName: cashier.name }, lines: back, refundMinor, method, at: new Date().toISOString() });
    setMsg({ tone: "green", text: t("term.returned", { v: m(refundMinor) }) });
  };

  const cashMove = async (kind: "PAY_IN" | "PAY_OUT" | "DROP", amountMinor: number, reason: string) => {
    if (!cashier || !d?.shift) return;
    const id = uuid();
    await sync.enqueue("CASH_MOVE", cashier.userId, { moveId: id, shiftId: d.shift.id, kind, amountMinor, reason }, async (s) => {
      const sh = (await req(s.kv.get("shift"))) as Shift | undefined;
      if (sh) s.kv.put({ ...sh, moves: [...sh.moves, { id, kind, amountMinor, reason, at: new Date().toISOString() }] }, "shift");
    });
    setPanel(null);
    setMsg({ tone: "green", text: t("term.cashSaved") });
  };

  const closeShift = async (countedMinor: number, denoms: Record<string, number>, note: string, handover: { kind: "NEXT_CASHIER" | "SAFE"; to: Cashier | null }) => {
    if (!cashier || !d?.shift || !drawer) return;
    const z = report(t("term.zReport"));
    if (z.kind === "report") z.rows.push([t("term.counted"), m(countedMinor)], [t("term.difference"), m(countedMinor - drawer.expectedMinor)], [t("term.handTo"), handover.kind === "SAFE" ? t("term.toSafe") : handover.to?.name ?? ""]);
    await sync.enqueue("SHIFT_CLOSE", cashier.userId, { shiftId: d.shift.id, countedMinor, expectedMinor: drawer.expectedMinor, denominations: denoms, note: note || null, handoverKind: handover.kind, handedTo: handover.to?.userId ?? null }, (s) => {
      s.kv.delete("shift");
      if (handover.kind === "NEXT_CASHIER" && handover.to) s.kv.put({ to: handover.to.userId, amountMinor: countedMinor }, "handover");
    });
    setPanel(null);
    setPrint(z);
    setCashier(handover.kind === "NEXT_CASHIER" ? handover.to : null);
  };

  const startCount = async (name: string) => {
    const c: Count = { id: uuid(), name, status: "COUNTING", createdAt: new Date().toISOString(), lines: {}, sent: false };
    await put("counts", c);
    sync.changed();
  };
  const setCount = async (item: CatalogItem, counted: number) => {
    if (!d?.count) return;
    const prev = d.count.lines[item.variantId];
    const c: Count = { ...d.count, lines: { ...d.count.lines, [item.variantId]: { expected: prev?.expected ?? item.stock, counted, at: new Date().toISOString() } } };
    await put("counts", c);
    sync.changed();
  };
  const sendCount = async () => {
    if (!d?.count || !cashier) return;
    const c = d.count;
    await sync.enqueue("COUNT", cashier.userId, { countId: c.id, name: c.name, status: "REVIEW", lines: Object.entries(c.lines).map(([variantId, l]) => ({ variantId, expected: l.expected, counted: l.counted, at: l.at })) }, (s) => {
      s.counts.put({ ...c, status: "REVIEW", sent: true });
    });
  };

  // keyboard: F2 search, F4 pay, F8 hold, F9 invoices
  useEffect(() => {
    const h = (e: KeyboardEvent) => {
      if (!cashier || done) return;
      if (e.key === "F2") { e.preventDefault(); search.current?.focus(); }
      if (e.key === "F4" && lines.length && panel === null) { e.preventDefault(); startPay(); }
      if (e.key === "F8" && lines.length && panel === null) { e.preventDefault(); void hold(); }
      if (e.key === "F9" && panel === null) { e.preventDefault(); setPanel("invoices"); }
    };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  });

  const findRemote = async (qq: string): Promise<Invoice | null> => {
    const local = d?.invoices.find((i) => i.number === qq || i.number.endsWith(qq));
    if (local) return local;
    const r = await sync.findRemote(qq) as { id: string; orderNumber: string; createdAt: string; currency: string; totalMinor: number; status: string; items: { variantId: string; name: string; variantName: string | null; sku: string; quantity: number; returned: number; unitMinor: number }[] };
    if (!r?.id) return null;
    return { id: r.id, number: r.orderNumber, at: r.createdAt, cashierId: null, currency: r.currency, status: r.status, totalMinor: r.totalMinor, subtotalMinor: r.totalMinor, discountMinor: 0, vatMinor: 0,
      lines: r.items.filter((i) => i.variantId).map((i) => ({ variantId: i.variantId, name: i.name, label: i.variantName, sku: i.sku, qty: i.quantity, unitMinor: i.unitMinor, returned: i.returned })), payments: [], synced: true, source: "server" };
  };

  // ---- screens ----------------------------------------------------------------------------------------------------------

  const onPrinted = useCallback(() => setPrint(null), []);
  const receipt = <Receipt doc={print} shop={shop} size={receiptSize} deviceName={d?.device?.name ?? ""} onPrinted={onPrinted} />;

  if (!d) return <Center><span className="animate-pulse text-slate-500">…</span></Center>;
  if (status.registered === false || (!d.device && status.ready)) {
    return <Center><RegisterScreen pending={status.pending + status.failed} revoked={!!d.device} onRegister={async (name) => { if (d.device) await wipe(false); await sync.register(name); await load(); }} /></Center>;
  }
  if (!d.device) return <Center><span className="animate-pulse text-slate-500">{t("term.loadingCopy")}</span></Center>;

  const top = (
    <header className="flex flex-wrap items-center gap-2 border-b border-slate-200 bg-white px-3 py-2 print:hidden">
      <b className="text-sm">{shop?.name}</b>
      <span className="rounded-lg bg-slate-100 px-2 py-0.5 text-xs" dir="ltr">{d.device.code}</span>
      <button type="button" onClick={() => setPanel("sync")} className={`flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold ${status.online ? "bg-emerald-50 text-emerald-700" : "bg-amber-100 text-amber-900"}`}>
        <span className={`h-2 w-2 rounded-full ${status.online ? "bg-emerald-500" : "bg-amber-500"} ${status.syncing ? "animate-pulse" : ""}`} />
        {status.online ? t("term.online") : t("term.offline")}{status.pending > 0 ? ` · ${t("term.waiting", { n: String(status.pending) })}` : ""}{status.failed > 0 ? ` · ⚠ ${status.failed}` : ""}
      </button>
      <div className="ms-auto flex flex-wrap items-center gap-1.5">
        {cashier && <span className="text-sm">👤 {cashier.name}</span>}
        {cashier && <TopBtn onClick={() => setPanel("switch")}>{t("term.switch")}</TopBtn>}
        {cashier && d.shift && <>
          <TopBtn onClick={() => setPanel("invoices")}>{t("term.invoices")} <kbd>F9</kbd></TopBtn>
          <TopBtn onClick={() => setPanel("held")}>{t("term.heldBtn")}{d.held.length ? ` (${d.held.length})` : ""}</TopBtn>
          {cashier.canCash && <TopBtn onClick={() => setPanel("cash")}>{t("term.cashBtn")}</TopBtn>}
          {cashier.canCount && <TopBtn onClick={() => setPanel("count")}>{t("term.countBtn")}</TopBtn>}
          <TopBtn onClick={() => setPrint(report(t("term.xReport")))}>{t("term.xReport")}</TopBtn>
          <TopBtn onClick={() => setPanel("close")}>{t("term.closeShift")}</TopBtn>
        </>}
        <TopBtn onClick={() => setPanel("settings")}>⚙</TopBtn>
        {cashier && <TopBtn onClick={() => { if (!lines.length) setCashier(null); }}>🔒</TopBtn>}
      </div>
    </header>
  );

  const modals = (
    <>
      <Modal open={panel === "switch"} onClose={() => setPanel(null)} title={t("term.switch")}><PinPad cashiers={d.cashiers} title={t("term.enterPin")} onOk={(c) => { setCashier(c); setPanel(null); }} /></Modal>
      <Modal open={panel === "approve"} onClose={() => setPanel(null)} title={t("term.managerApprove")}>
        <p className="mb-3 text-sm text-slate-600">{t("term.discountOverLimit", { p: String(shop?.posMaxDiscountPct ?? 0) })}</p>
        <PinPad cashiers={d.cashiers} title={t("term.managerPin")} need={(c) => c.canManage} onOk={(c) => { setApprovedBy(c); setPanel("pay"); }} />
      </Modal>
      <Modal open={panel === "pay"} onClose={() => !busy && setPanel(null)} title={t("term.payTitle")}>
        <PayPanel totalMinor={tot.totalMinor} currency={currency} methods={d.methods} busy={busy} onCancel={() => setPanel(null)} onPay={(p, ten, ch) => void pay(p, ten, ch)} />
      </Modal>
      <Modal open={panel === "invoices"} onClose={() => setPanel(null)} title={t("term.invoices")} wide>
        <InvoicesPanel invoices={d.invoices} currency={currency} canRefund={!!cashier?.canRefund} findRemote={findRemote}
          onReprint={(inv) => setPrint({ kind: "sale", invoice: inv, copy: true })} onReturn={(inv) => setPanel({ return: inv })} />
      </Modal>
      <Modal open={typeof panel === "object" && panel !== null && "return" in panel} onClose={() => setPanel(null)} title={t("term.returnItems")}>
        {typeof panel === "object" && panel !== null && "return" in panel && <ReturnPanel invoice={panel.return} methods={d.methods} onDone={(back, method, reason) => void giveBack(panel.return, back, method, reason)} />}
      </Modal>
      <Modal open={panel === "held"} onClose={() => setPanel(null)} title={t("term.heldBtn")}>
        <HeldPanel held={d.held} currency={currency} onRecall={(h) => void recall(h)} onDrop={async (h) => { await del("held", h.id); sync.changed(); }} />
      </Modal>
      <Modal open={panel === "cash"} onClose={() => setPanel(null)} title={t("term.cashBtn")}><CashMovePanel currency={currency} onSave={(k, a, r) => void cashMove(k, a, r)} /></Modal>
      <Modal open={panel === "close"} onClose={() => setPanel(null)} title={t("term.closeShift")} wide>
        {d.shift && drawer && <CloseShift shift={d.shift} summary={drawer} currency={currency} cashiers={d.cashiers} onPrintX={() => setPrint(report(t("term.xReport")))} onClose={(c, den, n, h) => void closeShift(c, den, n, h)} />}
      </Modal>
      <Modal open={panel === "count"} onClose={() => setPanel(null)} title={t("term.countBtn")} wide>
        <CountPanel count={d.count && d.count.status === "COUNTING" ? d.count : null} catalog={d.catalog} find={byCode} onStart={(n) => void startCount(n)} onSet={(i, c) => void setCount(i, c)} onSend={() => void sendCount()} />
      </Modal>
      <Modal open={panel === "sync"} onClose={() => setPanel(null)} title={t("term.syncTitle")}>
        <SyncPanel pending={status.pending} failed={d.failed} online={status.online} lastSync={status.lastSync} syncing={status.syncing} onSync={() => { void sync.refresh().then(() => sync.push()); }} onDismiss={(s) => void sync.dismissFailed(s)} />
      </Modal>
      <Modal open={panel === "settings"} onClose={() => setPanel(null)} title={t("term.settings")}>
        <SettingsPanel size={d.local.size} shopSize={d.device?.receiptSize ?? shop?.receiptSize ?? "80"} autoPrint={d.local.autoPrint} deviceName={d.device.name} branchName={d.device.branchName}
          canForget={status.pending === 0 && !d.shift}
          onSize={async (s) => { await kvSet("local", { ...d.local, size: s }); sync.changed(); }} onAutoPrint={async (v) => { await kvSet("local", { ...d.local, autoPrint: v }); sync.changed(); }}
          onForget={async () => { await sync.forget(); await wipe(true); location.reload(); }} />
      </Modal>
    </>
  );

  if (!cashier) {
    return (
      <div className="flex min-h-dvh flex-col bg-slate-50">{top}
        <Center><div className="w-full max-w-sm rounded-3xl bg-white p-6 shadow-card"><PinPad cashiers={d.cashiers} title={t("term.enterPin")} onRefresh={() => sync.refresh(true)} onOk={(c) => { setCashier(c); lastAct.current = Date.now(); }} /></div></Center>
        {modals}{receipt}
      </div>
    );
  }

  if (!d.shift) {
    const suggested = d.handover && d.handover.to === cashier.userId ? d.handover.amountMinor : 0;
    return (
      <div className="flex min-h-dvh flex-col bg-slate-50">{top}
        <Center><div className="w-full max-w-sm rounded-3xl bg-white p-6 shadow-card"><h2 className="mb-3 text-lg font-bold">{t("term.openShift")}</h2><OpenShift currency={currency} suggested={suggested} cashierName={cashier.name} onOpen={(f) => void openShift(f)} /></div></Center>
        {modals}{receipt}
      </div>
    );
  }

  if (done) {
    const inv = done.invoice;
    return (
      <div className="flex min-h-dvh flex-col bg-slate-50">{top}
        <Center>
          <div className="w-full max-w-md space-y-4 rounded-3xl bg-white p-6 text-center shadow-card print:hidden">
            <div className="text-5xl">✅</div>
            <h2 className="text-xl font-bold">{t("term.saleDone")}</h2>
            <p className="text-sm text-slate-500" dir="ltr">{inv.number}</p>
            <p className="text-3xl font-extrabold tabular-nums">{m(inv.totalMinor)}</p>
            {(inv.changeMinor ?? 0) > 0 && <div className="rounded-2xl bg-emerald-50 p-4 text-emerald-800"><div className="text-sm">{t("term.change")}</div><div className="text-4xl font-extrabold tabular-nums">{m(inv.changeMinor ?? 0)}</div></div>}
            {!status.online && <p className="text-xs text-amber-700">{t("term.savedOffline")}</p>}
            <div className="flex gap-2">
              <Button variant="secondary" className="flex-1" onClick={() => setPrint({ kind: "sale", invoice: inv })}>{t("term.print")}</Button>
              <Button autoFocus className="flex-[2] py-3" onClick={() => { setDone(null); setTimeout(() => search.current?.focus(), 30); }}>{t("term.newSale")}</Button>
            </div>
          </div>
        </Center>
        {modals}{receipt}
      </div>
    );
  }

  return (
    <div className="flex h-dvh flex-col bg-slate-50">
      {top}
      <div className="grid min-h-0 flex-1 gap-3 p-3 lg:grid-cols-[1fr_420px] print:hidden">
        {/* products */}
        <section className="flex min-h-0 flex-col gap-2">
          <div className="flex gap-2">
            <input ref={search} value={q} onChange={(e) => setQ(e.target.value)} placeholder={t("term.searchPh")} className="w-full rounded-xl border border-slate-200 bg-white px-3.5 py-2.5 text-base outline-none focus:border-brand focus:ring-2 focus:ring-brand/20"
              onKeyDown={async (e) => {
                if (e.key !== "Enter" || !q.trim()) return;
                const it = await byCode(q);
                if (it) { add(it); setQ(""); } else if (results.length === 1) { add(results[0]); setQ(""); } else if (!results.length) setMsg({ tone: "red", text: t("term.noCode", { code: q }) });
              }} />
          </div>
          {cats.length > 1 && (
            <div className="flex gap-1.5 overflow-x-auto pb-1">
              <Chip on={!cat} onClick={() => setCat("")}>{t("term.all")}</Chip>
              {cats.map((c) => <Chip key={c} on={cat === c} onClick={() => setCat(c)}>{c}</Chip>)}
            </div>
          )}
          {msg && <Alert tone={msg.tone}>{msg.text}</Alert>}
          <div className="grid min-h-0 flex-1 auto-rows-min grid-cols-2 gap-2 overflow-y-auto sm:grid-cols-3 xl:grid-cols-4">
            {results.map((it) => (
              <button key={it.variantId} type="button" onClick={() => add(it)} className="flex flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white text-start shadow-sm transition active:scale-[0.98] hover:border-brand">
                {/* eslint-disable-next-line @next/next/no-img-element -- plain image: served from the offline cache, no optimizer round-trip */}
                {it.image ? <img src={it.image} alt="" loading="lazy" className="h-20 w-full object-cover" /> : <div className="flex h-12 items-center justify-center bg-slate-100 text-lg text-slate-400">{it.name.slice(0, 1)}</div>}
                <div className="flex flex-1 flex-col p-2">
                  <span className="line-clamp-2 text-sm font-semibold leading-tight">{it.name}</span>
                  {it.label && <span className="truncate text-xs text-slate-500">{it.label}</span>}
                  <span className="mt-auto flex items-center justify-between pt-1"><b className="text-sm tabular-nums">{m(it.priceMinor)}</b><span className={`text-[11px] ${it.stock > 0 ? "text-slate-500" : "font-bold text-red-600"}`}>{it.stock}</span></span>
                </div>
              </button>
            ))}
            {results.length === 0 && <p className="col-span-full p-8 text-center text-sm text-slate-500">{d.catalog.length ? t("term.noResults") : t("term.emptyCatalog")}</p>}
          </div>
        </section>

        {/* cart */}
        <section className="flex min-h-0 flex-col rounded-2xl bg-white shadow-card">
          <div className="flex items-center justify-between border-b px-4 py-2 text-sm">
            <b>{t("term.cart")} · {tot.pieces}</b>
            {lines.length > 0 && <button type="button" className="text-slate-500 hover:text-red-600" onClick={clearCart}>{t("term.clear")}</button>}
          </div>
          <div className="min-h-0 flex-1 divide-y overflow-y-auto">
            {lines.length === 0 && <p className="p-8 text-center text-sm text-slate-500">{t("term.cartEmpty")}</p>}
            {lines.map((l, i) => (
              <div key={i} className="flex items-center gap-2 px-3 py-2 text-sm">
                <button type="button" className="min-w-0 flex-1 text-start" onClick={() => setPanel({ edit: i })}>
                  <div className="truncate font-semibold">{l.name}</div>
                  <div className="truncate text-xs text-slate-500">{l.label ? `${l.label} · ` : ""}{m(l.unitMinor)}{l.discountMinor > 0 ? ` · -${m(l.discountMinor)}` : ""}{l.note ? ` · ${l.note}` : ""}</div>
                </button>
                <div className="flex items-center rounded-full border" dir="ltr">
                  <button type="button" className="h-9 w-9 text-lg" onClick={() => setLines(lines.map((x, k) => (k === i ? { ...x, qty: Math.max(1, x.qty - 1) } : x)))}>−</button>
                  <span className="w-7 text-center font-bold">{l.qty}</span>
                  <button type="button" className="h-9 w-9 text-lg" onClick={() => setLines(lines.map((x, k) => (k === i ? { ...x, qty: x.qty + 1 } : x)))}>+</button>
                </div>
                <b className="w-20 text-end tabular-nums">{m(l.unitMinor * l.qty - l.discountMinor)}</b>
                <button type="button" className="px-1 text-slate-400 hover:text-red-600" onClick={() => setLines(lines.filter((_, k) => k !== i))} aria-label={t("common.delete")}>✕</button>
              </div>
            ))}
          </div>
          <div className="space-y-2 border-t p-3">
            <div className="flex items-center gap-2 text-sm">
              <span className="w-20 text-slate-600">{t("term.discount")}</span>
              <Input dir="ltr" inputMode="decimal" value={cartDisc} onChange={(e) => setCartDisc(e.target.value)} placeholder="0" className="h-9" />
              <button type="button" onClick={() => setCartPct(!cartPct)} className="h-9 rounded-lg border px-3 text-sm font-bold">{cartPct ? "%" : currency}</button>
            </div>
            <details className="text-sm">
              <summary className="cursor-pointer text-slate-600">{t("term.customerOptional")}{customer.name || customer.phone ? ` · ${customer.name || customer.phone}` : ""}</summary>
              <div className="mt-2 grid grid-cols-2 gap-2">
                <Input value={customer.name} onChange={(e) => setCustomer({ ...customer, name: e.target.value })} placeholder={t("term.customerName")} className="h-9" />
                <Input dir="ltr" inputMode="tel" value={customer.phone} onChange={(e) => setCustomer({ ...customer, phone: e.target.value })} placeholder={t("term.customerPhone")} className="h-9" />
              </div>
            </details>
            <div className="space-y-0.5 text-sm">
              <div className="flex justify-between"><span className="text-slate-600">{t("term.subtotal")}</span><span className="tabular-nums">{m(tot.subtotalMinor)}</span></div>
              {tot.discountMinor > 0 && <div className="flex justify-between text-red-600"><span>{t("term.discount")}</span><span className="tabular-nums">-{m(tot.discountMinor)}</span></div>}
              {tot.vatMinor > 0 && <div className="flex justify-between text-xs text-slate-500"><span>{t("term.vatIncluded", { p: String(shop?.vatPercent ?? "") })}</span><span className="tabular-nums">{m(tot.vatMinor)}</span></div>}
            </div>
            {overLimit && !cashier.canManage && <p className="text-xs text-amber-700">{approvedBy ? t("term.approvedBy", { name: approvedBy.name }) : t("term.needsApproval")}</p>}
            <div className="flex gap-2">
              <Button variant="secondary" disabled={!lines.length} onClick={() => void hold()}>{t("term.hold")} <kbd className="ms-1 text-[10px] opacity-60">F8</kbd></Button>
              <Button className="flex-1 py-4 text-lg" disabled={!lines.length} onClick={startPay}>{t("term.pay")} {m(tot.totalMinor)} <kbd className="ms-1 text-[10px] opacity-70">F4</kbd></Button>
            </div>
          </div>
        </section>
      </div>
      {typeof panel === "object" && panel !== null && "edit" in panel && lines[panel.edit] && (
        <LineEditor line={lines[panel.edit]} currency={currency} onClose={() => setPanel(null)}
          onSave={(l) => { setLines(lines.map((x, k) => (k === (panel as { edit: number }).edit ? l : x))); setPanel(null); }}
          onRemove={() => { setLines(lines.filter((_, k) => k !== (panel as { edit: number }).edit)); setPanel(null); }} />
      )}
      {modals}{receipt}
    </div>
  );
}

function LineEditor({ line, currency, onSave, onRemove, onClose }: { line: CartLine; currency: string; onSave: (l: CartLine) => void; onRemove: () => void; onClose: () => void }) {
  const { t, locale } = useI18n();
  const [qty, setQty] = useState(String(line.qty));
  const [disc, setDisc] = useState(line.discountMinor ? (line.discountMinor / 100).toString() : "");
  const [note, setNote] = useState(line.note ?? "");
  const q = Math.max(1, parseInt(qty || "1", 10) || 1);
  const dm = Math.min(line.unitMinor * q, toMinor(disc));
  return (
    <Modal open onClose={onClose} title={line.name}>
      <div className="space-y-3">
        <p className="text-sm text-slate-500">{line.label} · {money(line.unitMinor, currency, locale)} · {t("term.stockNow")} {line.stock}</p>
        <label className="block text-sm font-semibold">{t("term.qty")}<Input dir="ltr" inputMode="numeric" value={qty} onChange={(e) => setQty(e.target.value.replace(/\D/g, ""))} /></label>
        <label className="block text-sm font-semibold">{t("term.lineDiscount")}<Input dir="ltr" inputMode="decimal" value={disc} onChange={(e) => setDisc(e.target.value)} placeholder="0" /></label>
        <label className="block text-sm font-semibold">{t("term.note")}<Input value={note} onChange={(e) => setNote(e.target.value)} /></label>
        <div className="flex justify-between font-bold"><span>{t("term.total")}</span><span className="tabular-nums">{money(line.unitMinor * q - dm, currency, locale)}</span></div>
        <div className="flex gap-2">
          <Button variant="danger" onClick={onRemove}>{t("common.delete")}</Button>
          <Button className="flex-1" onClick={() => onSave({ ...line, qty: q, discountMinor: dm, note: note.trim() || null })}>{t("common.save")}</Button>
        </div>
      </div>
    </Modal>
  );
}

function Center({ children }: { children: React.ReactNode }) { return <div className="flex flex-1 items-center justify-center p-4" style={{ minHeight: "60dvh" }}>{children}</div>; }
function TopBtn({ children, onClick }: { children: React.ReactNode; onClick: () => void }) { return <button type="button" onClick={onClick} className="rounded-lg border border-slate-200 bg-white px-2.5 py-1 text-xs font-semibold hover:border-brand">{children}</button>; }
function Chip({ children, on, onClick }: { children: React.ReactNode; on: boolean; onClick: () => void }) { return <button type="button" onClick={onClick} className={`whitespace-nowrap rounded-full px-3 py-1 text-xs font-semibold ${on ? "bg-brand text-white" : "bg-white text-slate-700 ring-1 ring-slate-200"}`}>{children}</button>; }
