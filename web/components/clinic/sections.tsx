import Link from "next/link";
import { Icon, type IconName } from "@/components/icons";
import { SocialLinks, hasSocial } from "@/components/social-icons";
import { DAYS, hasHours, type WeekHours } from "@/lib/hours";
import { fileUrl } from "@/lib/media";
import { money } from "@/lib/format";
import type { Locale } from "@/lib/i18n";
import { whatsappLink } from "@/lib/whatsapp";
import { nowInClinic, openStatus, type ClinicBranch, type ClinicDoctor, type ClinicService, type ClinicSite } from "@/lib/clinic-site";
import { CountUp, Reveal } from "./reveal";

export type T = (k: string, v?: Record<string, string | number>) => string;

export const WhatsAppGlyph = ({ className = "h-5 w-5" }: { className?: string }) => (
  <svg viewBox="0 0 32 32" className={className} fill="currentColor" aria-hidden><path d="M16.04 3C9.4 3 4 8.4 4 15.03c0 2.12.56 4.19 1.62 6.01L4 29l8.14-1.59a12 12 0 0 0 3.9.65C22.68 28.06 28 22.66 28 16.03 28 9.4 22.68 3 16.04 3Zm5.5 19.57c-.3-.15-1.79-.88-2.07-.98-.28-.1-.48-.15-.68.15-.2.3-.78.98-.96 1.18-.18.2-.35.22-.65.07-.3-.15-1.28-.47-2.43-1.5-.9-.8-1.5-1.79-1.68-2.09-.18-.3-.02-.46.13-.61.14-.13.3-.35.45-.52.15-.18.2-.3.3-.5.1-.2.05-.38-.02-.53-.08-.15-.68-1.64-.93-2.25-.25-.59-.5-.51-.68-.52h-.58c-.2 0-.53.08-.8.38-.28.3-1.05 1.03-1.05 2.5 0 1.48 1.08 2.9 1.23 3.1.15.2 2.12 3.2 5.1 4.49.71.3 1.27.49 1.7.62.72.23 1.37.2 1.88.12.57-.08 1.79-.73 2.04-1.44.25-.7.25-1.3.18-1.44-.08-.12-.28-.2-.58-.35Z" /></svg>
);

export const mainPhone = (p: ClinicSite) => p.phone || p.extraPhones?.[0] || p.branches.find((b) => b.phone)?.phone || "";
export const directionsUrl = (p: ClinicSite) =>
  p.mapsUrl || p.branches.find((b) => b.mapsUrl)?.mapsUrl || (p.addressText ? `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(p.addressText)}` : "");
const specialtyNames = (d: ClinicDoctor, locale: Locale) => d.specialties.map((s) => (s.code === "other" && d.otherSpecialty ? d.otherSpecialty : locale === "ar" ? s.nameAr : s.nameEn));

function SectionHead({ eyebrow, title, sub }: { eyebrow?: string; title: string; sub?: string }) {
  return (
    <Reveal className="mb-8 text-center sm:mb-10">
      {eyebrow && <p className="c-eyebrow">{eyebrow}</p>}
      <h2 className="c-title mt-1">{title}</h2>
      {sub && <p className="c-mute mx-auto mt-2 max-w-xl">{sub}</p>}
    </Reveal>
  );
}

/** Top bar shown only when the clinic wrote an announcement or is temporarily closed. */
export function Announcement({ p }: { p: ClinicSite; t?: T }) {
  const text = p.isOpen === false ? p.closedMessage || "" : p.announcement || "";
  if (!text && p.isOpen !== false) return null;
  return <div className="c-announce">{p.isOpen === false && "⚠️ "}{text}</div>;
}

