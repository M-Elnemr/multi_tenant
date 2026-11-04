/**
 * The till's link to the server. Every action is saved locally first, together with its place in the outbox (one IndexedDB transaction), so nothing is lost if the tab closes
 * or the internet drops. When the server can be reached, the outbox is sent in order; the server applies each action once (its id is unique), so a resend after a lost answer
 * is harmless. The catalog, cashiers and settings are refreshed in the background.
 */
import { all, kvGet, req, tx, uuid, type CatalogItem, type Cashier, type DeviceInfo, type Invoice, type Op, type OpType, type Shop } from "./db";

export type Status = {
  ready: boolean; registered: boolean | null; online: boolean; syncing: boolean;
  pending: number; failed: number; flagged: number; lastSync: string | null; lastRefresh: string | null; error: string | null;
};

let status: Status = { ready: false, registered: null, online: true, syncing: false, pending: 0, failed: 0, flagged: 0, lastSync: null, lastRefresh: null, error: null };
const listeners = new Set<() => void>();
const channel: BroadcastChannel | null = typeof BroadcastChannel !== "undefined" ? new BroadcastChannel("pos-terminal") : null;

function set(p: Partial<Status>) { status = { ...status, ...p }; listeners.forEach((l) => l()); }
export function subscribe(cb: () => void) { listeners.add(cb); return () => { listeners.delete(cb); }; }
export function getStatus() { return status; }
/** Other tabs of this terminal hear about every local change (and reload what they show). */
export function onChange(cb: () => void) {
  const h = () => cb();
  channel?.addEventListener("message", h);
  window.addEventListener("pos:changed", h);
  return () => { channel?.removeEventListener("message", h); window.removeEventListener("pos:changed", h); };
}
export function changed() { channel?.postMessage("changed"); window.dispatchEvent(new Event("pos:changed")); }

class TerminalError extends Error { constructor(public status: number, message: string) { super(message); } }

/** The terminal's own calls: never redirect to a login page (the terminal is authenticated by its device cookie, not by a staff session). */
async function call<T>(path: string, body?: unknown): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`/api/bff/${path}`, { method: body === undefined ? "GET" : "POST", headers: body === undefined ? {} : { "Content-Type": "application/json" }, body: body === undefined ? undefined : JSON.stringify(body), cache: "no-store" });
  } catch {
    throw new TerminalError(0, "offline");
  }
  const text = await res.text();
  let data: unknown = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = null; }
  if (!res.ok) throw new TerminalError(res.status, (data as { message?: string } | null)?.message ?? `HTTP ${res.status}`);
  return data as T;
}

// ---- reading the local copy -----------------------------------------------------------------------------------------------

export const device = () => kvGet<DeviceInfo>("device");
export const shop = () => kvGet<Shop>("shop");
export const cashiers = async () => (await kvGet<Cashier[]>("cashiers")) ?? [];
export const methods = async () => (await kvGet<string[]>("methods")) ?? ["CASH", "CARD_POS", "TRANSFER"];
export const catalog = () => all<CatalogItem>("catalog");

async function counts() {
  const ops = await all<Op>("outbox");
  set({ pending: ops.filter((o) => o.state === "pending").length, failed: ops.filter((o) => o.state === "failed").length });
}

// ---- refreshing the copy from the server --------------------------------------------------------------------------------

type ServerInvoice = { id: string; orderNumber: string; createdAt: string; clientCreatedAt: string | null; totalMinor: number; discountMinor: number; currency: string; status: string;
  customerNameSnapshot: string | null; customerPhoneSnapshot: string | null; cashierId: string | null;
  items: { variantId: string | null; name: string; label: string | null; sku: string; qty: number; unitMinor: number; returned: number }[]; payments: { method: string; amountMinor: number }[] };
type Boot = { serverTime: string; full: boolean; device: DeviceInfo; shop: Shop; methods: string[]; cashiers: Cashier[]; catalog: CatalogItem[]; invoices: ServerInvoice[];
  openShift: { id: string; cashierId: string; openedAt: string; openingFloatMinor: number } | null };

