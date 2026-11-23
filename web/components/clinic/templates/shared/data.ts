import type { Locale } from "@/lib/i18n";
import { DAYS, hasHours, type WeekHours } from "@/lib/hours";
import { nowInClinic, openStatus, type ClinicDoctor, type ClinicSite } from "@/lib/clinic-site";
import type { T } from "./kit";

export const mainPhone = (p: ClinicSite) => p.phone || p.extraPhones?.[0] || p.branches.find((b) => b.phone)?.phone || "";
export const directionsUrl = (p: ClinicSite) =>
  p.mapsUrl || p.branches.find((b) => b.mapsUrl)?.mapsUrl || (p.addressText ? `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(p.addressText)}` : "");

export type Spec = { key: string; name: string; doctors: ClinicDoctor[] };
type DS = ClinicDoctor["specialties"][number];
const specName = (s: DS, d: ClinicDoctor, locale: Locale) => (s.code === "other" && d.otherSpecialty ? d.otherSpecialty : locale === "ar" ? s.nameAr : s.nameEn);
const specKey = (s: DS, d: ClinicDoctor, locale: Locale) => (s.code === "other" ? `o-${encodeURIComponent(specName(s, d, locale))}` : s.code);

/** One entry per specialty that really has a doctor; "other" specialties are grouped by the name the clinic typed. */
export function specialtiesOf(p: ClinicSite, locale: Locale): Spec[] {
  const m = new Map<string, Spec>();
  for (const d of p.doctors) for (const s of d.specialties) {
    const key = specKey(s, d, locale);
    const cur = m.get(key) ?? { key, name: specName(s, d, locale), doctors: [] };
    if (!cur.doctors.includes(d)) cur.doctors.push(d);
    m.set(key, cur);
  }
  return [...m.values()];
}
export const specNames = (d: ClinicDoctor, locale: Locale) => d.specialties.map((s) => specName(s, d, locale));

/** First letters of a name, ignoring titles, for a photo placeholder. */
export const initials = (name: string) => name.replace(/^(dr\.?|doctor|د\.|الدكتور|الدكتورة)\s*/i, "").trim().split(/\s+/).slice(0, 2).map((w) => [...w][0] ?? "").join("");

export type HourRow = { day: (typeof DAYS)[number]; closed: boolean; open: string; close: string };
export const hourRows = (h: WeekHours | undefined | null): HourRow[] => (hasHours(h) ? DAYS.map((d) => ({ day: d, closed: !h?.[d] || h[d].closed, open: h?.[d]?.open ?? "", close: h?.[d]?.close ?? "" })) : []);

export const maxYears = (p: ClinicSite) => (p.establishedYear ? new Date().getFullYear() - p.establishedYear : Math.max(0, ...p.doctors.map((d) => d.yearsExperience ?? 0)));
/** "Open now · until 22:00" / "Closed now · opens Sunday at 10:00", from the real weekly hours; null when the clinic set none (or is marked closed). */
export function statusLine(p: ClinicSite, t: T): { open: boolean; text: string } | null {
  const st = p.isOpen === false ? null : openStatus(p.workingHours);
  if (!st || st.state === "unknown") return null;
  if (st.state === "open") return { open: true, text: `${t("csite.openNow")} · ${t("csite.closesAt", { time: st.until })}` };
  const next = st.next ? (st.next.today ? t("csite.opensToday", { time: st.next.time }) : t("csite.opensAt", { day: t(`day.${st.next.day}`), time: st.next.time })) : "";
  return { open: false, text: next ? `${t("csite.closedNow")} · ${next}` : t("csite.closedNow") };
}
export const todayKey = () => nowInClinic().day;
export const hoursText = (h: HourRow, t: T) => (h.closed ? t("cn.closedDay") : `${h.open} – ${h.close}`);
export const lowestFee =(p: ClinicSite) => p.services.filter((s) => s.priceMinor).sort((a, b) => (a.priceMinor ?? 0) - (b.priceMinor ?? 0))[0];
