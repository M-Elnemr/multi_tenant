import Link from "next/link";
import { Icon } from "@/components/icons";
import { directionsUrl, specialtiesOf, statusLine } from "../shared/data";
import { ContactCards, DoctorCard, FaqList, Gallery, HoursTable, PrimaryAction, SecondaryAction, SectionHead, ServiceRow, waUrl } from "../shared/parts";
import { Fade, Photo } from "../shared/ui";
import type { Ctx } from "../shared/kit";

/** Family home: warm and reassuring. Three big "know before you come" tiles built from real data (hours, place, phone), services as large cards, friendly doctor cards. */
export function FamilyHome({ c }: { c: Ctx }) {
  const { p, t, locale } = c;
  const st = statusLine(p, t);
  const dir = directionsUrl(p);
  const specs = specialtiesOf(p, locale);
  const tiles = [
    st && { icon: "clock" as const, title: t("cn.hours"), text: st.text, href: "/contact" },
    p.addressText && { icon: "pin" as const, title: t("cn.address"), text: p.addressText, href: dir || "/contact" },
    c.phone && { icon: "phone" as const, title: t("csite.call"), text: c.phone, href: `tel:${c.phone}` },
    !c.phone && p.whatsapp && { icon: "chat" as const, title: t("cn.whatsapp"), text: p.whatsapp, href: waUrl(p.whatsapp) },
  ].filter(Boolean) as { icon: "clock" | "pin" | "phone" | "chat"; title: string; text: string; href: string }[];
  return (
    <>
      <section className="fm-hero">
        <div className="cn-wrap fm-hero-grid">
          <div>
            <h1 className="cn-h1">{c.name}</h1>
            {p.tagline && <p className="cn-lead">{p.tagline}</p>}
            <div className="cn-cta-row"><PrimaryAction c={c} /><SecondaryAction c={c} /></div>
          </div>
          <div className="fm-blob"><Photo id={p.coverFileId} alt="" variant="medium" className="fm-hero-img" eager fallback={<Icon name="heart" className="fm-heart" />} /></div>
        </div>
        {tiles.length > 0 && <div className="cn-wrap"><ul className="fm-tiles">{tiles.map((x) => <li key={x.title}><a href={x.href} {...(x.href.startsWith("http") ? { target: "_blank", rel: "noopener" } : {})}><span className="fm-tile-ic"><Icon name={x.icon} className="cn-ic" /></span><div><b>{x.title}</b><span dir="auto">{x.text}</span></div></a></li>)}</ul></div>}
      </section>

      {p.services.length > 0 && (
        <section className="cn-section"><div className="cn-wrap">
          <SectionHead eyebrow={t("clinic.services")} title={t("cn.servicesTitle")} action={p.services.length > 6 ? <Link href="/services" className="cn-link">{t("cn.seeAll")}</Link> : undefined} />
          <ul className="cn-svc-list fm-svcs">{p.services.slice(0, 6).map((s) => <ServiceRow key={s.id} c={c} s={s} />)}</ul>
        </div></section>
      )}

      {p.doctors.length > 0 && (
        <section className="cn-section cn-alt"><div className="cn-wrap">
          <SectionHead eyebrow={t("cn.ourTeam")} title={t("clinic.doctors")} action={p.doctors.length > 3 ? <Link href="/doctors" className="cn-link">{t("cn.seeAll")}</Link> : undefined} />
          <div className="cn-grid cn-grid-3">{p.doctors.slice(0, 6).map((d, i) => <Fade key={d.id} delay={(i % 3) * 70}><DoctorCard c={c} d={d} /></Fade>)}</div>
          {specs.length > 1 && <ul className="cn-chips cn-chips-links fm-specs">{specs.map((s) => <li key={s.key}><Link href={`/specialties/${s.key}`}>{s.name}</Link></li>)}</ul>}
        </div></section>
      )}

      {(p.gallery?.length ?? 0) > 0 && <section className="cn-section"><div className="cn-wrap"><SectionHead title={t("cn.gallery")} /><Gallery c={c} /></div></section>}

      <section className="cn-section cn-alt"><div className="cn-wrap fm-visit">
        <div><SectionHead eyebrow={t("csite.visit")} title={t("csite.visitTitle")} /><ContactCards c={c} /></div>
        {p.workingHours && <div className="cn-card"><h3 className="cn-h3">{t("cn.hours")}</h3><HoursTable c={c} hours={p.workingHours} /></div>}
      </div></section>

      {(p.faqs?.length ?? 0) > 0 && <section className="cn-section"><div className="cn-wrap cn-narrow"><SectionHead title={t("cn.faqTitle")} /><FaqList c={c} /></div></section>}
    </>
  );
}