export function Hero({ p, t, logoId, hrefBook }: { p: ClinicSite; t: T; logoId?: string | null; hrefBook?: string }) {
  const phone = mainPhone(p);
  const dir = directionsUrl(p);
  const st = p.isOpen === false ? null : openStatus(p.workingHours);
  const years = p.establishedYear ? new Date().getFullYear() - p.establishedYear : Math.max(0, ...p.doctors.map((d) => d.yearsExperience ?? 0));
  const stats: { n: number; label: string }[] = [
    { n: p.doctors.length, label: t("csite.statDoctors") },
    { n: p.services.length, label: t("csite.statServices") },
    ...(years > 0 ? [{ n: years, label: t("csite.statYears") }] : []),
    ...((p.queueCount ?? 0) > 0 ? [{ n: p.queueCount ?? 0, label: t("clinic.queueNow") }] : []),
  ].filter((s) => s.n > 0);
  return (
    <section className="c-hero">
      {p.coverFileId && (<>
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src={fileUrl(p.coverFileId, "original")} alt="" className="c-hero-img" />
        <div className="c-hero-shade" />
      </>)}
      {!p.coverFileId && (<>
        <div className="c-blob -end-20 -top-20 h-72 w-72" />
        <div className="c-blob -bottom-24 start-1/4 h-60 w-60 [animation-delay:-5s]" />
        <svg className="c-cross end-[10%] top-[18%] h-16 w-16" viewBox="0 0 24 24" fill="currentColor"><path d="M9 2h6v7h7v6h-7v7H9v-7H2V9h7z" /></svg>
        <svg className="c-cross start-[8%] top-[55%] h-10 w-10 [animation-delay:-3s]" viewBox="0 0 24 24" fill="currentColor"><path d="M9 2h6v7h7v6h-7v7H9v-7H2V9h7z" /></svg>
      </>)}
      <div className="c-container relative pb-24 pt-14 text-center sm:pb-28 sm:pt-20">
        {logoId && (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={fileUrl(logoId, "medium")} alt="" className="c-up mx-auto mb-5 h-20 w-20 rounded-3xl border-4 border-white/70 bg-white object-cover shadow-xl" />
        )}
        {st && st.state !== "unknown" && (
          <p className="c-up mb-4" style={{ "--d": "60ms" } as React.CSSProperties}>
            <span className="c-status"><i className={`c-dot ${st.state === "open" ? "" : "c-dot-off"}`} />
              {st.state === "open" ? `${t("csite.openNow")} · ${t("csite.closesAt", { time: st.until })}`
                : st.next ? `${t("csite.closedNow")} · ${st.next.today ? t("csite.opensToday", { time: st.next.time }) : t("csite.opensAt", { day: t(`day.${st.next.day}`), time: st.next.time })}` : t("csite.closedNow")}
            </span>
          </p>
        )}
        <h1 className="c-up text-4xl font-extrabold sm:text-6xl" style={{ "--d": "120ms" } as React.CSSProperties}>{p.clinicName}</h1>
        {(p.tagline || p.about) && <p className="c-up mx-auto mt-4 max-w-2xl text-lg opacity-95 sm:text-xl" style={{ "--d": "200ms" } as React.CSSProperties}>{p.tagline || p.about?.slice(0, 160)}</p>}
        <div className="c-up mt-8 flex flex-wrap justify-center gap-3" style={{ "--d": "280ms" } as React.CSSProperties}>
          {hrefBook && <Link href={hrefBook} className="c-btn c-btn-white">{t("clinic.bookNow")}</Link>}
          {phone && <a href={`tel:${phone}`} className="c-btn c-btn-white c-pulse"><Icon name="phone" className="h-5 w-5" />{t("csite.call")}</a>}
          {p.whatsapp && <a href={whatsappLink(p.whatsapp, `${p.clinicName} 👋`)} target="_blank" rel="noopener noreferrer" className="c-btn c-btn-wa"><WhatsAppGlyph />{t("csite.whatsapp")}</a>}
          {dir && <a href={dir} target="_blank" rel="noopener noreferrer" className="c-btn c-btn-glass"><Icon name="navigate" className="h-5 w-5" />{t("csite.directions")}</a>}
        </div>
      </div>
      {stats.length > 0 && (
        <div className="c-container relative -mb-12 sm:-mb-14">
          <div className="c-card c-up grid divide-x divide-[var(--c-line)] rtl:divide-x-reverse" style={{ gridTemplateColumns: `repeat(${stats.length}, minmax(0, 1fr))`, "--d": "380ms" } as React.CSSProperties}>
            {stats.map((s) => <div key={s.label} className="c-stat"><b><CountUp to={s.n} />{s.label === t("csite.statYears") ? "+" : ""}</b><span className="c-mute text-xs font-semibold sm:text-sm">{s.label}</span></div>)}
          </div>
        </div>
      )}
    </section>
  );
}