const FULL_EVERY = 6 * 60 * 60 * 1000;

/** Fetches what changed (or everything) and merges it into the local copy. Pieces of sales still in the outbox stay taken off the shown stock. */
export async function refresh(forceFull = false): Promise<boolean> {
  const since = await kvGet<string>("serverTime");
  const lastFull = (await kvGet<number>("lastFull")) ?? 0;
  const full = forceFull || !since || Date.now() - lastFull > FULL_EVERY;
  let b: Boot;
  try {
    b = await call<Boot>(`store/pos/terminal/bootstrap${full ? "" : `?since=${encodeURIComponent(since!)}`}`);
  } catch (e) {
    const s = e instanceof TerminalError ? e.status : 0;
    if (s === 401 || s === 403) set({ registered: false, online: true, error: null });
    else if (s === 0) set({ online: false });
    return false;
  }
  await tx(["kv", "catalog", "invoices", "outbox"], "readwrite", async (s) => {
    s.kv.put(b.device, "device"); s.kv.put(b.shop, "shop"); s.kv.put(b.cashiers, "cashiers"); s.kv.put(b.methods, "methods"); s.kv.put(b.serverTime, "serverTime");
    if (full) s.kv.put(Date.now(), "lastFull");
    const seq = ((await req(s.kv.get("seq"))) as number | undefined) ?? 0;
    if (b.device.lastSeq > seq) s.kv.put(b.device.lastSeq, "seq");
    const inv = ((await req(s.kv.get("invoiceSeq"))) as number | undefined) ?? 0;
    if (b.device.lastInvoice > inv) s.kv.put(b.device.lastInvoice, "invoiceSeq");
    // what is still waiting to be sent has not reached the server's stock yet
    const pendingDelta: Record<string, number> = {};
    for (const op of (await req(s.outbox.getAll())) as Op[]) {
      const items = (op.payload.items as { variantId: string; qty: number }[] | undefined) ?? [];
      if (op.type === "SALE") for (const it of items) pendingDelta[it.variantId] = (pendingDelta[it.variantId] ?? 0) - it.qty;
      if (op.type === "RETURN") for (const it of items) pendingDelta[it.variantId] = (pendingDelta[it.variantId] ?? 0) + it.qty;
    }
    if (full) s.catalog.clear();
    for (const it of b.catalog) s.catalog.put({ ...it, stock: it.stock + (pendingDelta[it.variantId] ?? 0) });
    const locals = (await req(s.invoices.getAll())) as Invoice[];
    const unsynced = new Set(locals.filter((x) => !x.synced).map((x) => x.id));
    for (const x of b.invoices) {
      if (unsynced.has(x.id)) continue;
      s.invoices.put({
        id: x.id, number: x.orderNumber, at: x.clientCreatedAt ?? x.createdAt, cashierId: x.cashierId, currency: x.currency, status: x.status,
        lines: (x.items ?? []).filter((i) => i.variantId).map((i) => ({ variantId: i.variantId!, name: i.name, label: i.label, sku: i.sku, qty: i.qty, unitMinor: Number(i.unitMinor), returned: Number(i.returned) })),
        subtotalMinor: x.totalMinor + x.discountMinor, discountMinor: x.discountMinor, totalMinor: x.totalMinor, vatMinor: 0,
        payments: x.payments ?? [], customerName: x.customerNameSnapshot ?? undefined, phone: x.customerPhoneSnapshot ?? undefined, synced: true, source: "server",
      } satisfies Invoice);
    }
    const old = Date.now() - 35 * 24 * 60 * 60 * 1000;
    for (const x of locals) if (x.synced && new Date(x.at).getTime() < old) s.invoices.delete(x.id);
    // a shift the server knows is open on this terminal (the browser's copy was lost): carried on, the cashier signs in with their PIN
    if (b.openShift && !((await req(s.kv.get("shift"))) as unknown)) {
      const who = b.cashiers.find((c) => c.userId === b.openShift!.cashierId);
      s.kv.put({ id: b.openShift.id, cashierId: b.openShift.cashierId, cashierName: who?.name ?? "", openedAt: b.openShift.openedAt, openingFloatMinor: Number(b.openShift.openingFloatMinor), moves: [], returns: [] }, "shift");
    }
  });
  set({ registered: true, online: true, lastRefresh: new Date().toISOString(), error: null });
  changed();
  return true;
}

