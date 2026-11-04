/** A cashier's PIN, checked on the terminal with no internet: PBKDF2-SHA256 with the salt and iterations the server used (WebCrypto). */
import type { Cashier } from "./db";

function hex(buf: ArrayBuffer): string { return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join(""); }
function fromHex(h: string): Uint8Array { const out = new Uint8Array(h.length / 2); for (let i = 0; i < out.length; i++) out[i] = parseInt(h.slice(i * 2, i * 2 + 2), 16); return out; }

export async function pinHash(pin: string, saltHex: string, iterations: number): Promise<string> {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(pin), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", salt: fromHex(saltHex) as BufferSource, iterations, hash: "SHA-256" }, key, 256);
  return hex(bits);
}

/** The cashier whose PIN this is (PINs are unique in a shop), or null. */
export async function cashierByPin(pin: string, cashiers: Cashier[]): Promise<Cashier | null> {
  if (!/^\d{4,6}$/.test(pin)) return null;
  for (const c of cashiers) {
    if (!c.hasPin || !c.salt || !c.iterations || !c.pinHash) continue;
    if ((await pinHash(pin, c.salt, c.iterations)) === c.pinHash) return c;
  }
  return null;
}