export function AboutSection({ p, t }: { p: ClinicSite; t: T }) {
  if (!p.about) return null;
  return (
    <section id="about" className="c-container pt-24 sm:pt-28">
      <Reveal className="c-card mx-auto max-w-3xl p-7 text-center sm:p-10">
        <p className="c-eyebrow">{t("csite.about")}</p>
        <p className="mt-3 whitespace-pre-line text-lg leading-8 text-[var(--c-mute)]">{p.about}</p>
        {p.establishedYear && <p className="mt-4 text-sm font-bold" style={{ color: "var(--brand)" }}>{t("csite.since", { year: p.establishedYear })}</p>}
      </Reveal>
    </section>
  );
}

export function ServicesSection({ services, t, locale, hrefBook }: { services: ClinicService[]; t: T; locale: Locale; hrefBook?: string }) {
  if (!services.length) return null;
  return (
    <section id="services" className="c-container pt-20">
      <SectionHead eyebrow={t("clinic.services")} title={t("csite.servicesTitle")} sub={t("csite.servicesSub")} />
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {services.map((s, i) => (
          <Reveal key={s.id} delay={(i % 3) * 80} className="c-card c-lift flex flex-col p-6">
            <div className="flex items-start gap-4">
              <span className="c-icon h-12 w-12 shrink-0"><Icon name={s.visitType === "FOLLOW_UP" ? "heart" : "stethoscope"} className="h-6 w-6" /></span>
              <div className="min-w-0">
                <h3 className="text-lg font-extrabold">{s.name}</h3>
                {s.visitType && <span className="c-chip mt-1">{s.visitType === "FOLLOW_UP" ? t("csite.visitFollow") : t("csite.visitConsult")}</span>}
              </div>
            </div>
            {s.description && <p className="c-mute mt-3 text-sm leading-6">{s.description}</p>}
            <div className="mt-auto flex items-end justify-between pt-5">
              <span className="c-mute flex items-center gap-1.5 text-sm font-semibold"><Icon name="clock" className="h-4 w-4" />{s.durationMinutes} {t("clinic.minutes")}</span>
              {s.priceMinor ? <b className="text-xl" style={{ color: "var(--brand)" }}>{money(s.priceMinor, s.currency, locale)}</b> : null}
            </div>
            {hrefBook && <Link href={`${hrefBook}?serviceId=${s.id}`} className="c-btn c-btn-ghost c-btn-sm mt-4">{t("clinic.bookNow")}</Link>}
          </Reveal>
        ))}
      </div>
    </section>
  );
}

