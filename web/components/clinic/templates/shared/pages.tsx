import Link from "next/link";
import { notFound } from "next/navigation";
import { money } from "@/lib/format";
import { maxYears, specNames, specialtiesOf } from "./data";
import { BranchList, ContactCards, DoctorCard, FaqList, Gallery, HoursTable, InsuranceChips, PageHead, PrimaryAction, SecondaryAction, SectionHead, ServiceRow, SpecialtyCard, StatusPill } from "./parts";
import { Fade, Photo } from "./ui";
import type { Ctx } from "./kit";

/**
 * The inner pages every cn-* template shares. The markup is the same; each template's stylesheet gives it its own look
 * (layout density, type, shapes, colour). Sections with no data are not rendered at all.
 */

const paras = (s?: string) => (s ?? "").split(/\n+/).map((x) => x.trim()).filter(Boolean);

export function AboutPage({ c }: { c: Ctx }) {
  const { p, t, locale } = c;
  if (!p.about && !p.gallery?.length) notFound();
  const years = maxYears(c.p);
  const specs = specialtiesOf(p, locale).length;
  const stats = [
    years > 0 && { n: String(years), l: t("cn.stat.years") },
    p.doctors.length > 0 && { n: String(p.doctors.length), l: t("cn.stat.doctors") },
    specs > 0 && { n: String(specs), l: t("cn.stat.specialties") },
    p.branches.length > 1 && { n: String(p.branches.length), l: t("cn.stat.branches") },
  ].filter(Boolean) as { n: string; l: string }[];
  return (
    <>
      <PageHead c={c} eyebrow={t("cn.nav.about")} title={c.name} lead={p.tagline} crumbs={[{ label: t("cn.nav.about") }]} />
      <section className="cn-section"><div className="cn-wrap cn-about">
        <div className="cn-prose">{paras(p.about).map((x, i) => <p key={i}>{x}</p>)}</div>
        {stats.length > 0 && <dl className="cn-stats">{stats.map((s) => <div key={s.l}><dd>{s.n}</dd><dt>{s.l}</dt></div>)}</dl>}
      </div></section>
      {(p.gallery?.length ?? 0) > 0 && <section className="cn-section cn-alt"><div className="cn-wrap"><SectionHead title={t("cn.gallery")} /><Gallery c={c} /></div></section>}
      {(p.insurance?.length ?? 0) > 0 && <section className="cn-section"><div className="cn-wrap"><SectionHead title={t("cn.insurance")} /><InsuranceChips c={c} /></div></section>}
    </>
  );
}

export function DoctorsPage({ c }: { c: Ctx }) {
  const { p, t, locale } = c;
  if (!p.doctors.length) notFound();
  const specs = specialtiesOf(p, locale);
  return (
    <>
      <PageHead c={c} eyebrow={t("cn.ourTeam")} title={t("clinic.doctors")} lead={t("cn.doctorsLead", { n: p.doctors.length })} crumbs={[{ label: t("clinic.doctors") }]} />
      <section className="cn-section"><div className="cn-wrap">
        {specs.length > 1 && <ul className="cn-chips cn-chips-links">{specs.map((s) => <li key={s.key}><Link href={`/specialties/${s.key}`}>{s.name}</Link></li>)}</ul>}
        <div className="cn-grid cn-grid-3">{p.doctors.map((d, i) => <Fade key={d.id} delay={Math.min(i, 5) * 60}><DoctorCard c={c} d={d} /></Fade>)}</div>
      </div></section>
    </>
  );
}

