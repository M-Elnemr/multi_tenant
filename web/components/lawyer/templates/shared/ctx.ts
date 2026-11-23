import { backendJson, currentHost } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";
import type { Locale, Translate } from "@/lib/i18n";
import { fileUrl } from "@/lib/media";
import { siteTemplate } from "@/lib/site-template";
import { resolveHost } from "@/lib/tenant";
import { whatsappLink } from "@/lib/whatsapp";
import { LAWYER_TEMPLATE_IDS } from "@/lib/lawyer-templates";
import type { LawyerSite } from "@/app/t-lawyer/(site)/types";

export const LN_IDS = ["ln-global", "ln-boutique", "ln-advisory", "ln-digital", "ln-heritage"] as const;
export type LnId = (typeof LN_IDS)[number];
export const isLnId = (v: unknown): v is LnId => typeof v === "string" && (LN_IDS as readonly string[]).includes(v);

export type LnArea = LawyerSite["practiceAreas"][number];
/** One lawyer of the firm. The backend serves a single profile per site today, so `lawyers` has one entry; every layout copes with 1..n. */
export type LnLawyer = { key: string; name: string; title: string; role: string; bio: string; photoUrl: string | null; barNumber: string; barLevel: string; years: number; languages: string[]; education: LawyerSite["education"] };
export type LnNavKey = "about" | "team" | "areas" | "services" | "reviews" | "faq" | "contact";
export type LnNav = { key: LnNavKey; href: string; label: string };
export type LnHours = { w: number; name: string; rows: string[] }[];

export type LnCtx = {
  id: LnId; isLn: boolean; p: LawyerSite; name: string; firm: string; t: Translate; locale: Locale; currency: string; logo: string | null; book: boolean; waHref: string | null;
  lawyers: LnLawyer[]; nav: LnNav[]; hours: LnHours; hasHours: boolean; stats: { value: string; label: string }[]; langHref: string; year: number; inFrame: boolean;
  address: string;
};

/** Everything a template page needs, from the one real backend call the original lawyer site already makes (profile). Nothing is invented. */
export async function buildLnCtx(): Promise<LnCtx | null> {
  const { t, locale } = await getT();
  const info = await resolveHost(await currentHost());
  const p = await backendJson<LawyerSite>("/lawyer/public/profile").catch(() => null);
  if (!p) return null;
  const tpl = await siteTemplate<string>(p.template, LAWYER_TEMPLATE_IDS, "classic");
  const isLn = isLnId(tpl.id);
  const id: LnId = isLnId(tpl.id) ? tpl.id : "ln-global";
  const logoId = info.kind === "TENANT" ? (info.branding as { logo_file_id?: string }).logo_file_id : undefined;
  const name = `${p.title ? p.title + " " : ""}${p.displayName}`;
  const intl = locale === "ar" ? "ar-EG" : "en-GB";
  const fmt = (s: string) => new Intl.DateTimeFormat(intl, { hour: "numeric", minute: "2-digit", timeZone: "UTC" }).format(new Date(`2024-01-01T${s}Z`));
  const hours: LnHours = [1, 2, 3, 4, 5, 6, 7].map((w) => ({
    w, name: new Intl.DateTimeFormat(intl, { weekday: "long", timeZone: "UTC" }).format(new Date(Date.UTC(2024, 0, w))),
    rows: p.hours.filter((h) => h.weekday === w).map((h) => `${fmt(h.start)} – ${fmt(h.end)}`),
  }));
  const hasAreas = p.practiceAreas.length > 0;
  const nav: LnNav[] = [
    { key: "about", href: "/about", label: t("lws.nav.about") },
    { key: "team", href: "/team", label: t("lnx.nav.team") },
    ...(hasAreas ? [{ key: "areas" as const, href: "/areas", label: t("lws.nav.areas") }] : []),
    ...(p.services.length ? [{ key: "services" as const, href: "/services", label: t("lws.nav.services") }] : []),
    ...(p.testimonials.length ? [{ key: "reviews" as const, href: "/reviews", label: t("lws.nav.reviews") }] : []),
    ...(p.faq.length ? [{ key: "faq" as const, href: "/faq", label: t("lws.faqEyebrow") }] : []),
    { key: "contact", href: "/contact", label: t("lws.nav.contact") },
  ];
  const lawyers: LnLawyer[] = [{
    key: "main", name, title: p.title, role: p.barLevel, bio: p.bio, photoUrl: p.photoUrl ?? null, barNumber: p.barNumber, barLevel: p.barLevel,
    years: p.yearsExperience, languages: p.languages, education: p.education,
  }];
  const stats = p.achievements.length ? p.achievements : p.yearsExperience ? [{ value: `${p.yearsExperience.toLocaleString(locale === "ar" ? "ar-EG" : "en-US", { useGrouping: false })}+`, label: t("lws.years") }] : [];
  return {
    id, isLn, p, name, firm: info.kind === "TENANT" ? info.name : name, t, locale, currency: info.kind === "TENANT" ? info.currency : "EGP",
    logo: logoId ? fileUrl(logoId, "thumb") : null, book: p.bookingEnabled !== false, waHref: p.whatsapp ? whatsappLink(p.whatsapp, t("lws.waHello")) : null, lawyers, nav, hours, hasHours: p.hours.length > 0, stats,
    langHref: `/api/lang?l=${locale === "ar" ? "en" : "ar"}`, year: new Date().getFullYear(), inFrame: tpl.inFrame,
    address: [p.officeAddress, p.officeCity].filter(Boolean).join("، "),
  };
}

/** First letters of a name for a monogram (handles Arabic and Latin; ignores titles). */
export function initials(s: string): string {
  const parts = s.trim().split(/\s+/).filter(Boolean);
  return ((parts[0]?.[0] ?? "") + (parts.length > 1 ? parts[parts.length - 1][0] : "")).toUpperCase();
}
