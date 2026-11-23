import Link from "next/link";
import { money } from "@/lib/format";
import { specNames } from "../shared/data";
import { ContactCards, FaqList, HoursTable, PrimaryAction, SecondaryAction, StatusPill } from "../shared/parts";
import { Fade, Photo } from "../shared/ui";
import type { Ctx } from "../shared/kit";

const first = (s?: string) => (s ?? "").split(/\n+/).map((x) => x.trim()).find(Boolean) ?? "";

/** Atelier home: a full-height image opening, doctors as large alternating image-led spreads, services as a restrained list, a single quiet visit block. */
export function AtelierHome({ c }: { c: Ctx }) {
  const { p, t, locale } = c;
  const about = first(p.about);
  return (
    <>
      <section className="at-hero">
        <Photo id={p.coverFileId} alt="" variant="original" className="at-hero-img" eager fallback={null} />
        <div className="at-hero-shade" />
        <div className="cn-wrap at-hero-copy">
          {p.establishedYear && <p className="cn-eyebrow">{t("cn.est", { year: p.establishedYear })}</p>}
          <h1 className="cn-h1">{c.name}</h1>
          {p.tagline && <p className="cn-lead">{p.tagline}</p>}
          <div className="cn-cta-row"><PrimaryAction c={c} /><SecondaryAction c={c} /></div>
          <div className="at-status"><StatusPill c={c} /></div>
        </div>
      </section>

      {about && <section className="cn-section"><div className="cn-wrap cn-narrow at-about"><p className="at-pull">{about}</p><Link href="/about" className="cn-link">{t("cn.readMore")}</Link></div></section>}

      {p.doctors.slice(0, 3).map((d, i) => (
        <section key={d.id} className="at-spread" data-flip={i % 2 === 1 || undefined}>
          <div className="cn-wrap at-spread-grid">
            <Fade className="at-spread-media"><Photo id={d.profileImageFileId} alt={d.displayName} variant="medium" name={d.displayName} className="at-spread-img" /></Fade>
            <Fade delay={120} className="at-spread-copy">
              <p className="cn-eyebrow">{specNames(d, locale).join(" · ")}</p>
              <h2 className="cn-h2">{d.displayName}</h2>
              {first(d.bio) && <p className="cn-lead">{first(d.bio)}</p>}
              <div className="cn-cta-row"><Link href={`/doctors/${d.id}`} className="cn-btn cn-btn-ghost">{t("cn.viewProfile")}</Link>{c.book && <Link href={`${c.book}?doctor=${d.id}`} className="cn-btn">{t("clinic.book")}</Link>}</div>
            </Fade>
          </div>
        </section>
      ))}
      {p.doctors.length > 3 && <p className="at-more"><Link href="/doctors" className="cn-link">{t("cn.seeAll")} ({p.doctors.length})</Link></p>}

      {p.services.length > 0 && (
        <section className="cn-section cn-alt"><div className="cn-wrap cn-narrow">
          <p className="cn-eyebrow">{t("clinic.services")}</p>
          <h2 className="cn-h2">{t("cn.servicesTitle")}</h2>
          <ul className="at-list">{p.services.slice(0, 6).map((s) => <li key={s.id}><span>{s.name}<small>{s.durationMinutes} {t("clinic.minutes")}</small></span><b>{s.priceMinor ? money(s.priceMinor, s.currency, locale) : ""}</b></li>)}</ul>
          {p.services.length > 6 && <p><Link href="/services" className="cn-link">{t("cn.seeAll")}</Link></p>}
        </div></section>
      )}

      <section className="cn-section"><div className="cn-wrap at-visit">
        <div><p className="cn-eyebrow">{t("csite.visit")}</p><h2 className="cn-h2">{t("csite.visitTitle")}</h2><ContactCards c={c} /></div>
        {p.workingHours && <div><h3 className="cn-h3">{t("cn.hours")}</h3><HoursTable c={c} hours={p.workingHours} /></div>}
      </div></section>

      {(p.faqs?.length ?? 0) > 0 && <section className="cn-section cn-alt"><div className="cn-wrap cn-narrow"><h2 className="cn-h2 at-faq-h">{t("cn.faqTitle")}</h2><FaqList c={c} /></div></section>}
    </>
  );
}