export function DoctorsSection({ doctors, t, locale, phone, hrefBook }: { doctors: ClinicDoctor[]; t: T; locale: Locale; phone: string; hrefBook?: string }) {
  if (!doctors.length) return null;
  return (
    <section id="doctors" className="c-container pt-20">
      <SectionHead eyebrow={t("clinic.doctors")} title={t("csite.doctorsTitle")} sub={t("csite.doctorsSub")} />
      <div className="grid gap-5 md:grid-cols-2">
        {doctors.map((d, i) => {
          const call = d.publicPhone || phone;
          const langs = d.languages ?? [];
          return (
            <Reveal key={d.id} delay={(i % 2) * 90} className="c-card c-lift flex flex-col gap-5 overflow-hidden p-5 sm:flex-row sm:p-6">
              <div className="c-gal h-40 w-full shrink-0 sm:h-36 sm:w-36">
                {d.profileImageFileId ? (
                  // eslint-disable-next-line @next/next/no-img-element
                  <img src={fileUrl(d.profileImageFileId, "medium")} alt={d.displayName} className="c-photo h-full w-full object-cover" />
                ) : <div className="c-avatar grid h-full w-full place-items-center text-5xl">{d.displayName.trim().charAt(0).toUpperCase()}</div>}
              </div>
              <div className="min-w-0 flex-1">
                <h3 className="text-xl font-extrabold">{d.displayName}</h3>
                <div className="mt-2 flex flex-wrap gap-1.5">{specialtyNames(d, locale).map((n) => <span key={n} className="c-chip">{n}</span>)}</div>
                <ul className="c-mute mt-3 space-y-1.5 text-sm">
                  {d.qualifications && <li className="flex items-start gap-2"><Icon name="award" className="mt-0.5 h-4 w-4 shrink-0" style={{ color: "var(--brand)" }} />{d.qualifications}</li>}
                  {d.yearsExperience ? <li className="flex items-center gap-2"><Icon name="star" className="h-4 w-4 shrink-0" style={{ color: "var(--brand)" }} />{t("csite.experience", { n: d.yearsExperience })}</li> : null}
                  {langs.length > 0 && <li className="flex items-center gap-2"><Icon name="language" className="h-4 w-4 shrink-0" style={{ color: "var(--brand)" }} />{langs.join(" · ")}</li>}
                </ul>
                {d.bio && <p className="mt-3 text-sm leading-6 text-[var(--c-mute)]">{d.bio}</p>}
                <div className="mt-4 flex flex-wrap items-center gap-2">
                  {d.defaultAppointmentFeeMinor ? <span className="me-auto text-sm"><span className="c-mute">{t("csite.fee")}: </span><b style={{ color: "var(--brand)" }}>{money(d.defaultAppointmentFeeMinor, d.currency ?? "EGP", locale)}</b></span> : <span className="me-auto" />}
                  {hrefBook && <Link href={`${hrefBook}?doctorId=${d.id}`} className="c-btn c-btn-sm">{t("clinic.bookNow")}</Link>}
                  {call && <a href={`tel:${call}`} className="c-btn c-btn-ghost c-btn-sm"><Icon name="phone" className="h-4 w-4" />{t("csite.call")}</a>}
                </div>
              </div>
            </Reveal>
          );
        })}
      </div>
    </section>
  );
}

export function HowSection({ t }: { t: T }) {
  const steps = [
    { icon: "chat" as IconName, title: t("csite.step1t"), text: t("csite.step1d") },
    { icon: "pin" as IconName, title: t("csite.step2t"), text: t("csite.step2d") },
    { icon: "heart" as IconName, title: t("csite.step3t"), text: t("csite.step3d") },
  ];
  return (
    <section className="c-container pt-20">
      <SectionHead eyebrow={t("csite.how")} title={t("csite.howTitle")} />
      <div className="grid gap-8 md:grid-cols-3">
        {steps.map((s, i) => (
          <Reveal key={s.title} delay={i * 110} className="c-step flex gap-4 md:flex-col md:gap-5">
            <span className="c-step-n shrink-0">{i + 1}</span>
            <div>
              <h3 className="flex items-center gap-2 text-lg font-extrabold"><Icon name={s.icon} className="h-5 w-5" style={{ color: "var(--brand)" }} />{s.title}</h3>
              <p className="c-mute mt-1 text-sm leading-6">{s.text}</p>
            </div>
          </Reveal>
        ))}
      </div>
    </section>
  );
}

export function HoursList({ hours, t }: { hours?: WeekHours | null; t: T }) {
  if (!hasHours(hours)) return null;
  const today = nowInClinic().day;
  return (
    <ul className="space-y-2 text-sm">
      {DAYS.map((d) => {
        const h = hours?.[d];
        if (!h) return null;
        return (
          <li key={d} className={`flex justify-between gap-6 py-0.5 ${d === today ? "c-today" : ""}`}>
            <span>{t(`day.${d}`)}{d === today ? ` · ${t("clinic.today")}` : ""}</span>
            <span dir="ltr" className={h.closed ? "c-mute" : "font-bold"}>{h.closed ? t("hours.closed") : `${h.open} – ${h.close}`}</span>
          </li>
        );
      })}
    </ul>
  );
}

