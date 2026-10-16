/** Egyptian numbers are stored as +201xxxxxxxxx but read and typed as 01xxxxxxxxx: lists and details show that. Calls and WhatsApp still use the full number. */
export function localPhone(p?: string | null): string {
  const s = (p ?? "").trim();
  const m = /^\+20(\d{10})$/.exec(s);
  return m ? `0${m[1]}` : s;
}
