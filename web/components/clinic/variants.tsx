import Link from "next/link";
import { Icon } from "@/components/icons";
import { fileUrl } from "@/lib/media";
import { money } from "@/lib/format";
import type { Locale } from "@/lib/i18n";
import { whatsappLink } from "@/lib/whatsapp";
import { openStatus, type ClinicDoctor, type ClinicService, type ClinicSite } from "@/lib/clinic-site";
import { CountUp, Reveal } from "./reveal";
import { ParallaxBg, Rise } from "./motion";
import { Hero as CenterHero, SectionHead, WhatsAppGlyph, directionsUrl, mainPhone, specialtyNames, type T } from "./sections";
import { DoctorDirectory } from "./directory";
import type { ClinicHeroKind } from "./templates";

/* eslint-disable @next/next/no-img-element */

function OpenPill({ p, t }: { p: ClinicSite; t: T }) {
  const st = p.isOpen === false ? null : openStatus(p.workingHours);
  if (!st || st.state === "unknown") return null;
  return (
    <span className="c-status"><i className={`c-dot ${st.state === "open" ? "" : "c-dot-off"}`} />
      {st.state === "open" ? `${t("csite.openNow")} · ${t("csite.closesAt", { time: st.until })}` : st.next ? `${t("csite.closedNow")} · ${st.next.today ? t("csite.opensToday", { time: st.next.time }) : t("csite.opensAt", { day: t(`day.${st.next.day}`), time: st.next.time })}` : t("csite.closedNow")}
    </span>
  );
}

function Actions({ p, t, hrefBook, light }: { p: ClinicSite; t: T; hrefBook?: string; light?: boolean }) {
  const phone = mainPhone(p);
  const dir = directionsUrl(p);
  return (
    <div className="flex flex-wrap gap-3">
      {hrefBook && <Link href={hrefBook} className={`c-btn ${light ? "c-btn-white" : ""}`}><Icon name="calendar" className="h-5 w-5" />{t("clinic.bookNow")}</Link>}
      {phone && <a href={`tel:${phone}`} className={`c-btn ${hrefBook ? (light ? "c-btn-glass" : "c-btn-ghost") : light ? "c-btn-white" : ""}`}><Icon name="phone" className="h-5 w-5" />{t("csite.call")}</a>}
      {p.whatsapp && <a href={whatsappLink(p.whatsapp, p.clinicName)} target="_blank" rel="noopener noreferrer" className="c-btn c-btn-wa"><WhatsAppGlyph />{t("csite.whatsapp")}</a>}
      {dir && !hrefBook && <a href={dir} target="_blank" rel="noopener noreferrer" className={`c-btn ${light ? "c-btn-glass" : "c-btn-ghost"}`}><Icon name="navigate" className="h-5 w-5" />{t("csite.directions")}</a>}
    </div>
  );
}

function trustFacts(p: ClinicSite, t: T) {
  const years = p.establishedYear ? new Date().getFullYear() - p.establishedYear : Math.max(0, ...p.doctors.map((d) => d.yearsExperience ?? 0));
  return [
    { n: p.doctors.length, label: t("csite.statDoctors"), plus: false },
    { n: p.services.length, label: t("csite.statServices"), plus: false },
    { n: years, label: t("csite.statYears"), plus: true },
    { n: p.insurance?.length ?? 0, label: t("csite.statInsurance"), plus: false },
  ].filter((s) => s.n > 0);
}

