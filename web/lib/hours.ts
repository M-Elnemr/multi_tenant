/** Weekly opening hours, stored as { sat: { open: "09:00", close: "22:00", closed: false }, ... }. Egypt's week starts on Saturday. */
export type DayHours = { open: string; close: string; closed: boolean };
export type WeekHours = Record<string, DayHours>;
export const DAYS = ["sat", "sun", "mon", "tue", "wed", "thu", "fri"] as const;

export function defaultHours(): WeekHours {
  return Object.fromEntries(DAYS.map((d) => [d, { open: "10:00", close: "22:00", closed: d === "fri" }]));
}

/** "10:00" -> "10:00 AM" / "م" style is left to the locale; keep 24h numerals simple and unambiguous. */
export function hasHours(h: WeekHours | undefined | null): boolean {
  return !!h && DAYS.some((d) => h[d] && !h[d].closed);
}
