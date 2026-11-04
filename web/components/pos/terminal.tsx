"use client";

import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { money } from "@/lib/format";
import { all, byCode, del, kvGet, kvSet, put, req, uuid, wipe, type CartLine, type CatalogItem, type Cashier, type Count, type DeviceInfo, type Held, type Invoice, type Op, type Payment, type Shift, type Shop } from "@/lib/pos/db";
import { allocate, toMinor, totals } from "@/lib/pos/money";
import { matches, norm, words } from "@/lib/pos/search";
import { beepBad, beepOk } from "@/lib/pos/sound";
import * as sync from "@/lib/pos/sync";
import { useI18n } from "../i18n-provider";
import { Alert, Button, Input, Modal } from "../ui";
import { useWedgeScanner } from "../dashboard/pos-picker";
import { PayPanel } from "./pay";
import { CashMovePanel, CategorySheet, CloseShift, CountPanel, HeldPanel, InvoicesPanel, MenuSheet, OpenShift, PinPad, RegisterScreen, ReportView, ReturnPanel, SettingsPanel, SyncPanel, VariantPicker, type DrawerSummary, type MenuItem } from "./panels";
import { Receipt, type PrintDoc } from "./receipt";

type Local = { size: "58" | "80" | "A4" | null; autoPrint: boolean; sound: boolean };
type Data = {
  device: DeviceInfo | undefined; shop: Shop | null; cashiers: Cashier[]; methods: string[]; catalog: CatalogItem[];
  shift: Shift | null; held: Held[]; invoices: Invoice[]; count: Count | null; failed: Op[];
  local: Local; handover: { to: string; amountMinor: number } | null; sold: Record<string, number>; recent: string[]; lastMethod: string | null;
};
type Panel = null | "pay" | "invoices" | "held" | "cash" | "close" | "count" | "sync" | "settings" | "switch" | "approve" | "menu" | "cats" | "x" | "keys"
  | { return: Invoice } | { edit: number } | { variants: string };
type Product = { productId: string; name: string; image: string | null; variants: CatalogItem[]; minPrice: number; maxPrice: number; stock: number; sold: number };
type Toast = { tone: "red" | "amber" | "green"; text: string; id: number };

