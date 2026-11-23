import Link from "next/link";
import { Icon } from "@/components/icons";
import { money } from "@/lib/format";
import { lowestFee, maxYears, specNames, specialtiesOf } from "../shared/data";
import { ContactCards, FaqList, HoursTable, PrimaryAction, SecondaryAction, SectionHead, ServiceRow, StatusPill } from "../shared/parts";
import { Fade, Photo } from "../shared/ui";
import type { Ctx } from "../shared/kit";

/** Medtech home: booking-first. The hero carries a booking panel built from the clinic's real services; doctors are a dense list with a book action on every row. */
export function MedtechHome({ c }: { c: Ctx }) {
  const { p, t, locale } = c;
  const specs = specialtiesOf(p, locale);
  const years = maxYears(p);
  const cheapest = lowestFee(p);
  const metrics = [
    p.doctors.length > 0 && { n: String(p.doctors.length), l: t("cn.stat.doctors") },
    specs.length > 0 && { n: String(specs.length), l: t("cn.stat.specialties") },
    years > 0 && { n: String(years), l: t("cn.stat.years") },
    p.services.length > 0 && { n: String(p.services.length), l: t("clinic.services") },
  ].filter(Boolean) as { n: string; l: string }[];
  return (
    <>
      <section className="mt-hero">
        <div className="cn-wrap mt-hero-grid">
          <div>
            {p.establishedYear && <p className="cn-eyebrow">{t("cn.est", { year: p.establishedYear })}</p>}
            <h1 className="cn-h1">{c.name}</h1>
            {p.tagline && <p className="cn-lead">{p.tagline}</p>}
            <div className="cn-cta-row"><PrimaryAction c={c} /><SecondaryAction c={c} /></div>
            <div className="mt-hero-status"><StatusPill c={c} /></div>
          </div>
          <aside className="mt-panel" aria-label={t("clinic.book")}>
            <h2 className="cn-h3">{c.book ? t("cn.mt.quick") : t("csite.contact")}</h2>
            {p.services.length > 0 ? (
              <ul className="mt-panel-list">{p.services.slice(0, 4).map((s) => (
                <li key={s.id}><Link href={c.book ? `${c.book}?service=${s.id}` : "/services"}><span>{s.name}<small>{s.durationMinutes} {t("clinic.minutes")}</small></span><b>{s.priceMinor ? money(s.priceMinor, s.currency, locale) : ""}</b></Link></li>
              ))}</ul>
            ) : <ContactCards c={c} />}
            {cheapest?.priceMinor && <p className="cn-muted mt-from">{t("cn.mt.from", { price: money(cheapest.priceMinor, cheapest.currency, locale) })}</p>}
          </aside>
        </div>
      </section>

      {metrics.length > 0 && <section className="mt-metrics"><dl className="cn-wrap mt-metrics-row">{metrics.map((m) => <div key={m.l}><dd>{m.n}</dd><dt>{m.l}</dt></div>)}</dl></section>}

      {p.doctors.length > 0 && (
        <section className="cn-section"><div className="cn-wrap">
          <SectionHead eyebrow={t("cn.ourTeam")} title={t("clinic.doctors")} action={<Link href="/doctors" className="cn-link">{t("cn.seeAll")}</Link>} />
          <ul className="mt-rows">{p.doctors.slice(0, 8).map((d, i) => (
            <Fade as="li" key={d.id} delay={Math.min(i, 4) * 40} className="mt-row-doc">
              <Photo id={d.profileImageFileId} alt="" name={d.displayName} variant="thumb" className="mt-av" />
              <div><h3 className="cn-h3"><Link href={`/doctors/${d.id}`}>{d.displayName}</Link></h3><p className="cn-muted">{specNames(d, locale).join(" · ")}</p></div>
              {d.defaultAppointmentFeeMinor ? <span className="mt-fee">{money(d.defaultAppointmentFeeMinor, d.currency || "EGP", locale)}</span> : <span />}
              {c.book ? <Link href={`${c.book}?doctor=${d.id}`} className="cn-btn cn-btn-sm">{t("clinic.book")}</Link> : <Link href={`/doctors/${d.id}`} className="cn-btn cn-btn-sm cn-btn-ghost">{t("cn.viewProfile")}</Link>}
            </Fade>
          ))}</ul>
        </div></section>
      )}

      {specs.length > 0 && (
        <section className="cn-section cn-alt"><div className="cn-wrap">
          <SectionHead eyebrow={t("cn.nav.specialties")} title={t("cn.specialtiesTitle")} />
          <ul className="cn-chips mt-specs">{specs.map((s) => <li key={s.key}><Link href={`/specialties/${s.key}`}><Icon name="stethoscope" className="cn-ic" />{s.name}<small>{s.doctors.length}</small></Link></li>)}</ul>
        </div></section>
      )}

      {p.services.length > 0 && (
        <section className="cn-section"><div className="cn-wrap">
          <SectionHead eyebrow={t("clinic.services")} title={t("cn.servicesTitle")} action={p.services.length > 6 ? <Link href="/services" className="cn-link">{t("cn.seeAll")}</Link> : undefined} />
          <ul className="cn-svc-list">{p.services.slice(0, 6).map((s) => <ServiceRow key={s.id} c={c} s={s} />)}</ul>
        </div></section>
      )}

      <section className="cn-section cn-alt"><div className="cn-wrap mt-visit">
        <div><SectionHead eyebrow={t("csite.visit")} title={t("csite.visitTitle")} /><ContactCards c={c} /></div>
        {p.workingHours && <div className="cn-card"><h3 className="cn-h3">{t("cn.hours")}</h3><HoursTable c={c} hours={p.workingHours} /></div>}
      </div></section>

      {(p.faqs?.length ?? 0) > 0 && <section className="cn-section"><div className="cn-wrap cn-narrow"><SectionHead title={t("cn.faqTitle")} /><FaqList c={c} /></div></section>}
    </>
  );
}