function BranchCard({ b, t, i }: { b: ClinicBranch; t: T; i: number }) {
  const address = [b.addressLine1, b.addressLine2, b.district, b.city].filter(Boolean).join("، ");
  const map = b.mapsUrl || (address ? `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(address)}` : "");
  return (
    <Reveal delay={(i % 2) * 90} className="c-card c-lift p-6">
      <div className="flex items-start gap-4">
        <span className="c-icon h-12 w-12 shrink-0"><Icon name="pin" className="h-6 w-6" /></span>
        <div className="min-w-0">
          <h3 className="text-lg font-extrabold">{b.name}</h3>
          {address && <p className="c-mute mt-1 text-sm">{address}</p>}
          {b.landmark && <p className="mt-1 text-sm font-semibold" style={{ color: "var(--brand)" }}>📍 {b.landmark}</p>}
        </div>
      </div>
      {hasHours(b.workingHours) && <details className="mt-4 rounded-2xl bg-[var(--c-soft)] px-4 py-3"><summary className="flex items-center justify-between text-sm font-bold"><span className="flex items-center gap-2"><Icon name="clock" className="h-4 w-4" />{t("identity.hours")}</span><Icon name="arrow" className="c-chev h-4 w-4 rotate-90" /></summary><div className="mt-3"><HoursList hours={b.workingHours} t={t} /></div></details>}
      <div className="mt-4 flex flex-wrap gap-2">
        {b.phone && <a href={`tel:${b.phone}`} className="c-btn c-btn-ghost c-btn-sm"><Icon name="phone" className="h-4 w-4" /><span dir="ltr">{b.phone}</span></a>}
        {b.whatsapp && <a href={whatsappLink(b.whatsapp)} target="_blank" rel="noopener noreferrer" className="c-btn c-btn-wa c-btn-sm"><WhatsAppGlyph className="h-4 w-4" />{t("csite.whatsapp")}</a>}
        {map && <a href={map} target="_blank" rel="noopener noreferrer" className="c-btn c-btn-sm"><Icon name="navigate" className="h-4 w-4" />{t("csite.directions")}</a>}
      </div>
    </Reveal>
  );
}

export function VisitSection({ p, t }: { p: ClinicSite; t: T }) {
  const showHours = hasHours(p.workingHours);
  if (!showHours && !p.branches.length) return null;
  return (
    <section id="hours" className="c-container pt-20">
      <SectionHead eyebrow={t("csite.visit")} title={t("csite.visitTitle")} />
      <div className={`grid gap-5 ${showHours ? "lg:grid-cols-[1fr_1.4fr]" : ""}`}>
        {showHours && (
          <Reveal className="c-card h-fit p-6 sm:p-7">
            <h3 className="mb-4 flex items-center gap-2 text-lg font-extrabold"><span className="c-icon h-10 w-10"><Icon name="clock" className="h-5 w-5" /></span>{t("identity.hours")}</h3>
            <HoursList hours={p.workingHours} t={t} />
          </Reveal>
        )}
        <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-1 xl:grid-cols-2">{p.branches.map((b, i) => <BranchCard key={b.id} b={b} t={t} i={i} />)}</div>
      </div>
    </section>
  );
}

export function GallerySection({ ids, t }: { ids: string[]; t: T }) {
  if (!ids.length) return null;
  return (
    <section className="c-container pt-20">
      <SectionHead eyebrow={t("csite.gallery")} title={t("csite.galleryTitle")} />
      <div className="grid grid-cols-2 gap-3 sm:gap-4 md:grid-cols-3">
        {ids.map((id, i) => (
          <Reveal key={id} delay={(i % 3) * 70} className={`c-gal ${i === 0 ? "col-span-2 aspect-[2/1]" : "aspect-[4/3]"}`}>
            <a href={fileUrl(id, "original")} target="_blank" rel="noopener noreferrer" className="block h-full w-full">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={fileUrl(id, "medium")} alt="" className="h-full w-full object-cover" loading="lazy" />
            </a>
          </Reveal>
        ))}
      </div>
    </section>
  );
}

export function InsuranceSection({ items, t }: { items?: string[]; t: T }) {
  if (!items?.length) return null;
  return (
    <section className="c-container pt-20">
      <SectionHead eyebrow={t("csite.insuranceEyebrow")} title={t("csite.insurance")} />
      <Reveal className="flex flex-wrap justify-center gap-3">
        {items.map((x) => <span key={x} className="c-card inline-flex items-center gap-2 px-5 py-3 text-sm font-bold"><Icon name="shield" className="h-5 w-5" style={{ color: "var(--brand)" }} />{x}</span>)}
      </Reveal>
    </section>
  );
}