const IDLE_LOCK_MS = 15 * 60_000;
const RECENT = "__recent__";

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
  const [toast, setToast] = useState<Toast | null>(null);
  const [done, setDone] = useState<Invoice | null>(null);
  const [print, setPrint] = useState<PrintDoc | null>(null);
  const [busy, setBusy] = useState(false);
  const [approvedBy, setApprovedBy] = useState<Cashier | null>(null);
  const [pendingPrice, setPendingPrice] = useState<{ index: number; line: CartLine } | null>(null);
  const [cartOpen, setCartOpen] = useState(false);
  const [now, setNow] = useState(() => new Date());
  const search = useRef<HTMLInputElement>(null);
  const lastAct = useRef(0);

  const load = useCallback(async () => {
    const [device, shop, cashiers, methods, catalog, shift, held, invoices, counts, ops, local, handover, sold, recent, lastMethod] = await Promise.all([
      sync.device(), sync.shop(), sync.cashiers(), sync.methods(), sync.catalog(), kvGet<Shift>("shift"), all<Held>("held"), all<Invoice>("invoices"), all<Count>("counts"),
      all<Op>("outbox"), kvGet<Partial<Local>>("local"), kvGet<Data["handover"]>("handover"), kvGet<Record<string, number>>("sold"), kvGet<string[]>("recent"), kvGet<string>("lastMethod"),
    ]);
    setD({ device, shop: shop ?? null, cashiers, methods, catalog, shift: shift ?? null, held, invoices, count: counts.find((c) => c.status === "COUNTING") ?? counts[0] ?? null,
      failed: ops.filter((o) => o.state === "failed"), local: { size: local?.size ?? null, autoPrint: local?.autoPrint ?? true, sound: local?.sound ?? true },
      handover: handover ?? null, sold: sold ?? {}, recent: recent ?? [], lastMethod: lastMethod ?? null });
  }, []);

  useEffect(() => {
    lastAct.current = Date.now();
    void sync.start();
    sync.keepForOffline();
    void Promise.resolve().then(load);
    const tick = setInterval(() => setNow(new Date()), 30_000);
    const off = sync.onChange(() => void load());
    return () => { off(); clearInterval(tick); };
  }, [load]);

  // the till locks itself after a quiet while: the next person signs in with their PIN
  useEffect(() => {
    const touch = () => { lastAct.current = Date.now(); };
    window.addEventListener("pointerdown", touch);
    window.addEventListener("keydown", touch);
    const id = setInterval(() => { if (cashier && !done && lines.length === 0 && Date.now() - lastAct.current > IDLE_LOCK_MS) setCashier(null); }, 30_000);
    return () => { window.removeEventListener("pointerdown", touch); window.removeEventListener("keydown", touch); clearInterval(id); };
  }, [cashier, done, lines.length]);

  // messages go away on their own (errors stay until tapped)
  useEffect(() => {
    if (!toast || toast.tone === "red") return;
    const id = setTimeout(() => setToast((cur) => (cur?.id === toast.id ? null : cur)), 4000);
    return () => clearTimeout(id);
  }, [toast]);
  const say = useCallback((tone: Toast["tone"], text: string) => setToast({ tone, text, id: Date.now() }), []);

  const shop = d?.shop ?? null;
  const currency = shop?.currency ?? "EGP";
  const m = useCallback((v: number) => money(v, currency, locale), [currency, locale]);
  const receiptSize = d?.local.size ?? d?.device?.receiptSize ?? shop?.receiptSize ?? "80";
  const sound = d?.local.sound ?? true;

  // ---- the catalog, grouped by product ------------------------------------------------------------------------------------

  const products = useMemo<Product[]>(() => {
    const by = new Map<string, Product>();
    for (const c of d?.catalog ?? []) {
      if (!c.active) continue;
      const p = by.get(c.productId) ?? { productId: c.productId, name: c.name, image: c.image, variants: [], minPrice: c.priceMinor, maxPrice: c.priceMinor, stock: 0, sold: 0 };
      p.variants.push(c);
      p.minPrice = Math.min(p.minPrice, c.priceMinor); p.maxPrice = Math.max(p.maxPrice, c.priceMinor);
      p.stock += Math.max(0, c.stock); p.sold += d?.sold[c.variantId] ?? 0;
      if (!p.image && c.image) p.image = c.image;
      by.set(c.productId, p);
    }
    return [...by.values()];
  }, [d]);
  const catOf = useCallback((c: CatalogItem) => (locale === "ar" ? c.categoryAr || c.categoryEn : c.categoryEn || c.categoryAr), [locale]);
  const cats = useMemo(() => {
    const count = new Map<string, number>();
    for (const p of products) { const k = catOf(p.variants[0]); if (k) count.set(k, (count.get(k) ?? 0) + 1); }
    return [...count.entries()].sort((a, b) => b[1] - a[1]).map(([k]) => k);
  }, [products, catOf]);
  const shown = useMemo(() => {
    const ws = words(q);
    let list = products;
    if (cat === RECENT) { const order = d?.recent ?? []; list = order.map((id) => products.find((p) => p.productId === id)).filter((p): p is Product => !!p); }
    else if (cat) list = list.filter((p) => catOf(p.variants[0]) === cat);
    if (ws.length) list = list.filter((p) => p.variants.some((v) => matches(ws, v.name, v.label, v.sku, v.barcode)));
    if (cat !== RECENT) list = [...list].sort((a, b) => b.sold - a.sold || a.name.localeCompare(b.name, locale === "ar" ? "ar" : "en"));
    return list.slice(0, 80);
  }, [products, q, cat, d?.recent, catOf, locale]);

  // ---- the cart ---------------------------------------------------------------------------------------------------------

  const cartDiscMinor = useMemo(() => {
    const sub = lines.reduce((n, l) => n + l.unitMinor * l.qty - l.discountMinor, 0);
    if (!cartDisc.trim()) return 0;
    if (cartPct) { const p = Math.min(100, Math.max(0, Number(norm(cartDisc).replace(",", ".")) || 0)); return Math.round((sub * p) / 100); }
    return Math.min(sub, toMinor(cartDisc));
  }, [cartDisc, cartPct, lines]);
  const tot = useMemo(() => totals(lines, cartDiscMinor, shop), [lines, cartDiscMinor, shop]);
  const overLimit = shop ? tot.discountMinor > Math.round((tot.subtotalMinor * (shop.posMaxDiscountPct ?? 0)) / 100) : false;

  const add = useCallback((it: CatalogItem, qty = 1) => {
    if (it.stock <= 0) say("amber", t("term.outOfStockSold", { name: it.name }));
    setLines((cur) => {
      const i = cur.findIndex((l) => l.variantId === it.variantId && !l.note && l.discountMinor === 0 && l.unitMinor === it.priceMinor);
      if (i >= 0) return cur.map((l, k) => (k === i ? { ...l, qty: l.qty + qty } : l));
      return [...cur, { variantId: it.variantId, name: it.name, label: it.label, sku: it.sku, unitMinor: it.priceMinor, qty, discountMinor: 0, note: null, stock: it.stock }];
    });
  }, [say, t]);

  const tapProduct = (p: Product) => {
    if (p.variants.length === 1) add(p.variants[0]);
    else setPanel({ variants: p.productId });
  };

  const scan = useCallback(async (code: string) => {
    if (!cashier || (panel !== null && panel !== "menu")) return;
    if (done) setDone(null);   // scanning on the "sale complete" screen starts the next sale
    const it = await byCode(code);
    if (it) { add(it); setQ(""); if (sound) beepOk(); }
    else { say("red", t("term.noCode", { code })); if (sound) beepBad(); }
  }, [add, cashier, panel, done, t, say, sound]);
  useWedgeScanner((c) => void scan(c), !!cashier && !!d?.shift && (panel === null || panel === "menu"));

  const clearCart = () => { setLines([]); setCartDisc(""); setCartPct(false); setCustomer({ name: "", phone: "" }); setHeldId(null); setApprovedBy(null); setCartOpen(false); };

  // customers from this till's own invoices: typing a mobile suggests the name, the last five are one tap away
  const customers = useMemo(() => {
    const seen = new Map<string, string>();
    for (const i of [...(d?.invoices ?? [])].sort((a, b) => b.at.localeCompare(a.at))) if (i.phone && !seen.has(i.phone)) seen.set(i.phone, i.customerName && i.customerName !== "Walk-in customer" ? i.customerName : "");
    return [...seen.entries()].map(([phone, name]) => ({ phone, name }));
  }, [d?.invoices]);
  const phoneHits = useMemo(() => {
    const p = norm(customer.phone).replace(/\D/g, "");
    if (p.length < 3) return customers.slice(0, 5);
    return customers.filter((c) => c.phone.replace(/\D/g, "").includes(p)).slice(0, 5);
  }, [customer.phone, customers]);

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

  const reportRows = (): [string, string][] => {
    const x = drawer!;
    return [
      [t("term.cashier"), d?.shift?.cashierName ?? ""], [t("term.openedAt"), new Date(d!.shift!.openedAt).toLocaleString(locale === "ar" ? "ar-EG" : "en-GB")], ["—", ""],
      [t("term.sales"), `${x.sales} · ${m(x.salesMinor)}`], ...Object.entries(x.byMethod).map(([k, v]) => [t(`pay.${k}`), m(v)] as [string, string]), ["—", ""],
      [t("term.openingFloat"), m(x.floatMinor)], [t("term.cashSales"), m(x.cashSalesMinor)], [t("term.cashRefunds"), `-${m(x.cashRefundsMinor)}`],
      [t("term.cash.PAY_IN"), m(x.payInMinor)], [t("term.cash.PAY_OUT"), `-${m(x.payOutMinor)}`], [t("term.cash.DROP"), `-${m(x.dropMinor)}`], ["—", ""],
      [t("term.expectedCash"), m(x.expectedMinor)],
    ];
  };
  const report = (title: string): PrintDoc => ({ kind: "report", title, rows: reportRows(), at: new Date().toISOString() });

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
        const sold = ((await req(s.kv.get("sold"))) as Record<string, number> | undefined) ?? {};
        let recent = ((await req(s.kv.get("recent"))) as string[] | undefined) ?? [];
        for (const l of lines) {
          const it = (await req(s.catalog.get(l.variantId))) as CatalogItem | undefined;
          if (it) {
            s.catalog.put({ ...it, stock: it.stock - l.qty });
            recent = [it.productId, ...recent.filter((x) => x !== it.productId)];
          }
          sold[l.variantId] = (sold[l.variantId] ?? 0) + l.qty;
        }
        s.kv.put(sold, "sold");
        s.kv.put(recent.slice(0, 12), "recent");
        s.kv.put(payments.length === 1 ? payments[0].method : "CASH", "lastMethod");
        if (heldId) s.held.delete(heldId);
        return { number };
      });
      clearCart();
      setPanel(null);
      if (invoice) {
        setDone(invoice);
        if (d.local.autoPrint) setPrint({ kind: "sale", invoice });
      }
    } catch (e) {
      say("red", `${t("term.saveFailed")} ${(e as Error).message}`);
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
    say("green", t("term.held"));
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
    say("green", t("term.returned", { v: m(refundMinor) }));
  };

  const cashMove = async (kind: "PAY_IN" | "PAY_OUT" | "DROP", amountMinor: number, reason: string) => {
    if (!cashier || !d?.shift) return;
    const id = uuid();
    await sync.enqueue("CASH_MOVE", cashier.userId, { moveId: id, shiftId: d.shift.id, kind, amountMinor, reason }, async (s) => {
      const sh = (await req(s.kv.get("shift"))) as Shift | undefined;
      if (sh) s.kv.put({ ...sh, moves: [...sh.moves, { id, kind, amountMinor, reason, at: new Date().toISOString() }] }, "shift");
    });
    setPanel(null);
    say("green", t("term.cashSaved"));
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
    if (sound) beepOk();
    sync.changed();
  };
  const sendCount = async () => {
    if (!d?.count || !cashier) return;
    const c = d.count;
    await sync.enqueue("COUNT", cashier.userId, { countId: c.id, name: c.name, status: "REVIEW", lines: Object.entries(c.lines).map(([variantId, l]) => ({ variantId, expected: l.expected, counted: l.counted, at: l.at })) }, (s) => {
      s.counts.put({ ...c, status: "REVIEW", sent: true });
    });
  };

  // a line's price changed by someone who may not: a manager's PIN first (the server also flags it for the manager)
  const saveLine = (index: number, line: CartLine) => {
    const before = lines[index];
    if (before && line.unitMinor !== before.unitMinor && !cashier?.canManage) { setPendingPrice({ index, line }); setPanel("approve"); return; }
    setLines(lines.map((x, k) => (k === index ? line : x)));
    setPanel(null);
  };

  const toggleFullscreen = () => {
    try { if (document.fullscreenElement) void document.exitFullscreen(); else void document.documentElement.requestFullscreen(); } catch { /* not supported */ }
    setPanel(null);
  };

  // keyboard: F2 search, F4 pay, F8 hold, F9 invoices, F10 menu, +/- the last line, Delete removes it, Esc clears the search
  useEffect(() => {
    const h = (e: KeyboardEvent) => {
      if (!cashier || done) return;
      const typing = (e.target as HTMLElement | null)?.tagName === "INPUT";
      if (e.key === "F2") { e.preventDefault(); search.current?.focus(); }
      if (e.key === "F4" && lines.length && panel === null) { e.preventDefault(); startPay(); }
      if (e.key === "F8" && lines.length && panel === null) { e.preventDefault(); void hold(); }
      if (e.key === "F9" && panel === null) { e.preventDefault(); setPanel("invoices"); }
      if (e.key === "F10") { e.preventDefault(); setPanel(panel === "menu" ? null : "menu"); }
      if (e.key === "Escape" && panel === null && q) { setQ(""); }
      if (!typing && panel === null && lines.length) {
        const last = lines.length - 1;
        if (e.key === "+" || e.key === "=") { e.preventDefault(); setLines(lines.map((x, k) => (k === last ? { ...x, qty: x.qty + 1 } : x))); }
        if (e.key === "-") { e.preventDefault(); setLines(lines.map((x, k) => (k === last ? { ...x, qty: Math.max(1, x.qty - 1) } : x))); }
        if (e.key === "Delete") { e.preventDefault(); setLines(lines.slice(0, -1)); }
      }
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

  const whatsapp = (inv: Invoice, phone: string) => {
    const body = inv.lines.map((l) => `${l.qty} × ${l.name}${l.label ? ` (${l.label})` : ""} = ${m(l.qty * l.unitMinor - (l.discountMinor ?? 0))}`).join("\n");
    const text = `${shop?.name ?? ""}\n${t("term.invoiceNo")}: ${inv.number}\n${body}\n${t("term.total")}: ${m(inv.totalMinor)}\n${t("term.thanks")}`;
    let p = norm(phone).replace(/\D/g, "");
    if (p.startsWith("0")) p = "20" + p.slice(1);   // Egyptian 01… → 201…
    window.open(`https://wa.me/${p}?text=${encodeURIComponent(text)}`, "_blank", "noopener");
  };

  // ---- screens ----------------------------------------------------------------------------------------------------------

  const onPrinted = useCallback(() => setPrint(null), []);
  const receipt = <Receipt doc={print} shop={shop} size={receiptSize} deviceName={d?.device?.name ?? ""} onPrinted={onPrinted} />;

  if (!d) return <Center><span className="animate-pulse text-slate-500">…</span></Center>;
  if (status.registered === false || (!d.device && status.ready)) {
    return <Center><RegisterScreen pending={status.pending + status.failed} revoked={!!d.device} onRegister={async (name) => { if (d.device) await wipe(false); await sync.register(name); await load(); }} /></Center>;
  }
  if (!d.device) return <Center><span className="animate-pulse text-slate-500">{t("term.loadingCopy")}</span></Center>;

  const shiftSales = drawer ? `${drawer.sales} · ${m(drawer.salesMinor)}` : "";
  const top = (
    <header className="flex min-w-0 items-center gap-2 border-b border-slate-200 bg-white px-3 py-2 print:hidden">
      <div className="min-w-0 flex-1">
        <div className="flex min-w-0 items-center gap-2">
          <b className="truncate text-sm">{shop?.name}</b>
          <button type="button" onClick={() => setPanel("sync")} className={`flex shrink-0 items-center gap-1.5 rounded-full px-2 py-0.5 text-xs font-semibold ${status.online ? "bg-emerald-50 text-emerald-700" : "bg-amber-100 text-amber-900"}`}>
            <span className={`h-2 w-2 rounded-full ${status.online ? "bg-emerald-500" : "bg-amber-500"} ${status.syncing ? "animate-pulse" : ""}`} />
            <span className="hidden sm:inline">{status.online ? t("term.online") : t("term.offline")}</span>
            {status.pending > 0 && <span>· {status.pending}</span>}{status.failed > 0 && <span>· ⚠ {status.failed}</span>}
          </button>
        </div>
        {cashier && <div className="truncate text-xs text-slate-500">👤 {cashier.name}{d.shift ? ` · ${t("term.shiftSince", { time: new Date(d.shift.openedAt).toLocaleTimeString(locale === "ar" ? "ar-EG" : "en-GB", { hour: "2-digit", minute: "2-digit" }) })} · ${shiftSales}` : ""}</div>}
      </div>
      {cashier && d.shift && <button type="button" onClick={() => setPanel("invoices")} className="shrink-0 rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm font-semibold hover:border-brand">🧾 <span className="hidden sm:inline">{t("term.invoicesReturns")}</span></button>}
      {cashier && <button type="button" onClick={() => setPanel("menu")} className="shrink-0 rounded-xl bg-slate-900 px-3 py-2 text-sm font-semibold text-white">☰ <span className="hidden sm:inline">{t("term.menu")}</span>{d.held.length ? <span className="ms-1 rounded-full bg-amber-500 px-1.5 text-[11px]">{d.held.length}</span> : null}</button>}
    </header>
  );

  const menuItems: MenuItem[] = !cashier ? [] : [
    ...(d.shift ? [
      { key: "held", label: t("term.heldBtn"), icon: "⏸", onClick: () => setPanel("held"), badge: d.held.length || undefined },
      ...(cashier.canCash ? [{ key: "cash", label: t("term.cashBtn"), icon: "💵", onClick: () => setPanel("cash") }] : []),
      { key: "x", label: t("term.xReport"), icon: "📊", onClick: () => setPanel("x") },
      ...(cashier.canCount ? [{ key: "count", label: t("term.countBtn"), icon: "📦", onClick: () => setPanel("count") }] : []),
      { key: "close", label: t("term.closeShift"), icon: "🔐", onClick: () => setPanel("close") },
    ] : []),
    { key: "switch", label: t("term.switch"), icon: "👥", onClick: () => setPanel("switch") },
    { key: "lock", label: t("term.lock"), icon: "🔒", onClick: () => { if (!lines.length) { setCashier(null); setPanel(null); } else say("amber", t("term.lockCartFirst")); } },
    { key: "sync", label: t("term.syncTitle"), icon: status.online ? "🟢" : "🟠", onClick: () => setPanel("sync"), badge: status.pending || undefined },
    { key: "full", label: t("term.fullscreen"), icon: "⛶", onClick: toggleFullscreen },
    { key: "keys", label: t("term.shortcuts"), icon: "⌨️", onClick: () => setPanel("keys") },
    { key: "settings", label: t("term.settings"), icon: "⚙️", onClick: () => setPanel("settings") },
  ];

  const variantsOf = typeof panel === "object" && panel !== null && "variants" in panel ? products.find((p) => p.productId === panel.variants) : undefined;
  const modals = (
    <>
      <Modal open={panel === "menu"} onClose={() => setPanel(null)} title={t("term.menu")}><MenuSheet items={menuItems} /></Modal>
      <Modal open={panel === "switch"} onClose={() => setPanel(null)} title={t("term.switch")}><PinPad cashiers={d.cashiers} title={t("term.enterPin")} onRefresh={() => sync.refresh(true)} onOk={(c) => { setCashier(c); setPanel(null); }} /></Modal>
      <Modal open={panel === "approve"} onClose={() => { setPanel(null); setPendingPrice(null); }} title={t("term.managerApprove")}>
        <p className="mb-3 text-sm text-slate-600">{pendingPrice ? t("term.priceNeedsManager") : t("term.discountOverLimit", { p: String(shop?.posMaxDiscountPct ?? 0) })}</p>
        <PinPad cashiers={d.cashiers} title={t("term.managerPin")} need={(c) => c.canManage} onOk={(c) => {
          setApprovedBy(c);
          if (pendingPrice) { setLines(lines.map((x, k) => (k === pendingPrice.index ? pendingPrice.line : x))); setPendingPrice(null); setPanel(null); }
          else setPanel("pay");
        }} />
      </Modal>
      <Modal open={panel === "pay"} onClose={() => !busy && setPanel(null)} title={t("term.payTitle")}>
        <PayPanel totalMinor={tot.totalMinor} currency={currency} methods={d.methods} busy={busy} lastMethod={d.lastMethod} onCancel={() => setPanel(null)} onPay={(p, ten, ch) => void pay(p, ten, ch)} />
      </Modal>
      <Modal open={!!variantsOf} onClose={() => setPanel(null)} title={variantsOf?.name ?? ""}>
        {variantsOf && <VariantPicker variants={variantsOf.variants} currency={currency} onAdd={(v, n) => { add(v, n); setPanel(null); if (sound) beepOk(); }} />}
      </Modal>
      <Modal open={panel === "cats"} onClose={() => setPanel(null)} title={t("term.categories")}><CategorySheet cats={cats} current={cat} onPick={(c) => { setCat(c); setPanel(null); }} /></Modal>
      <Modal open={panel === "invoices"} onClose={() => setPanel(null)} title={t("term.invoicesReturns")} wide>
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
      <Modal open={panel === "x" && !!drawer} onClose={() => setPanel(null)} title={t("term.xReport")}>{drawer && <ReportView rows={reportRows()} onPrint={() => { setPrint(report(t("term.xReport"))); setPanel(null); }} />}</Modal>
      <Modal open={panel === "close"} onClose={() => setPanel(null)} title={t("term.closeShift")} wide>
        {d.shift && drawer && <CloseShift shift={d.shift} summary={drawer} currency={currency} cashiers={d.cashiers} onPrintX={() => setPrint(report(t("term.xReport")))} onClose={(c, den, n, h) => void closeShift(c, den, n, h)} />}
      </Modal>
      <Modal open={panel === "count"} onClose={() => setPanel(null)} title={t("term.countBtn")} wide>
        <CountPanel count={d.count && d.count.status === "COUNTING" ? d.count : null} catalog={d.catalog} find={byCode} onStart={(n) => void startCount(n)} onSet={(i, c) => void setCount(i, c)} onSend={() => void sendCount()} />
      </Modal>
      <Modal open={panel === "sync"} onClose={() => setPanel(null)} title={t("term.syncTitle")}>
        <SyncPanel pending={status.pending} failed={d.failed} online={status.online} lastSync={status.lastSync} syncing={status.syncing} onSync={() => { void sync.refresh().then(() => sync.push()); }} onDismiss={(s) => void sync.dismissFailed(s)} />
      </Modal>
      <Modal open={panel === "keys"} onClose={() => setPanel(null)} title={t("term.shortcuts")}>
        <ul className="space-y-1.5 text-sm">{([["F2", "term.k.search"], ["F4", "term.k.pay"], ["F8", "term.k.hold"], ["F9", "term.k.invoices"], ["F10", "term.k.menu"], ["+ / −", "term.k.qty"], ["Delete", "term.k.remove"], ["Esc", "term.k.clear"]] as const).map(([k, l]) => <li key={k} className="flex justify-between gap-3"><span>{t(l)}</span><kbd className="rounded bg-slate-100 px-2 font-mono text-xs" dir="ltr">{k}</kbd></li>)}</ul>
      </Modal>
      <Modal open={panel === "settings"} onClose={() => setPanel(null)} title={t("term.settings")}>
        <SettingsPanel size={d.local.size} shopSize={d.device?.receiptSize ?? shop?.receiptSize ?? "80"} autoPrint={d.local.autoPrint} sound={d.local.sound} deviceName={d.device.name} branchName={d.device.branchName}
          canForget={status.pending === 0 && !d.shift}
          onSize={async (s) => { await kvSet("local", { ...d.local, size: s }); sync.changed(); }} onAutoPrint={async (v) => { await kvSet("local", { ...d.local, autoPrint: v }); sync.changed(); }}
          onSound={async (v) => { await kvSet("local", { ...d.local, sound: v }); sync.changed(); }}
          onForget={async () => { await sync.forget(); await wipe(true); location.reload(); }} />
      </Modal>
    </>
  );
  const toastView = toast && (
    <div className="pointer-events-none fixed inset-x-0 top-14 z-40 flex justify-center px-3 print:hidden">
      <button type="button" className="pointer-events-auto max-w-md" onClick={() => setToast(null)}><Alert tone={toast.tone}>{toast.text}</Alert></button>
    </div>
  );

  const shell = (body: React.ReactNode) => (
    <div className="flex min-h-dvh flex-col overflow-x-hidden bg-slate-50">{top}{toastView}<Center>{body}</Center>{modals}{receipt}</div>
  );

  if (!cashier) {
    return shell(
      <div className="w-full max-w-sm space-y-3 rounded-3xl bg-white p-6 shadow-card">
        <div className="text-center"><div className="text-3xl font-extrabold tabular-nums" dir="ltr">{now.toLocaleTimeString(locale === "ar" ? "ar-EG" : "en-GB", { hour: "2-digit", minute: "2-digit" })}</div><div className="truncate text-xs text-slate-500">{d.device.name} · {d.device.branchName}</div></div>
        <PinPad cashiers={d.cashiers} title={t("term.enterPin")} onRefresh={() => sync.refresh(true)} onOk={(c) => { setCashier(c); lastAct.current = Date.now(); }} />
      </div>,
    );
  }

  if (!d.shift) {
    const suggested = d.handover && d.handover.to === cashier.userId ? d.handover.amountMinor : 0;
    return shell(<div className="w-full max-w-sm rounded-3xl bg-white p-6 shadow-card"><h2 className="mb-3 text-lg font-bold">{t("term.openShift")}</h2><OpenShift currency={currency} suggested={suggested} cashierName={cashier.name} onOpen={(f) => void openShift(f)} /></div>);
  }

  if (done) return shell(<DoneCard inv={done} m={m} online={status.online} onPrint={() => setPrint({ kind: "sale", invoice: done })} onWhatsapp={(p) => whatsapp(done, p)} onNext={() => { setDone(null); setTimeout(() => search.current?.focus(), 30); }} />);

  const cartBody = (
    <>
      <div className="flex items-center justify-between border-b px-4 py-2 text-sm">
        <b>{t("term.cart")} · {tot.pieces}</b>
        <div className="flex items-center gap-3">
          {lines.length > 0 && <button type="button" className="text-slate-500 hover:text-red-600" onClick={clearCart}>{t("term.clear")}</button>}
          <button type="button" className="text-lg text-slate-500 lg:hidden" onClick={() => setCartOpen(false)} aria-label={t("term.back")}>✕</button>
        </div>
      </div>
      <div className="min-h-0 flex-1 divide-y overflow-y-auto overflow-x-hidden">
        {lines.length === 0 && <p className="p-8 text-center text-sm text-slate-500">{t("term.cartEmpty")}</p>}
        {lines.map((l, i) => (
          <div key={i} className="flex min-w-0 items-center gap-2 px-3 py-2 text-sm">
            <button type="button" className="min-w-0 flex-1 text-start" onClick={() => setPanel({ edit: i })}>
              <div className="truncate font-semibold">{l.name}</div>
              <div className="truncate text-xs text-slate-500">{l.label ? `${l.label} · ` : ""}{m(l.unitMinor)}{l.discountMinor > 0 ? ` · -${m(l.discountMinor)}` : ""}{l.note ? ` · ${l.note}` : ""}{l.stock - l.qty < 0 ? ` · ⚠ ${t("term.overStock")}` : ""}</div>
            </button>
            <div className="flex shrink-0 items-center rounded-full border" dir="ltr">
              <button type="button" className="h-10 w-10 text-lg" onClick={() => setLines(lines.map((x, k) => (k === i ? { ...x, qty: Math.max(1, x.qty - 1) } : x)))}>−</button>
              <span className="w-7 text-center font-bold">{l.qty}</span>
              <button type="button" className="h-10 w-10 text-lg" onClick={() => setLines(lines.map((x, k) => (k === i ? { ...x, qty: x.qty + 1 } : x)))}>+</button>
            </div>
            <b className="w-20 shrink-0 truncate text-end tabular-nums">{m(l.unitMinor * l.qty - l.discountMinor)}</b>
            <button type="button" className="shrink-0 px-1 text-slate-400 hover:text-red-600" onClick={() => setLines(lines.filter((_, k) => k !== i))} aria-label={t("common.delete")}>✕</button>
          </div>
        ))}
      </div>
      <div className="space-y-2 border-t p-3 pb-[calc(0.75rem+env(safe-area-inset-bottom))]">
        <div className="flex min-w-0 items-center gap-2 text-sm">
          <span className="w-16 shrink-0 text-slate-600">{t("term.discount")}</span>
          <Input dir="ltr" inputMode="decimal" value={cartDisc} onChange={(e) => setCartDisc(e.target.value)} placeholder="0" className="h-10 min-w-0" />
          <button type="button" onClick={() => setCartPct(!cartPct)} className="h-10 shrink-0 rounded-lg border px-3 text-sm font-bold">{cartPct ? "%" : currency}</button>
        </div>
        <details className="text-sm">
          <summary className="cursor-pointer truncate text-slate-600">{t("term.customerOptional")}{customer.name || customer.phone ? ` · ${customer.name || customer.phone}` : ""}</summary>
          <div className="mt-2 grid grid-cols-2 gap-2">
            <Input dir="ltr" inputMode="tel" value={customer.phone} onChange={(e) => setCustomer({ ...customer, phone: e.target.value })} placeholder={t("term.customerPhone")} className="h-10 min-w-0" />
            <Input value={customer.name} onChange={(e) => setCustomer({ ...customer, name: e.target.value })} placeholder={t("term.customerName")} className="h-10 min-w-0" />
          </div>
          {phoneHits.length > 0 && <div className="mt-2 flex flex-wrap gap-1.5">{phoneHits.map((c) => <button key={c.phone} type="button" onClick={() => setCustomer({ phone: c.phone, name: c.name || customer.name })} className="max-w-full truncate rounded-full bg-slate-100 px-2.5 py-1 text-xs"><span dir="ltr">{c.phone}</span>{c.name ? ` · ${c.name}` : ""}</button>)}</div>}
        </details>
        <div className="space-y-0.5 text-sm">
          <div className="flex justify-between"><span className="text-slate-600">{t("term.subtotal")}</span><span className="tabular-nums">{m(tot.subtotalMinor)}</span></div>
          {tot.discountMinor > 0 && <div className="flex justify-between text-red-600"><span>{t("term.discount")}</span><span className="tabular-nums">-{m(tot.discountMinor)}</span></div>}
          {tot.vatMinor > 0 && <div className="flex justify-between text-xs text-slate-500"><span>{t("term.vatIncluded", { p: String(shop?.vatPercent ?? "") })}</span><span className="tabular-nums">{m(tot.vatMinor)}</span></div>}
        </div>
        {overLimit && !cashier.canManage && <p className="text-xs text-amber-700">{approvedBy ? t("term.approvedBy", { name: approvedBy.name }) : t("term.needsApproval")}</p>}
        <div className="flex gap-2">
          <Button variant="secondary" disabled={!lines.length} onClick={() => void hold()}>{t("term.hold")}</Button>
          <Button className="min-w-0 flex-1 py-4 text-lg" disabled={!lines.length} onClick={startPay}><span className="truncate">{t("term.pay")} {m(tot.totalMinor)}</span></Button>
        </div>
      </div>
    </>
  );

  const topCats = cats.slice(0, 8);
  const otherCat = !!cat && cat !== RECENT && !topCats.includes(cat);
  return (
    <div className="flex h-dvh flex-col overflow-x-hidden bg-slate-50">
      {top}{toastView}
      <div className="grid min-h-0 min-w-0 flex-1 gap-3 p-3 lg:grid-cols-[minmax(0,1fr)_clamp(340px,34vw,440px)] print:hidden">
        {/* products */}
        <section className="flex min-h-0 min-w-0 flex-col gap-2">
          <input ref={search} value={q} onChange={(e) => setQ(e.target.value)} placeholder={t("term.searchPh")}
            className="w-full min-w-0 rounded-xl border border-slate-200 bg-white px-3.5 py-2.5 text-base outline-none focus:border-brand focus:ring-2 focus:ring-brand/20"
            onKeyDown={async (e) => {
              if (e.key !== "Enter" || !q.trim()) return;
              const it = await byCode(q);
              if (it) { add(it); setQ(""); if (sound) beepOk(); return; }
              if (shown.length === 1) { tapProduct(shown[0]); setQ(""); return; }
              if (!shown.length) { say("red", t("term.noCode", { code: q })); if (sound) beepBad(); }
            }} />
          <div className="flex flex-wrap gap-1.5">
            <Chip on={!cat} onClick={() => setCat("")}>{t("term.all")}</Chip>
            {d.recent.length > 0 && <Chip on={cat === RECENT} onClick={() => setCat(RECENT)}>🕘 {t("term.recent")}</Chip>}
            {topCats.map((c) => <Chip key={c} on={cat === c} onClick={() => setCat(c)}>{c}</Chip>)}
            {cats.length > topCats.length && <Chip on={otherCat} onClick={() => setPanel("cats")}>{otherCat ? cat : t("term.more")} ▾</Chip>}
          </div>
          <div className="grid min-h-0 flex-1 auto-rows-min grid-cols-2 gap-2 overflow-y-auto overflow-x-hidden pb-24 sm:grid-cols-3 lg:pb-0 xl:grid-cols-4 2xl:grid-cols-5">
            {shown.map((p) => (
              <button key={p.productId} type="button" onClick={() => tapProduct(p)} className="flex min-w-0 flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white text-start shadow-sm transition active:scale-[0.98] hover:border-brand">
                {/* eslint-disable-next-line @next/next/no-img-element -- plain image: served from the offline cache, no optimizer round-trip */}
                {p.image ? <img src={p.image} alt="" loading="lazy" className="aspect-[4/3] w-full object-cover" /> : <div className="flex aspect-[4/3] w-full items-center justify-center bg-gradient-to-br from-slate-100 to-slate-200 text-2xl font-bold text-slate-400">{p.name.slice(0, 1)}</div>}
                <div className="flex min-w-0 flex-1 flex-col p-2">
                  <span className="line-clamp-2 text-sm font-semibold leading-tight">{p.name}</span>
                  {p.variants.length > 1 && <span className="text-[11px] text-slate-500">{t("term.options", { n: String(p.variants.length) })}</span>}
                  <span className="mt-auto flex min-w-0 items-center justify-between gap-1 pt-1">
                    <b className="truncate text-sm tabular-nums">{p.minPrice === p.maxPrice ? m(p.minPrice) : t("term.from", { v: m(p.minPrice) })}</b>
                    <span className={`shrink-0 rounded-full px-1.5 text-[11px] ${p.stock > 0 ? "bg-slate-100 text-slate-600" : "bg-red-50 font-bold text-red-600"}`}>{p.stock}</span>
                  </span>
                </div>
              </button>
            ))}
            {shown.length === 0 && <p className="col-span-full p-8 text-center text-sm text-slate-500">{d.catalog.length ? t("term.noResults") : t("term.emptyCatalog")}</p>}
          </div>
        </section>

        {/* the cart: a column on wide screens, a sheet on phones and tablets in portrait */}
        <section className="hidden min-h-0 min-w-0 flex-col rounded-2xl bg-white shadow-card lg:flex">{cartBody}</section>
      </div>

      {/* phones: the cart bar at the bottom opens the cart */}
      {!cartOpen && (
        <div className="fixed inset-x-0 bottom-0 z-30 border-t bg-white p-2 pb-[calc(0.5rem+env(safe-area-inset-bottom))] shadow-[0_-8px_20px_-12px_rgb(0_0_0/0.25)] lg:hidden print:hidden">
          <div className="flex gap-2">
            <button type="button" onClick={() => setCartOpen(true)} className="min-w-0 flex-1 rounded-xl border border-slate-200 px-3 py-2 text-start">
              <span className="block truncate text-sm font-semibold">🛒 {t("term.cart")} · {tot.pieces} {t("term.pieces")}</span>
              <span className="block truncate text-xs text-slate-500 tabular-nums">{m(tot.totalMinor)}</span>
            </button>
            <Button className="shrink-0 px-6 text-base" disabled={!lines.length} onClick={startPay}>{t("term.pay")}</Button>
          </div>
        </div>
      )}
      {cartOpen && <div className="fixed inset-0 z-40 flex flex-col bg-white lg:hidden print:hidden">{cartBody}</div>}

      {typeof panel === "object" && panel !== null && "edit" in panel && lines[panel.edit] && (
        <LineEditor line={lines[panel.edit]} currency={currency} onClose={() => setPanel(null)}
          onSave={(l) => saveLine((panel as { edit: number }).edit, l)}
          onRemove={() => { setLines(lines.filter((_, k) => k !== (panel as { edit: number }).edit)); setPanel(null); }} />
      )}
      {modals}{receipt}
    </div>
  );
}

function DoneCard({ inv, m, online, onPrint, onWhatsapp, onNext }: { inv: Invoice; m: (v: number) => string; online: boolean; onPrint: () => void; onWhatsapp: (phone: string) => void; onNext: () => void }) {
  const { t } = useI18n();
  const [phone, setPhone] = useState(inv.phone ?? "");
  return (
    <div className="w-full max-w-md space-y-4 rounded-3xl bg-white p-6 text-center shadow-card print:hidden">
      <div className="text-5xl">✅</div>
      <h2 className="text-xl font-bold">{t("term.saleDone")}</h2>
      <p className="text-sm text-slate-500" dir="ltr">{inv.number}</p>
      <p className="text-3xl font-extrabold tabular-nums">{m(inv.totalMinor)}</p>
      {(inv.changeMinor ?? 0) > 0 && <div className="rounded-2xl bg-emerald-50 p-4 text-emerald-800"><div className="text-sm">{t("term.change")}</div><div className="text-5xl font-extrabold tabular-nums">{m(inv.changeMinor ?? 0)}</div></div>}
      {!online && <p className="text-xs text-amber-700">{t("term.savedOffline")}</p>}
      <div className="flex min-w-0 gap-2">
        <Input dir="ltr" inputMode="tel" value={phone} onChange={(e) => setPhone(e.target.value)} placeholder={t("term.customerPhone")} className="min-w-0" />
        <Button variant="secondary" className="shrink-0" disabled={phone.replace(/\D/g, "").length < 8} onClick={() => onWhatsapp(phone)}>WhatsApp</Button>
      </div>
      <div className="flex gap-2">
        <Button variant="secondary" className="flex-1" onClick={onPrint}>{t("term.print")}</Button>
        <Button autoFocus className="flex-[2] py-3" onClick={onNext}>{t("term.newSale")}</Button>
      </div>
      <p className="text-xs text-slate-400">{t("term.scanForNext")}</p>
    </div>
  );
}

function LineEditor({ line, currency, onSave, onRemove, onClose }: { line: CartLine; currency: string; onSave: (l: CartLine) => void; onRemove: () => void; onClose: () => void }) {
  const { t, locale } = useI18n();
  const [qty, setQty] = useState(String(line.qty));
  const [price, setPrice] = useState((line.unitMinor / 100).toString());
  const [disc, setDisc] = useState(line.discountMinor ? (line.discountMinor / 100).toString() : "");
  const [note, setNote] = useState(line.note ?? "");
  const q = Math.max(1, parseInt(norm(qty) || "1", 10) || 1);
  const unit = price.trim() === "" ? line.unitMinor : toMinor(price);
  const dm = Math.min(unit * q, toMinor(disc));
  return (
    <Modal open onClose={onClose} title={line.name}>
      <div className="space-y-3">
        <p className="truncate text-sm text-slate-500">{line.label ? `${line.label} · ` : ""}{t("term.stockNow")} {line.stock}</p>
        <div className="grid grid-cols-2 gap-2">
          <label className="block min-w-0 text-sm font-semibold">{t("term.qty")}
            <div className="mt-1 flex items-center rounded-xl border" dir="ltr">
              <button type="button" className="h-11 w-11 shrink-0 text-lg" onClick={() => setQty(String(Math.max(1, q - 1)))}>−</button>
              <input inputMode="numeric" value={qty} onChange={(e) => setQty(e.target.value.replace(/[^\d٠-٩]/g, ""))} className="w-full min-w-0 bg-transparent text-center font-bold outline-none" />
              <button type="button" className="h-11 w-11 shrink-0 text-lg" onClick={() => setQty(String(q + 1))}>+</button>
            </div>
          </label>
          <label className="block min-w-0 text-sm font-semibold">{t("term.unitPrice")}<Input dir="ltr" inputMode="decimal" value={price} onChange={(e) => setPrice(e.target.value)} className="mt-1 min-w-0" /></label>
        </div>
        <label className="block text-sm font-semibold">{t("term.lineDiscount")}<Input dir="ltr" inputMode="decimal" value={disc} onChange={(e) => setDisc(e.target.value)} placeholder="0" className="mt-1" /></label>
        <label className="block text-sm font-semibold">{t("term.note")}<Input value={note} onChange={(e) => setNote(e.target.value)} className="mt-1" /></label>
        {unit !== line.unitMinor && <p className="text-xs text-amber-700">{t("term.priceChangedNote")}</p>}
        <div className="flex justify-between font-bold"><span>{t("term.total")}</span><span className="tabular-nums">{money(unit * q - dm, currency, locale)}</span></div>
        <div className="flex gap-2">
          <Button variant="danger" onClick={onRemove}>{t("common.delete")}</Button>
          <Button className="flex-1" onClick={() => onSave({ ...line, qty: q, unitMinor: unit, discountMinor: dm, note: note.trim() || null })}>{t("common.save")}</Button>
        </div>
      </div>
    </Modal>
  );
}

function Center({ children }: { children: React.ReactNode }) { return <div className="flex min-w-0 flex-1 items-center justify-center p-4" style={{ minHeight: "60dvh" }}>{children}</div>; }
function Chip({ children, on, onClick }: { children: React.ReactNode; on: boolean; onClick: () => void }) { return <button type="button" onClick={onClick} className={`max-w-[11rem] truncate rounded-full px-3 py-1.5 text-xs font-semibold ${on ? "bg-brand text-white" : "bg-white text-slate-700 ring-1 ring-slate-200"}`}>{children}</button>; }