/** The clinic's hero, composed the way its template wants: clean split, full-bleed luxury, a doctor's personal page, dental, wellness arch, or a medical centre. */
export function ClinicHero({ kind, p, t, locale, logoId, hrefBook }: { kind: ClinicHeroKind; p: ClinicSite; t: T; locale: Locale; logoId?: string | null; hrefBook?: string }) {
  if (kind === "center") return <CenterHero p={p} t={t} logoId={logoId} hrefBook={hrefBook} />;
  const cover = p.coverFileId ? fileUrl(p.coverFileId, "original") : null;
  const lead = p.doctors[0];
  const leadPhoto = lead?.profileImageFileId ? fileUrl(lead.profileImageFileId, "original") : null;
  const facts = trustFacts(p, t);
  const blurb = p.tagline || p.about?.slice(0, 180) || "";

  if (kind === "fullbleed") {
    return (
      <section className="c-hero-full relative isolate overflow-hidden">
        {cover ? <ParallaxBg className="absolute inset-0 -z-10"><img src={cover} alt="" className="h-full w-full object-cover" /></ParallaxBg> : <div className="absolute inset-0 -z-10 bg-[var(--c-ink)]" />}
        <div className="absolute inset-0 -z-10 bg-gradient-to-b from-[rgb(8_14_22/.55)] via-[rgb(8_14_22/.62)] to-[var(--c-bg)]" />
        <div className="c-container flex min-h-[86svh] flex-col items-center justify-center py-24 text-center text-white">
          <Rise><p className="c-hair mb-6 text-[11px] uppercase tracking-[0.45em] text-[var(--gold,#c8a96a)]">{t("csite.privateCare")}</p></Rise>
          <Rise delay={100}><h1 className="c-display text-balance text-[2.8rem] leading-[1.02] sm:text-[5.2rem]">{p.clinicName}</h1></Rise>
          {blurb && <Rise delay={200}><p className="mx-auto mt-6 max-w-xl text-lg text-white/80">{blurb}</p></Rise>}
          <Rise delay={300} className="mt-10 flex justify-center"><Actions p={p} t={t} hrefBook={hrefBook} light /></Rise>
          <Rise delay={400} className="mt-8"><OpenPill p={p} t={t} /></Rise>
        </div>
        {facts.length > 0 && (
          <div className="c-container relative -mt-16 pb-4">
            <div className="c-facts grid border-y border-[var(--c-line)]" style={{ gridTemplateColumns: `repeat(${facts.length}, minmax(0,1fr))` }}>
              {facts.map((f) => <div key={f.label} className="px-3 py-6 text-center"><b className="c-display block text-3xl sm:text-4xl"><CountUp to={f.n} />{f.plus ? "+" : ""}</b><span className="c-mute text-xs uppercase tracking-[0.2em]">{f.label}</span></div>)}
            </div>
          </div>
        )}
      </section>
    );
  }

  if (kind === "persona" && lead) {
    const names = specialtyNames(lead, locale);
    return (
      <section className="c-hero-persona c-container pt-10 sm:pt-16">
        <div className="grid items-end gap-10 lg:grid-cols-[1fr_1.1fr]">
          <Rise className="relative">
            <div className="c-portrait relative aspect-[4/5] overflow-hidden rounded-[28px] bg-[var(--c-soft)]">
              {leadPhoto ? <img src={leadPhoto} alt={lead.displayName} className="h-full w-full object-cover" /> : cover ? <img src={cover} alt="" className="h-full w-full object-cover" /> : <div className="c-avatar grid h-full w-full place-items-center text-[8rem]">{lead.displayName.trim().charAt(0)}</div>}
            </div>
            {lead.yearsExperience ? <div className="c-badge-float absolute -bottom-6 end-6 rounded-[20px] bg-[var(--c-card)] px-5 py-4 shadow-[var(--c-shadow-lg)]"><b className="c-display block text-3xl" style={{ color: "var(--brand)" }}><CountUp to={lead.yearsExperience} />+</b><span className="c-mute text-xs font-semibold">{t("csite.statYears")}</span></div> : null}
          </Rise>
          <div className="pb-4">
            <Rise><p className="c-eyebrow">{p.clinicName}</p></Rise>
            <Rise delay={100}><h1 className="c-display mt-3 text-[2.6rem] leading-[1.02] sm:text-[4.6rem]">{lead.displayName}</h1></Rise>
            <Rise delay={160} className="mt-4 flex flex-wrap gap-2">{names.map((n) => <span key={n} className="c-chip">{n}</span>)}</Rise>
            {(lead.bio || blurb) && <Rise delay={220}><p className="c-quote mt-7 max-w-xl text-xl leading-relaxed">“{(lead.bio || blurb).slice(0, 220)}{(lead.bio || blurb).length > 220 ? "…" : ""}”</p></Rise>}
            {lead.qualifications && <Rise delay={280}><p className="c-mute mt-5 flex items-start gap-2 text-sm"><Icon name="award" className="mt-0.5 h-4 w-4 shrink-0" style={{ color: "var(--brand)" }} />{lead.qualifications}</p></Rise>}
            <Rise delay={340} className="mt-8"><Actions p={p} t={t} hrefBook={hrefBook} /></Rise>
            <Rise delay={400} className="mt-5"><OpenPill p={p} t={t} /></Rise>
          </div>
        </div>
      </section>
    );
  }

  if (kind === "dental") {
    const first = p.services.find((s) => s.priceMinor);
    return (
      <section className="c-hero-dental relative overflow-hidden">
        <div className="c-container grid items-center gap-10 py-12 sm:py-20 lg:grid-cols-[1.05fr_1fr]">
          <div>
            <Rise><OpenPill p={p} t={t} /></Rise>
            <Rise delay={80}><h1 className="c-display mt-5 text-balance text-[2.6rem] font-extrabold leading-[1.04] sm:text-[4.2rem]">{p.clinicName}</h1></Rise>
            {blurb && <Rise delay={160}><p className="c-mute mt-5 max-w-lg text-lg">{blurb}</p></Rise>}
            <Rise delay={240} className="mt-8"><Actions p={p} t={t} hrefBook={hrefBook} /></Rise>
            {facts.length > 0 && <Rise delay={320} className="mt-10 flex flex-wrap gap-8">{facts.slice(0, 3).map((f) => <div key={f.label}><b className="c-display block text-3xl" style={{ color: "var(--brand)" }}><CountUp to={f.n} />{f.plus ? "+" : ""}</b><span className="c-mute text-sm">{f.label}</span></div>)}</Rise>}
          </div>
          <Rise delay={120} className="relative">
            <div className="c-smile relative aspect-square overflow-hidden rounded-[40%_60%_55%_45%/50%_45%_55%_50%] bg-[var(--c-soft)] shadow-[var(--c-shadow-lg)]">
              {cover ? <img src={cover} alt="" className="h-full w-full object-cover" /> : leadPhoto ? <img src={leadPhoto} alt="" className="h-full w-full object-cover" /> : <div className="grid h-full w-full place-items-center" style={{ color: "var(--brand)" }}><Icon name="tooth" className="h-40 w-40" /></div>}
            </div>
            {first && hrefBook && (
              <Link href={`${hrefBook}?serviceId=${first.id}`} className="c-float-book absolute -bottom-4 start-2 flex items-center gap-3 rounded-[20px] bg-[var(--c-card)] p-3 pe-5 shadow-[var(--c-shadow-lg)] sm:start-[-1.5rem]">
                <span className="grid h-12 w-12 place-items-center rounded-[14px] text-white" style={{ background: "var(--brand)" }}><Icon name="calendar" className="h-6 w-6" /></span>
                <span><span className="block text-sm font-extrabold">{first.name}</span><span className="c-mute text-xs">{money(first.priceMinor!, first.currency, locale)} · {first.durationMinutes} {t("clinic.minutes")}</span></span>
              </Link>
            )}
          </Rise>
        </div>
      </section>
    );
  }

  if (kind === "arch") {
    return (
      <section className="c-hero-arch c-container pt-10 sm:pt-16">
        <div className="grid items-center gap-12 lg:grid-cols-2 lg:gap-20">
          <Rise className="relative mx-auto w-full max-w-md">
            <div className="c-arch aspect-[3/4] overflow-hidden rounded-t-full bg-[var(--c-soft)] shadow-[var(--c-shadow-lg)]">
              {cover ? <img src={cover} alt="" className="h-full w-full object-cover" /> : leadPhoto ? <img src={leadPhoto} alt="" className="h-full w-full object-cover" /> : <div className="bg-brand-gradient h-full w-full opacity-60" />}
            </div>
            <span className="c-ring absolute -bottom-8 -end-8 h-32 w-32 rounded-full border border-[var(--brand)] opacity-40" />
          </Rise>
          <div>
            <Rise><p className="c-eyebrow">{t("csite.welcome")}</p></Rise>
            <Rise delay={100}><h1 className="c-display mt-4 text-[2.6rem] leading-[1.05] sm:text-[4.2rem]">{p.clinicName}</h1></Rise>
            {blurb && <Rise delay={180}><p className="c-mute mt-6 max-w-md text-lg leading-relaxed">{blurb}</p></Rise>}
            <Rise delay={260} className="mt-9"><Actions p={p} t={t} hrefBook={hrefBook} /></Rise>
            <Rise delay={320} className="mt-6"><OpenPill p={p} t={t} /></Rise>
          </div>
        </div>
      </section>
    );
  }

  // split (clean, reassuring): text with the trust facts beside a calm photo
  return (
    <section className="c-hero-split relative overflow-hidden">
      <div className="c-container grid items-center gap-10 py-12 sm:py-20 lg:grid-cols-[1.1fr_1fr]">
        <div>
          {logoId && <Rise><img src={fileUrl(logoId, "medium")} alt="" className="mb-6 h-14 w-14 rounded-2xl object-cover shadow-md" /></Rise>}
          <Rise delay={60}><OpenPill p={p} t={t} /></Rise>
          <Rise delay={120}><h1 className="c-display mt-5 text-balance text-[2.5rem] font-extrabold leading-[1.05] sm:text-[3.8rem]">{p.clinicName}</h1></Rise>
          {blurb && <Rise delay={180}><p className="c-mute mt-5 max-w-lg text-lg">{blurb}</p></Rise>}
          <Rise delay={240} className="mt-8"><Actions p={p} t={t} hrefBook={hrefBook} /></Rise>
        </div>
        <Rise delay={140} className="relative">
          <div className="relative aspect-[5/4] overflow-hidden rounded-[28px] bg-[var(--c-soft)] shadow-[var(--c-shadow-lg)]">
            {cover ? <img src={cover} alt="" className="h-full w-full object-cover" /> : leadPhoto ? <img src={leadPhoto} alt="" className="h-full w-full object-cover" /> : <div className="bg-brand-gradient h-full w-full opacity-70" />}
          </div>
          {facts.length > 0 && (
            <div className="c-card absolute -bottom-6 inset-x-4 grid divide-x divide-[var(--c-line)] rtl:divide-x-reverse" style={{ gridTemplateColumns: `repeat(${Math.min(3, facts.length)}, minmax(0,1fr))` }}>
              {facts.slice(0, 3).map((f) => <div key={f.label} className="c-stat"><b><CountUp to={f.n} />{f.plus ? "+" : ""}</b><span className="c-mute text-xs font-semibold">{f.label}</span></div>)}
            </div>
          )}
        </Rise>
      </div>
    </section>
  );
}

