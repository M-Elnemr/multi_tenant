/** Egypt's 27 governorates. Codes are stable identifiers (also used by the backend for shipping zones). */
export const GOVERNORATES: { code: string; ar: string; en: string }[] = [
  { code: "CAI", ar: "القاهرة", en: "Cairo" },
  { code: "GIZ", ar: "الجيزة", en: "Giza" },
  { code: "ALX", ar: "الإسكندرية", en: "Alexandria" },
  { code: "QLY", ar: "القليوبية", en: "Qalyubia" },
  { code: "SHR", ar: "الشرقية", en: "Sharqia" },
  { code: "DKH", ar: "الدقهلية", en: "Dakahlia" },
  { code: "GHR", ar: "الغربية", en: "Gharbia" },
  { code: "MNF", ar: "المنوفية", en: "Monufia" },
  { code: "BHR", ar: "البحيرة", en: "Beheira" },
  { code: "KFS", ar: "كفر الشيخ", en: "Kafr El Sheikh" },
  { code: "DMT", ar: "دمياط", en: "Damietta" },
  { code: "PSD", ar: "بورسعيد", en: "Port Said" },
  { code: "ISM", ar: "الإسماعيلية", en: "Ismailia" },
  { code: "SUZ", ar: "السويس", en: "Suez" },
  { code: "FYM", ar: "الفيوم", en: "Faiyum" },
  { code: "BNS", ar: "بني سويف", en: "Beni Suef" },
  { code: "MNY", ar: "المنيا", en: "Minya" },
  { code: "AST", ar: "أسيوط", en: "Asyut" },
  { code: "SHG", ar: "سوهاج", en: "Sohag" },
  { code: "QNA", ar: "قنا", en: "Qena" },
  { code: "LXR", ar: "الأقصر", en: "Luxor" },
  { code: "ASN", ar: "أسوان", en: "Aswan" },
  { code: "RSS", ar: "البحر الأحمر", en: "Red Sea" },
  { code: "WAD", ar: "الوادي الجديد", en: "New Valley" },
  { code: "MTR", ar: "مطروح", en: "Matrouh" },
  { code: "NSN", ar: "شمال سيناء", en: "North Sinai" },
  { code: "SSN", ar: "جنوب سيناء", en: "South Sinai" },
];

export function governorateName(code: string | null | undefined, locale: string): string {
  const g = GOVERNORATES.find((x) => x.code === code);
  return g ? (locale === "ar" ? g.ar : g.en) : "";
}