// ---- queueing an action ---------------------------------------------------------------------------------------------------

/**
 * Saves an action and whatever local change goes with it in one transaction (a sale and its outbox entry are never half-saved). {@code more} writes the local side
 * (the invoice, the stock shown, the shift) with the same transaction.
 */
export async function enqueue(type: OpType, cashierId: string, payload: Record<string, unknown>,
                              more?: (s: Record<"kv" | "catalog" | "outbox" | "invoices" | "held" | "counts", IDBObjectStore>, seq: number) => Promise<Record<string, unknown> | void> | Record<string, unknown> | void): Promise<Op> {
  const op = await tx(["kv", "catalog", "outbox", "invoices", "held", "counts"], "readwrite", async (s) => {
    const seq = (((await req(s.kv.get("seq"))) as number | undefined) ?? 0) + 1;
    s.kv.put(seq, "seq");
    const extra = more ? await more(s, seq) : undefined;   // e.g. the invoice number reserved with the sale
    const o: Op = { seq, opId: uuid(), type, at: new Date().toISOString(), cashierId, payload: { ...payload, ...(extra ?? {}) }, state: "pending" };
    s.outbox.put(o);
    return o;
  });
  await counts();
  changed();
  void push();
  return op;
}

/** The next invoice number of this terminal (POS-A1-000124), reserved in the same transaction as the sale. */
export async function nextInvoiceNumber(s: Record<"kv", IDBObjectStore>): Promise<string> {
  const n = (((await req(s.kv.get("invoiceSeq"))) as number | undefined) ?? 0) + 1;
  s.kv.put(n, "invoiceSeq");
  const d = (await req(s.kv.get("device"))) as DeviceInfo | undefined;
  return `POS-${d?.code ?? "X"}-${String(n).padStart(6, "0")}`;
}

// ---- sending the outbox ---------------------------------------------------------------------------------------------------

let running = false;
let backoff = 0;
let retryTimer: ReturnType<typeof setTimeout> | null = null;

type Result = { opId: string; status: "APPLIED" | "FLAGGED" | "FAILED" | "RETRY"; error?: string; flags?: string[]; duplicate?: boolean };

async function pushNow(): Promise<void> {
  if (running) return;
  running = true;
  set({ syncing: true });
  try {
    for (let round = 0; round < 50; round++) {
      const pending = (await all<Op>("outbox")).filter((o) => o.state === "pending").sort((a, b) => a.seq - b.seq).slice(0, 100);
      if (pending.length === 0) break;
      let res: { results: Result[] };
      try {
        res = await call<{ results: Result[] }>("store/pos/sync/push", { ops: pending.map(({ opId, seq, type, at, cashierId, payload }) => ({ opId, seq, type, at, cashierId, payload })) });
      } catch (e) {
        const s = e instanceof TerminalError ? e.status : 0;
        if (s === 401 || s === 403) set({ registered: false, online: true });
        else set({ online: s !== 0, error: s === 0 ? null : (e as Error).message });
        scheduleRetry();
        return;
      }
      const byId = new Map(res.results.map((r) => [r.opId, r]));
      let stop = false, flagged = 0;
      await tx(["outbox", "invoices"], "readwrite", async (s) => {
        for (const op of pending) {
          const r = byId.get(op.opId);
          if (!r || r.status === "RETRY") { stop = true; continue; }
          if (r.status === "FAILED") { s.outbox.put({ ...op, state: "failed", error: r.error ?? "failed" }); continue; }
          if (r.status === "FLAGGED") flagged++;
          s.outbox.delete(op.seq);
          if (op.type === "SALE") {
            const inv = (await req(s.invoices.get(op.payload.orderId as string))) as Invoice | undefined;
            if (inv) s.invoices.put({ ...inv, synced: true });
          }
        }
      });
      set({ online: true, lastSync: new Date().toISOString(), error: null, flagged: status.flagged + flagged });
      backoff = 0;
      await counts();
      changed();
      if (stop) { scheduleRetry(); return; }
    }
  } finally {
    running = false;
    set({ syncing: false });
  }
}

