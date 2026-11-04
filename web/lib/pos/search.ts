/** Till search that reads Arabic the way people type it: أ/إ/آ = ا, ة = ه, ى = ي, no tashkeel, Arabic digits = Latin; every word must match, in any order. */

const DIGITS = "٠١٢٣٤٥٦٧٨٩";

export function norm(s: string | null | undefined): string {
  if (!s) return "";
  return s
    .replace(/[ً-ٰٟـ]/g, "")
    .replace(/[أإآ]/g, "ا").replace(/ة/g, "ه").replace(/ى/g, "ي")
    .replace(/[٠-٩]/g, (d) => String(DIGITS.indexOf(d)))
    .toLowerCase()
    .replace(/\s+/g, " ")
    .trim();
}

/** The words of a search (empty = everything matches). */
export function words(q: string): string[] { return norm(q).split(" ").filter(Boolean); }

/** Does every word appear in one of these texts? */
export function matches(ws: string[], ...texts: (string | null | undefined)[]): boolean {
  if (ws.length === 0) return true;
  const hay = texts.map(norm).join(" ");
  return ws.every((w) => hay.includes(w));
}
