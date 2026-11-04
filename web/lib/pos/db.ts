/**
 * The till terminal's own copy of everything it needs, in the browser's IndexedDB: the branch catalog, cashiers, shop settings, its sales, held carts, stock counts and the
 * queue of actions still to send (the outbox). The terminal reads and writes only here, so it sells the same with or without internet.
 */

export type CatalogItem = {
  variantId: string; productId: string; name: string; label: string | null; sku: string; barcode: string | null;
  priceMinor: number; currency: string; active: boolean; stock: number; categoryAr: string; categoryEn: string; image: string | null;
};
export type Cashier = { userId: string; name: string; hasPin: boolean; salt: string | null; iterations: number | null; pinHash: string | null; canRefund: boolean; canCash: boolean; canManage: boolean; canCount: boolean };
export type Shop = { name: string; address: string; taxId: string; phone: string; vatIncluded: boolean; vatPercent: number; receiptSize: "58" | "80" | "A4"; receiptFooter: string; posMaxDiscountPct: number; timezone: string; currency: string };
export type DeviceInfo = { id: string; name: string; code: string; branchId: string; branchName: string; receiptSize: "58" | "80" | "A4" | null; lastSeq: number; lastInvoice: number };
export type OpType = "SHIFT_OPEN" | "CASH_MOVE" | "SALE" | "RETURN" | "SHIFT_CLOSE" | "COUNT";
export type Op = { seq: number; opId: string; type: OpType; at: string; cashierId: string; payload: Record<string, unknown>; state: "pending" | "failed"; error?: string };
export type Payment = { method: string; amountMinor: number };
export type InvoiceLine = { variantId: string; name: string; label: string | null; sku?: string; qty: number; unitMinor: number; discountMinor?: number; returned: number; note?: string | null };
export type Invoice = {
  id: string; number: string; at: string; cashierId: string | null; cashierName?: string; shiftId?: string;
  lines: InvoiceLine[]; subtotalMinor: number; discountMinor: number; totalMinor: number; vatMinor: number;
  payments: Payment[]; tenderedMinor?: number; changeMinor?: number; customerName?: string; phone?: string; currency: string;
  synced: boolean; source: "local" | "server"; status?: string;
};
export type CashMove = { id: string; kind: "PAY_IN" | "PAY_OUT" | "DROP"; amountMinor: number; reason: string; at: string };
export type ReturnRec = { id: string; invoiceId: string; number: string; at: string; refundMinor: number; method: string; lines: { variantId: string; name: string; qty: number; unitMinor: number }[] };
export type Shift = { id: string; cashierId: string; cashierName: string; openedAt: string; openingFloatMinor: number; moves: CashMove[]; returns: ReturnRec[] };
export type CartLine = { variantId: string; name: string; label: string | null; sku: string; unitMinor: number; qty: number; discountMinor: number; note: string | null; stock: number };
export type Held = { id: string; at: string; lines: CartLine[]; cartDiscountMinor: number; note: string; cashierName: string };
export type Count = { id: string; name: string; status: "COUNTING" | "REVIEW"; createdAt: string; lines: Record<string, { expected: number; counted: number; at: string }>; sent: boolean };

const NAME = "pos-terminal";
const VERSION = 1;
const STORES = ["kv", "catalog", "outbox", "invoices", "held", "counts"] as const;
type Store = (typeof STORES)[number];

let opening: Promise<IDBDatabase> | null = null;

function open(): Promise<IDBDatabase> {
  if (opening) return opening;
  opening = new Promise((resolve, reject) => {
    const req = indexedDB.open(NAME, VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains("kv")) db.createObjectStore("kv");
      if (!db.objectStoreNames.contains("catalog")) {
        const s = db.createObjectStore("catalog", { keyPath: "variantId" });
        s.createIndex("barcode", "barcode");
        s.createIndex("sku", "sku");
      }
      if (!db.objectStoreNames.contains("outbox")) db.createObjectStore("outbox", { keyPath: "seq" });
      if (!db.objectStoreNames.contains("invoices")) {
        const s = db.createObjectStore("invoices", { keyPath: "id" });
        s.createIndex("number", "number");
        s.createIndex("at", "at");
      }
      if (!db.objectStoreNames.contains("held")) db.createObjectStore("held", { keyPath: "id" });
      if (!db.objectStoreNames.contains("counts")) db.createObjectStore("counts", { keyPath: "id" });
    };
    req.onsuccess = () => {
      req.result.onversionchange = () => { req.result.close(); opening = null; };
      resolve(req.result);
    };
    req.onerror = () => { opening = null; reject(req.error); };
  });
  return opening;
}

