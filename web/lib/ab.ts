import { api } from "@/lib/client";

const KEY = "ab:v1";

type Stored = { id: string; variant: "A" | "B"; clientKey: string };

function read(): Stored | null {
  try { const raw = localStorage.getItem(KEY); return raw ? (JSON.parse(raw) as Stored) : null; } catch { return null; }
}

function clientKey(): string {
  try {
    let k = localStorage.getItem("cart:key") ?? "";
    if (k.length < 8) { k = (crypto.randomUUID?.() ?? String(Math.random()).slice(2) + String(Date.now())).replace(/[^A-Za-z0-9_-]/g, "").slice(0, 40); localStorage.setItem("cart:key", k); }
    return k;
  } catch { return ""; }
}

/** The visitor's side of the running announcement test: 50/50 by browser, remembered, counted once. Returns the text to show, or null when no test runs. */
export async function pickAnnouncement(): Promise<string | null> {
  const t = await api<{ id?: string; a?: string; b?: string }>("shop/experiments/active").catch(() => null);
  if (!t?.id || !t.a || !t.b) { try { localStorage.removeItem(KEY); } catch { /* storage unavailable */ } return null; }
  let s = read();
  if (!s || s.id !== t.id) {
    const key = clientKey();
    if (!key) return null;
    s = { id: t.id, variant: Math.random() < 0.5 ? "A" : "B", clientKey: key };
    try { localStorage.setItem(KEY, JSON.stringify(s)); } catch { return null; }
    void api(`shop/experiments/${t.id}/event`, { body: { variant: s.variant, type: "view", clientKey: s.clientKey } }).catch(() => null);
  }
  return s.variant === "A" ? t.a : t.b;
}

/** After an order: tells the shop which text this buyer saw. */
export function reportOrder(orderNumber: string) {
  const s = read();
  if (!s) return;
  void api(`shop/experiments/${s.id}/event`, { body: { variant: s.variant, type: "order", clientKey: s.clientKey, orderNumber } }).catch(() => null);
}