function scheduleRetry() {
  if (retryTimer) return;
  backoff = Math.min(60_000, backoff ? backoff * 2 : 5_000);
  retryTimer = setTimeout(() => { retryTimer = null; void push(); }, backoff);
}

/** Sends the outbox now (one tab at a time when the browser supports it). */
export async function push(): Promise<void> {
  const locks = (navigator as Navigator & { locks?: { request: (n: string, o: { ifAvailable: boolean }, cb: (l: unknown) => Promise<void>) => Promise<void> } }).locks;
  if (locks) await locks.request("pos-sync", { ifAvailable: true }, async (lock) => { if (lock) await pushNow(); });
  else await pushNow();
}

/** Failed actions: kept for the manager (they are sync issues on the server too); the cashier may dismiss them from this screen once seen. */
export async function failedOps(): Promise<Op[]> { return (await all<Op>("outbox")).filter((o) => o.state === "failed"); }
export async function dismissFailed(seq: number): Promise<void> { await tx(["outbox"], "readwrite", (s) => { s.outbox.delete(seq); }); await counts(); changed(); }

// ---- starting -------------------------------------------------------------------------------------------------------------

let started = false;

/** Starts the terminal's background work: refresh now, then send every 20 s when something waits, refresh changes every 5 minutes, and at once when the internet comes back. */
export async function start(): Promise<void> {
  if (started) return;
  started = true;
  set({ online: navigator.onLine });
  await counts();
  const hasCopy = !!(await device());
  set({ ready: hasCopy, registered: hasCopy ? true : null });
  void refresh().then((ok) => { set({ ready: true }); if (ok) void push(); });
  window.addEventListener("online", () => { set({ online: true }); void refresh().then(() => push()); });
  window.addEventListener("offline", () => set({ online: false }));
  document.addEventListener("visibilitychange", () => { if (document.visibilityState === "visible") void push(); });
  setInterval(() => { if (status.pending > 0) void push(); }, 20_000);
  setInterval(() => { if (navigator.onLine) void refresh(); }, 5 * 60_000);
}

/** Registers this browser as a terminal (a manager signed in on it, online). */
export async function register(name: string, branchId?: string): Promise<void> {
  await call("store/pos/devices", { name, branchId });
  await refresh(true);
}

/** This browser stops being a terminal: only once nothing is left to send. */
export async function forget(): Promise<void> {
  await call("store/pos/terminal/forget", {});
}

/** Asks the server for a sale to return that this terminal does not have (another branch, a web order). */
export async function findRemote(q: string) { return call<Record<string, unknown>>(`store/pos/terminal/invoice?q=${encodeURIComponent(q)}`); }

/** Keeps the till's page and every script and style it loaded in the service worker's cache, so it opens with no internet. */
export function keepForOffline() {
  const sw = navigator.serviceWorker;
  if (!sw || process.env.NODE_ENV !== "production") return;
  const send = () => {
    const urls = new Set<string>(["/pos"]);
    for (const e of performance.getEntriesByType("resource")) {
      try { const u = new URL(e.name); if (u.origin === location.origin && u.pathname.startsWith("/_next/static/")) urls.add(u.pathname + u.search); } catch { /* ignore */ }
    }
    document.querySelectorAll<HTMLScriptElement | HTMLLinkElement>("script[src], link[rel=stylesheet][href], link[rel=preload][href]").forEach((el) => {
      const raw = el instanceof HTMLScriptElement ? el.src : el.href;
      try { const u = new URL(raw); if (u.origin === location.origin) urls.add(u.pathname + u.search); } catch { /* ignore */ }
    });
    sw.controller?.postMessage({ type: "keep", cache: "pos", urls: [...urls] });
  };
  if (sw.controller) send();
  else sw.addEventListener("controllerchange", send, { once: true });
  sw.register("/sw.js").catch(() => null);
}
