import Link from "next/link";
import { Icon } from "@/components/icons";
import { maxYears, specialtiesOf } from "../shared/data";
import { ContactCards, DoctorCard, FaqList, HoursTable, InsuranceChips, PrimaryAction, SecondaryAction, SectionHead, ServiceRow, SpecialtyCard, StatusPill } from "../shared/parts";
import { Fade, Photo } from "../shared/ui";
import type { Ctx } from "../shared/kit";

const first = (s?: string) => (s ?? "").split(/\n+/).map((x) => x.trim()).find(Boolean) ?? "";

/** Institute home: formal hero with the cover photo, a facts strip, a numbered department grid, the doctors, services as a price list and a navy visit panel. */
export function InstituteHome({ c }: { c: Ctx }) {
  const { p, t, locale } = c;
  const specs = specialtiesOf(p, locale);
  const years = maxYears(p);
  const facts = [
    p.establishedYear && { n: String(p.establishedYear), l: t("cn.stat.since") },
    p.doctors.length > 0 && { n: String(p.doctors.length), l: t("cn.stat.doctors") },
    specs.length > 0 && { n: String(specs.length), l: t("cn.stat.specialties") },
    !p.establishedYear && years > 0 && { n: String(years), l: t("cn.stat.years") },
    p.branches.length > 1 && { n: String(p.branches.length), l: t("cn.stat.branches") },
  ].filter(Boolean) as { n: string; l: string }[];
  const intro = first(p.about);
  const hasVisit = !!(p.workingHours || p.addressText || c.phone || p.email);
  return (
    <>
      <section className="in-hero">
        <div className="cn-wrap in-hero-grid">
          <div className="in-hero-copy">
            {p.establishedYear && <p className="cn-eyebrow">{t("cn.est", { year: p.establishedYear })}</p>}
            <h1 className="cn-h1">{c.name}</h1>
            {p.tagline && <p className="cn-lead">{p.tagline}</p>}
            <div className="cn-cta-row"><PrimaryAction c={c} /><SecondaryAction c={c} /></div>
            <div className="in-hero-status"><StatusPill c={c} /></div>
          </div>
          <div className="in-hero-media">
            <Photo id={p.coverFileId} alt="" variant="medium" className="in-hero-img" eager fallback={<Icon name="building" className="in-hero-ic" />} />
          </div>
        </div>
      </section>

      {facts.length > 0 && (
        <section className="in-facts" aria-label={c.name}>
          <dl className="cn-wrap in-facts-row">{facts.map((f) => <div key={f.l}><dd>{f.n}</dd><dt>{f.l}</dt></div>)}</dl>
        </section>
      )}

      {specs.length > 0 && (
        <section className="cn-section">
          <div className="cn-wrap">
            <SectionHead eyebrow={t("cn.nav.specialties")} title={t("cn.specialtiesTitle")} lead={t("cn.specialtiesLead")} action={specs.length > 1 ? <Link href="/specialties" className="cn-link">{t("cn.seeAll")}</Link> : undefined} />
            <div className="cn-grid cn-grid-3 in-depts">{specs.slice(0, 6).map((s, i) => <Fade key={s.key} delay={i * 50}><div className="in-dept"><b aria-hidden>{String(i + 1).padStart(2, "0")}</b><SpecialtyCard c={c} s={s} /></div></Fade>)}</div>
          </div>
        </section>
      )}

      {intro && (
        <section className="cn-section cn-alt">
          <div className="cn-wrap in-about">
            <div>
              <p className="cn-eyebrow">{t("cn.nav.about")}</p>
              <h2 className="cn-h2">{t("cn.aboutTitle", { name: c.name })}</h2>
              <p className="cn-lead">{intro}</p>
              <p><Link href="/about" className="cn-link">{t("cn.readMore")}</Link></p>
            </div>
            {(p.gallery?.length ?? 0) > 0 && <Photo id={p.gallery![0]} alt="" variant="medium" className="in-about-img" />}
          </div>
        </section>
      )}

      {p.doctors.length > 0 && (
        <section className="cn-section">
          <div className="cn-wrap">
            <SectionHead eyebrow={t("cn.ourTeam")} title={t("clinic.doctors")} action={p.doctors.length > 3 ? <Link href="/doctors" className="cn-link">{t("cn.seeAll")}</Link> : undefined} />
            <div className="cn-grid cn-grid-3">{p.doctors.slice(0, 6).map((d, i) => <Fade key={d.id} delay={(i % 3) * 70}><DoctorCard c={c} d={d} /></Fade>)}</div>
          </div>
        </section>
      )}

      {p.services.length > 0 && (
        <section className="cn-section cn-alt">
          <div className="cn-wrap">
            <SectionHead eyebrow={t("clinic.services")} title={t("cn.servicesTitle")} action={p.services.length > 6 ? <Link href="/services" className="cn-link">{t("cn.seeAll")}</Link> : undefined} />
            <ul className="cn-svc-list">{p.services.slice(0, 6).map((s) => <ServiceRow key={s.id} c={c} s={s} />)}</ul>
          </div>
        </section>
      )}

      {(p.insurance?.length ?? 0) > 0 && (
        <section className="cn-section"><div className="cn-wrap"><SectionHead title={t("cn.insurance")} /><InsuranceChips c={c} /></div></section>
      )}

      {hasVisit && (
        <section className="in-visit">
          <div className="cn-wrap in-visit-grid">
            <div>
              <p className="cn-eyebrow">{t("csite.visit")}</p>
              <h2 className="cn-h2">{t("csite.visitTitle")}</h2>
              <ContactCards c={c} />
            </div>
            {p.workingHours && <div className="in-visit-hours"><h3 className="cn-h3">{t("cn.hours")}</h3><HoursTable c={c} hours={p.workingHours} /></div>}
          </div>
        </section>
      )}

      {(p.faqs?.length ?? 0) > 0 && (
        <section className="cn-section">
          <div className="cn-wrap cn-narrow">
            <SectionHead eyebrow={t("csite.faqEyebrow")} title={t("cn.faqTitle")} />
            <FaqList c={{ ...c, p: { ...p, faqs: p.faqs!.slice(0, 5) } }} />
            {p.faqs!.length > 5 && <p><Link href="/faq" className="cn-link">{t("cn.seeAll")}</Link></p>}
          </div>
        </section>
      )}

      <section className="in-cta">
        <div className="cn-wrap in-cta-row">
          <h2 className="cn-h2">{c.book ? t("cn.ctaBook") : t("cn.ctaCall")}</h2>
          <div className="cn-cta-row"><PrimaryAction c={c} /><SecondaryAction c={c} /></div>
        </div>
      </section>
    </>
  );
}