function done<T>(req: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => { req.onsuccess = () => resolve(req.result); req.onerror = () => reject(req.error); });
}

/** Runs {@code fn} in one transaction over these stores; it commits as a whole or not at all (a sale and its outbox action are never half-saved). */
export async function tx<T>(stores: Store[], mode: IDBTransactionMode, fn: (s: Record<Store, IDBObjectStore>) => Promise<T> | T): Promise<T> {
  const db = await open();
  const t = db.transaction(stores, mode);
  const map = Object.fromEntries(stores.map((n) => [n, t.objectStore(n)])) as Record<Store, IDBObjectStore>;
  const finished = new Promise<void>((resolve, reject) => { t.oncomplete = () => resolve(); t.onerror = () => reject(t.error); t.onabort = () => reject(t.error ?? new Error("aborted")); });
  const out = await fn(map);
  await finished;
  return out;
}

export const req = done;

export async function kvGet<T>(key: string): Promise<T | undefined> { return tx(["kv"], "readonly", (s) => done(s.kv.get(key)) as Promise<T | undefined>); }
export async function kvSet(key: string, value: unknown): Promise<void> { await tx(["kv"], "readwrite", (s) => { s.kv.put(value, key); }); }
export async function all<T>(store: Store): Promise<T[]> { return tx([store], "readonly", (s) => done(s[store].getAll()) as Promise<T[]>); }
export async function get<T>(store: Store, key: IDBValidKey): Promise<T | undefined> { return tx([store], "readonly", (s) => done(s[store].get(key)) as Promise<T | undefined>); }
export async function put(store: Store, value: unknown): Promise<void> { await tx([store], "readwrite", (s) => { s[store].put(value); }); }
export async function del(store: Store, key: IDBValidKey): Promise<void> { await tx([store], "readwrite", (s) => { s[store].delete(key); }); }
export async function count(store: Store): Promise<number> { return tx([store], "readonly", (s) => done(s[store].count())); }

/** A product by its scanned code: barcode, then SKU, then the v= of a label's web address. */
export async function byCode(raw: string): Promise<CatalogItem | undefined> {
  let code = raw.trim();
  if (/^https?:\/\//.test(code)) {
    try { code = new URL(code).searchParams.get("v") ?? ""; } catch { code = ""; }
  }
  if (!code) return undefined;
  return tx(["catalog"], "readonly", async (s) => {
    const b = (await done(s.catalog.index("barcode").get(code))) as CatalogItem | undefined;
    if (b?.active) return b;
    const k = (await done(s.catalog.index("sku").get(code))) as CatalogItem | undefined;
    return k?.active ? k : undefined;
  });
}

/** Wipes the terminal (when it is unregistered); the outbox is kept unless {@code everything} (only after it was all sent). */
export async function wipe(everything: boolean): Promise<void> {
  const stores: Store[] = everything ? [...STORES] : ["kv", "catalog", "invoices", "held", "counts"];
  await tx(stores, "readwrite", async (s) => {
    const seq = everything ? undefined : await done(s.kv.get("seq"));   // actions still waiting keep their order: new ones are numbered after them
    for (const n of stores) s[n].clear();
    if (seq !== undefined) s.kv.put(seq, "seq");
  });
}

export function uuid(): string {
  const c: Crypto = globalThis.crypto;
  if (typeof c.randomUUID === "function") return c.randomUUID();   // missing on plain-http pages; the fallback below is just as random
  const b = c.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40; b[8] = (b[8] & 0x3f) | 0x80;
  const h = [...b].map((x) => x.toString(16).padStart(2, "0")).join("");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}