/** Doctors as tall editorial portraits (luxury, personal brand, wellness). */
export function DoctorsPortrait({ doctors, t, locale, hrefBook }: { doctors: ClinicDoctor[]; t: T; locale: Locale; hrefBook?: string }) {
  if (!doctors.length) return null;
  return (
    <section id="doctors" className="c-container pt-24">
      <SectionHead eyebrow={t("clinic.doctors")} title={t("csite.doctorsTitle")} sub={t("csite.doctorsSub")} />
      <div className={`grid gap-8 ${doctors.length === 1 ? "mx-auto max-w-sm" : doctors.length === 2 ? "sm:grid-cols-2 lg:mx-auto lg:max-w-3xl" : "sm:grid-cols-2 lg:grid-cols-3"}`}>
        {doctors.map((d, i) => (
          <Reveal key={d.id} delay={(i % 3) * 90} className="group">
            <div className="c-portrait relative aspect-[3/4] overflow-hidden rounded-[24px] bg-[var(--c-soft)]">
              {d.profileImageFileId ? <img src={fileUrl(d.profileImageFileId, "medium")} alt={d.displayName} className="h-full w-full object-cover transition duration-[1.2s] group-hover:scale-105" /> : <div className="c-avatar grid h-full w-full place-items-center text-7xl">{d.displayName.trim().charAt(0)}</div>}
              <div className="absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/70 to-transparent p-5 pt-20 text-white">
                <h3 className="c-display text-2xl">{d.displayName}</h3>
                <p className="mt-1 text-sm text-white/80">{specialtyNames(d, locale).join(" · ")}</p>
              </div>
            </div>
            <div className="mt-4 flex items-center justify-between gap-3">
              <span className="c-mute text-sm">{d.yearsExperience ? t("csite.experience", { n: d.yearsExperience }) : d.qualifications?.slice(0, 40)}</span>
              {hrefBook && <Link href={`${hrefBook}?doctorId=${d.id}`} className="c-btn c-btn-sm">{t("clinic.bookNow")}</Link>}
            </div>
            {d.defaultAppointmentFeeMinor ? <p className="c-mute mt-1 text-sm">{t("csite.fee")}: <b style={{ color: "var(--brand)" }}>{money(d.defaultAppointmentFeeMinor, d.currency ?? "EGP", locale)}</b></p> : null}
          </Reveal>
        ))}
      </div>
    </section>
  );
}

