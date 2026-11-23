import Link from "next/link";
import { money } from "@/lib/format";
import { specNames } from "../shared/data";
import { ContactCards, DoctorCard, FaqList, Gallery, HoursTable, PrimaryAction, SecondaryAction, SectionHead, StatusPill } from "../shared/parts";
import { Fade, Photo } from "../shared/ui";
import type { Ctx } from "../shared/kit";

const paras = (s?: string) => (s ?? "").split(/\n+/).map((x) => x.trim()).filter(Boolean);

/** Boutique home: an editorial opening, the lead doctor presented like a profile in a magazine, services as a numbered menu, then a quiet visit block. */
export function BoutiqueHome({ c }: { c: Ctx }) {
  const { p, t, locale } = c;
  const lead = p.doctors[0];
  const rest = p.doctors.slice(1, 4);
  const about = paras(p.about);
  return (
    <>
      <section className="bq-hero">
        <div className="cn-wrap bq-hero-grid">
          <div className="bq-hero-copy">
            {p.establishedYear && <p className="cn-eyebrow">{t("cn.est", { year: p.establishedYear })}</p>}
            <h1 className="cn-h1">{p.tagline || c.name}</h1>
            {p.tagline && <p className="cn-lead">{c.name}</p>}
            <div className="cn-cta-row"><PrimaryAction c={c} /><SecondaryAction c={c} /></div>
            <div className="bq-status"><StatusPill c={c} /></div>
          </div>
          <Photo id={lead?.profileImageFileId ?? p.coverFileId} alt="" variant="medium" name={lead?.displayName} className="bq-hero-img" eager />
        </div>
      </section>

      {lead && (
        <section className="cn-section bq-lead">
          <div className="cn-wrap bq-lead-grid">
            <Fade className="bq-lead-copy">
              <p className="cn-eyebrow">{specNames(lead, locale).join(" · ") || t("cn.ourTeam")}</p>
              <h2 className="cn-h2">{lead.displayName}</h2>
              {paras(lead.bio)[0] && <p className="bq-pull">{paras(lead.bio)[0]}</p>}
              {lead.defaultAppointmentFeeMinor ? <p className="cn-muted">{t("cn.fee")} {money(lead.defaultAppointmentFeeMinor, lead.currency || "EGP", locale)}</p> : null}
              <div className="cn-cta-row"><Link href={`/doctors/${lead.id}`} className="cn-btn cn-btn-ghost">{t("cn.viewProfile")}</Link>{c.book && <Link href={`${c.book}?doctor=${lead.id}`} className="cn-btn">{t("clinic.book")}</Link>}</div>
            </Fade>
          </div>
        </section>
      )}

      {about[0] && (
        <section className="cn-section cn-alt"><div className="cn-wrap cn-narrow bq-story">
          <p className="cn-eyebrow">{t("cn.nav.about")}</p>
          <p className="bq-pull">{about[0]}</p>
          <Link href="/about" className="cn-link">{t("cn.readMore")}</Link>
        </div></section>
      )}

      {p.services.length > 0 && (
        <section className="cn-section"><div className="cn-wrap">
          <SectionHead eyebrow={t("clinic.services")} title={t("cn.servicesTitle")} />
          <ol className="bq-menu">{p.services.slice(0, 8).map((s, i) => (
            <li key={s.id}><b aria-hidden>{String(i + 1).padStart(2, "0")}</b><div><h3 className="cn-h3">{s.name}</h3>{s.description && <p className="cn-muted">{s.description}</p>}<small className="cn-muted">{s.durationMinutes} {t("clinic.minutes")}</small></div><span className="cn-price">{s.priceMinor ? money(s.priceMinor, s.currency, locale) : ""}</span></li>
          ))}</ol>
          {p.services.length > 8 && <p><Link href="/services" className="cn-link">{t("cn.seeAll")}</Link></p>}
        </div></section>
      )}

      {rest.length > 0 && (
        <section className="cn-section cn-alt"><div className="cn-wrap">
          <SectionHead title={t("cn.ourTeam")} action={p.doctors.length > 4 ? <Link href="/doctors" className="cn-link">{t("cn.seeAll")}</Link> : undefined} />
          <div className="cn-grid cn-grid-3">{rest.map((d) => <DoctorCard key={d.id} c={c} d={d} />)}</div>
        </div></section>
      )}

      {(p.gallery?.length ?? 0) > 0 && <section className="cn-section"><div className="cn-wrap"><Gallery c={c} /></div></section>}

      <section className="cn-section bq-visit"><div className="cn-wrap bq-visit-grid">
        <div><p className="cn-eyebrow">{t("csite.visit")}</p><h2 className="cn-h2">{t("csite.visitTitle")}</h2><ContactCards c={c} /></div>
        {p.workingHours && <div className="cn-card"><h3 className="cn-h3">{t("cn.hours")}</h3><HoursTable c={c} hours={p.workingHours} /></div>}
      </div></section>

      {(p.faqs?.length ?? 0) > 0 && <section className="cn-section cn-alt"><div className="cn-wrap cn-narrow"><SectionHead title={t("cn.faqTitle")} /><FaqList c={c} /></div></section>}
    </>
  );
}
