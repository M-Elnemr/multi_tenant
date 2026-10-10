import { DAYS, hasHours, type WeekHours } from "@/lib/hours";

export type ClinicDoctor = {
  id: string; displayName: string; bio?: string; publicPhone?: string; gender?: string; consultationDurationMinutes: number; defaultAppointmentFeeMinor?: number | null; currency?: string;
  otherSpecialty?: string | null; specialties: { code: string; nameAr: string; nameEn: string }[];
  profileImageFileId?: string | null; yearsExperience?: number | null; qualifications?: string; languages?: string[];
};
export type ClinicService = { id: string; name: string; description?: string; durationMinutes: number; priceMinor?: number | null; currency: string; visitType?: string | null };
export type ClinicBranch = {
  id: string; name: string; addressLine1?: string; addressLine2?: string; city?: string; district?: string; phone?: string; whatsapp?: string; mapsUrl?: string; landmark?: string;
  workingHours?: WeekHours; latitude?: number | null; longitude?: number | null;
};
export type ClinicSite = {
  clinicName: string; about?: string; phone?: string; email?: string; addressText?: string; bookingEnabled: boolean; patientPortalEnabled?: boolean; queueCount?: number;
  tagline?: string; whatsapp?: string; extraPhones?: string[]; facebookUrl?: string; instagramUrl?: string; tiktokUrl?: string; websiteUrl?: string; mapsUrl?: string;
  workingHours?: WeekHours; coverFileId?: string | null; gallery?: string[]; announcement?: string; isOpen?: boolean; closedMessage?: string; insurance?: string[];
  faqs?: { q: string; a: string }[]; establishedYear?: number | null;
  doctors: ClinicDoctor[]; services: ClinicService[]; branches: ClinicBranch[];
};

const TZ = "Africa/Cairo";

/** The clinic's current day key (sat..fri) and HH:MM in its own timezone. */
export function nowInClinic(now = new Date()): { day: (typeof DAYS)[number]; time: string } {
  const parts = new Intl.DateTimeFormat("en-US", { timeZone: TZ, weekday: "short", hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).formatToParts(now);
  const get = (t: string) => parts.find((p) => p.type === t)?.value ?? "";
  return { day: get("weekday").toLowerCase().slice(0, 3) as (typeof DAYS)[number], time: `${get("hour")}:${get("minute")}` };
}

export type OpenStatus =
  | { state: "open"; until: string }
  | { state: "closed"; next?: { day: string; time: string; today: boolean } }
  | { state: "unknown" };

/** Open / closed right now from the weekly hours (hours that run past midnight count as open until close). */
export function openStatus(hours: WeekHours | undefined | null, now = new Date()): OpenStatus {
  if (!hasHours(hours)) return { state: "unknown" };
  const { day, time } = nowInClinic(now);
  const today = hours?.[day];
  if (today && !today.closed) {
    const overnight = today.close <= today.open;
    if (time >= today.open && (overnight || time < today.close)) return { state: "open", until: today.close };
    if (time < today.open) return { state: "closed", next: { day, time: today.open, today: true } };
  }
  const start = DAYS.indexOf(day);
  for (let i = 1; i <= 7; i++) {
    const d = DAYS[(start + i) % 7];
    const h = hours?.[d];
    if (h && !h.closed) return { state: "closed", next: { day: d, time: h.open, today: false } };
  }
  return { state: "closed" };
}