/** Services as an elegant price list (luxury, personal brand, wellness). */
export function ServicesList({ services, t, locale, hrefBook }: { services: ClinicService[]; t: T; locale: Locale; hrefBook?: string }) {
  if (!services.length) return null;
  return (
    <section id="services" className="c-container pt-24">
      <SectionHead eyebrow={t("clinic.services")} title={t("csite.servicesTitle")} sub={t("csite.servicesSub")} />
      <Reveal className="mx-auto max-w-3xl">
        <ul className="c-price-list divide-y divide-[var(--c-line)] border-y border-[var(--c-line)]">
          {services.map((s) => (
            <li key={s.id} className="group flex flex-wrap items-baseline gap-x-4 gap-y-1 py-5">
              <div className="min-w-0 flex-1">
                <h3 className="c-display text-xl">{s.name}</h3>
                <p className="c-mute mt-0.5 text-sm">{[s.description, `${s.durationMinutes} ${t("clinic.minutes")}`].filter(Boolean).join(" · ")}</p>
              </div>
              <span className="c-leader hidden flex-1 border-b border-dotted border-[var(--c-line)] sm:block" aria-hidden />
              {s.priceMinor ? <b className="c-display text-xl" style={{ color: "var(--brand)" }}>{money(s.priceMinor, s.currency, locale)}</b> : null}
              {hrefBook && <Link href={`${hrefBook}?serviceId=${s.id}`} className="c-mute flex items-center gap-1 text-sm font-semibold transition group-hover:text-[var(--brand)]">{t("clinic.bookNow")}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Link>}
            </li>
          ))}
        </ul>
      </Reveal>
    </section>
  );
}

/** Medical centre: departments (from the doctors' specialties) and a filterable doctor directory. */
export function DoctorsDirectory({ doctors, t, locale, hrefBook }: { doctors: ClinicDoctor[]; t: T; locale: Locale; hrefBook?: string }) {
  if (!doctors.length) return null;
  const rows = doctors.map((d) => ({
    id: d.id, name: d.displayName, specialties: specialtyNames(d, locale), photo: d.profileImageFileId ? fileUrl(d.profileImageFileId, "medium") : null,
    years: d.yearsExperience ?? 0, fee: d.defaultAppointmentFeeMinor ? money(d.defaultAppointmentFeeMinor, d.currency ?? "EGP", locale) : null, qualifications: d.qualifications ?? "",
  }));
  return (
    <section id="doctors" className="c-container pt-24">
      <SectionHead eyebrow={t("csite.departments")} title={t("csite.findDoctor")} sub={t("csite.findDoctorSub")} />
      <DoctorDirectory rows={rows} hrefBook={hrefBook} labels={{ all: t("csite.allDepartments"), book: t("clinic.bookNow"), years: t("csite.statYears"), fee: t("csite.fee"), none: t("csite.noDoctorsDept"), search: t("csite.searchDoctor") }} />
    </section>
  );
}