export function FaqSection({ items, t }: { items?: { q: string; a: string }[]; t: T }) {
  if (!items?.length) return null;
  return (
    <section id="faq" className="c-container pt-20">
      <SectionHead eyebrow={t("csite.faqEyebrow")} title={t("csite.faq")} />
      <div className="mx-auto max-w-3xl space-y-3">
        {items.map((f, i) => (
          <Reveal key={f.q} delay={i * 50}>
            <details className="c-card px-6 py-4">
              <summary className="flex items-center justify-between gap-4 font-bold">{f.q}<Icon name="arrow" className="c-chev h-5 w-5 shrink-0 rotate-90" style={{ color: "var(--brand)" }} /></summary>
              <div className="c-mute mt-3 whitespace-pre-line text-sm leading-7">{f.a}</div>
            </details>
          </Reveal>
        ))}
      </div>
    </section>
  );
}

export function ContactBand({ p, t, hrefBook }: { p: ClinicSite; t: T; hrefBook?: string }) {
  const phone = mainPhone(p);
  return (
    <section id="contact" className="c-container pt-20">
      <Reveal className="c-hero rounded-[32px] px-6 py-12 text-center shadow-[var(--c-shadow-lg)] sm:px-12">
        <div className="relative">
          <h2 className="text-3xl font-extrabold sm:text-4xl">{t("csite.ctaTitle")}</h2>
          <p className="mx-auto mt-3 max-w-xl text-lg opacity-95">{t("csite.ctaText")}</p>
          <div className="mt-7 flex flex-wrap justify-center gap-3">
            {hrefBook && <Link href={hrefBook} className="c-btn c-btn-white">{t("clinic.bookNow")}</Link>}
            {phone && <a href={`tel:${phone}`} className="c-btn c-btn-white"><Icon name="phone" className="h-5 w-5" /><span dir="ltr">{phone}</span></a>}
            {p.whatsapp && <a href={whatsappLink(p.whatsapp, `${p.clinicName} 👋`)} target="_blank" rel="noopener noreferrer" className="c-btn c-btn-wa"><WhatsAppGlyph />{t("csite.whatsapp")}</a>}
            {p.email && <a href={`mailto:${p.email}`} className="c-btn c-btn-glass"><Icon name="mail" className="h-5 w-5" />{p.email}</a>}
          </div>
          {p.addressText && <p className="mt-6 flex items-center justify-center gap-2 text-sm opacity-95"><Icon name="pin" className="h-4 w-4" />{p.addressText}</p>}
          {hasSocial(p) && (
            <div className="mt-6">
              <p className="mb-3 text-xs font-bold uppercase tracking-wider opacity-80">{t("contact.follow")}</p>
              <SocialLinks p={p} className="justify-center" />
            </div>
          )}
        </div>
      </Reveal>
    </section>
  );
}

/** Phones: a sticky Call / WhatsApp / Directions bar, the way patients actually reach a clinic. */
export function MobileBar({ p, t, hrefBook }: { p: ClinicSite; t: T; hrefBook?: string }) {
  const phone = mainPhone(p);
  const dir = directionsUrl(p);
  return (
    <div className="c-mobilebar">
      {hrefBook ? <Link href={hrefBook} className="c-btn flex-1">{t("clinic.bookNow")}</Link> : phone ? <a href={`tel:${phone}`} className="c-btn flex-1"><Icon name="phone" className="h-5 w-5" />{t("csite.call")}</a> : null}
      {hrefBook && phone && <a href={`tel:${phone}`} aria-label={t("csite.call")} className="c-btn c-btn-ghost px-4"><Icon name="phone" className="h-5 w-5" /></a>}
      {p.whatsapp && <a href={whatsappLink(p.whatsapp, `${p.clinicName} 👋`)} target="_blank" rel="noopener noreferrer" aria-label="WhatsApp" className="c-btn c-btn-wa px-4"><WhatsAppGlyph /></a>}
      {dir && <a href={dir} target="_blank" rel="noopener noreferrer" aria-label={t("csite.directions")} className="c-btn c-btn-ghost px-4"><Icon name="navigate" className="h-5 w-5" /></a>}
    </div>
  );
}