export function DoctorPage({ c, id }: { c: Ctx; id: string }) {
  const { p, t, locale } = c;
  const d = p.doctors.find((x) => x.id === id);
  if (!d) notFound();
  const specs = specNames(d, locale);
  const fee = d.defaultAppointmentFeeMinor;
  const facts = [
    d.yearsExperience ? { l: t("cn.experience"), v: t("cn.years", { n: d.yearsExperience }) } : null,
    fee ? { l: t("cn.fee"), v: money(fee, d.currency || "EGP", locale) } : null,
    d.consultationDurationMinutes ? { l: t("cn.visitLength"), v: `${d.consultationDurationMinutes} ${t("clinic.minutes")}` } : null,
    d.languages?.length ? { l: t("cn.languages"), v: d.languages.join("، ") } : null,
  ].filter(Boolean) as { l: string; v: string }[];
  const others = p.doctors.filter((x) => x.id !== d.id).slice(0, 3);
  return (
    <>
      <PageHead c={c} title={d.displayName} lead={specs.join(" · ")} crumbs={[{ href: "/doctors", label: t("clinic.doctors") }, { label: d.displayName }]} />
      <section className="cn-section"><div className="cn-wrap cn-profile">
        <Photo id={d.profileImageFileId} alt={d.displayName} variant="medium" name={d.displayName} className="cn-profile-img" eager />
        <div className="cn-profile-body">
          {facts.length > 0 && <dl className="cn-facts">{facts.map((f) => <div key={f.l}><dt>{f.l}</dt><dd>{f.v}</dd></div>)}</dl>}
          {paras(d.bio).length > 0 && <div className="cn-prose">{paras(d.bio).map((x, i) => <p key={i}>{x}</p>)}</div>}
          {d.qualifications && <div className="cn-prose"><h2 className="cn-h3">{t("cn.qualifications")}</h2>{paras(d.qualifications).map((x, i) => <p key={i}>{x}</p>)}</div>}
          <div className="cn-cta-row">
            {c.book ? <Link href={`${c.book}?doctor=${d.id}`} className="cn-btn">{t("cn.bookWith", { name: d.displayName })}</Link> : <PrimaryAction c={c} />}
            {c.book && <SecondaryAction c={c} />}
          </div>
        </div>
      </div></section>
      {others.length > 0 && <section className="cn-section cn-alt"><div className="cn-wrap"><SectionHead title={t("cn.otherDoctors")} /><div className="cn-grid cn-grid-3">{others.map((x) => <DoctorCard key={x.id} c={c} d={x} />)}</div></div></section>}
    </>
  );
}

export function SpecialtiesPage({ c }: { c: Ctx }) {
  const specs = specialtiesOf(c.p, c.locale);
  if (!specs.length) notFound();
  return (
    <>
      <PageHead c={c} eyebrow={c.t("cn.nav.specialties")} title={c.t("cn.specialtiesTitle")} lead={c.t("cn.specialtiesLead")} crumbs={[{ label: c.t("cn.nav.specialties") }]} />
      <section className="cn-section"><div className="cn-wrap"><div className="cn-grid cn-grid-3">{specs.map((s) => <SpecialtyCard key={s.key} c={c} s={s} />)}</div></div></section>
    </>
  );
}

export function SpecialtyPage({ c, specKey }: { c: Ctx; specKey: string }) {
  const s = specialtiesOf(c.p, c.locale).find((x) => x.key === specKey);
  if (!s) notFound();
  return (
    <>
      <PageHead c={c} eyebrow={c.t("cn.nav.specialties")} title={s.name} lead={c.t("cn.doctorsCount", { n: s.doctors.length })} crumbs={[{ href: "/specialties", label: c.t("cn.nav.specialties") }, { label: s.name }]} />
      <section className="cn-section"><div className="cn-wrap"><div className="cn-grid cn-grid-3">{s.doctors.map((d) => <DoctorCard key={d.id} c={c} d={d} />)}</div></div></section>
    </>
  );
}

export function ServicesPage({ c }: { c: Ctx }) {
  if (!c.p.services.length) notFound();
  return (
    <>
      <PageHead c={c} eyebrow={c.t("clinic.services")} title={c.t("cn.servicesTitle")} lead={c.t("cn.servicesLead")} crumbs={[{ label: c.t("clinic.services") }]} />
      <section className="cn-section"><div className="cn-wrap"><ul className="cn-svc-list">{c.p.services.map((s) => <ServiceRow key={s.id} c={c} s={s} />)}</ul></div></section>
    </>
  );
}

export function ContactPage({ c }: { c: Ctx }) {
  const { p, t } = c;
  return (
    <>
      <PageHead c={c} eyebrow={t("csite.contact")} title={t("cn.contactTitle")} lead={t("cn.contactLead")} crumbs={[{ label: t("csite.contact") }]} />
      <section className="cn-section"><div className="cn-wrap cn-contact-grid">
        <div><ContactCards c={c} /><div className="cn-cta-row"><PrimaryAction c={c} /><SecondaryAction c={c} /></div></div>
        {(p.workingHours || p.branches.some((b) => b.workingHours)) && (
          <div className="cn-card"><h2 className="cn-h3">{t("cn.hours")}</h2><StatusPill c={c} /><HoursTable c={c} hours={p.workingHours} /></div>
        )}
      </div></section>
      {p.branches.length > 0 && <section className="cn-section cn-alt"><div className="cn-wrap"><SectionHead title={t("cn.branches")} /><BranchList c={c} /></div></section>}
    </>
  );
}

export function FaqPage({ c }: { c: Ctx }) {
  if (!c.p.faqs?.length) notFound();
  return (
    <>
      <PageHead c={c} eyebrow={c.t("csite.faqEyebrow")} title={c.t("cn.faqTitle")} crumbs={[{ label: c.t("csite.faqEyebrow") }]} />
      <section className="cn-section"><div className="cn-wrap cn-narrow"><FaqList c={c} /><div className="cn-cta-row"><PrimaryAction c={c} /></div></div></section>
    </>
  );
}
