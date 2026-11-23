import Link from "next/link";
import { Icon } from "@/components/icons";
import { SocialLinks, hasSocial } from "@/components/social-icons";
import { money } from "@/lib/format";
import { DAYS } from "@/lib/hours";
import { fileUrl } from "@/lib/media";
import { directionsUrl, hourRows, hoursText, specNames, specialtiesOf, statusLine, todayKey } from "./data";
import { NavToggle } from "./nav";
import { Credit } from "./chrome";
import { AuthLink, Photo } from "./ui";
import type { Ctx } from "./kit";
import type { ClinicDoctor, ClinicService } from "@/lib/clinic-site";

/* eslint-disable @next/next/no-img-element */

export const waUrl = (n: string) => `https://wa.me/${n.replace(/\D/g, "")}`;

export function navLinks(c: Ctx) {
  const { p, t } = c;
  const L: { href: string; label: string }[] = [];
  if (p.about) L.push({ href: "/about", label: t("cn.nav.about") });
  if (p.doctors.length) L.push({ href: "/doctors", label: t("clinic.doctors") });
  if (specialtiesOf(p, c.locale).length > 1) L.push({ href: "/specialties", label: t("cn.nav.specialties") });
  if (p.services.length) L.push({ href: "/services", label: t("clinic.services") });
  if (p.faqs?.length) L.push({ href: "/faq", label: t("csite.faqEyebrow") });
  L.push({ href: "/contact", label: t("csite.contact") });
  return L;
}

export function Brand({ c, small }: { c: Ctx; small?: boolean }) {
  return (
    <Link href="/" className="cn-brand" aria-label={c.name}>
      {c.logo && <img src={fileUrl(c.logo, "thumb")} alt="" className="cn-logo" />}
      <span className={small ? "cn-brand-name cn-brand-sm" : "cn-brand-name"}>{c.name}</span>
    </Link>
  );
}

export const LangSwitch = ({ c }: { c: Ctx }) => <a className="cn-lang" href={`/api/lang?l=${c.locale === "ar" ? "en" : "ar"}`}>{c.locale === "ar" ? "EN" : "عربي"}</a>;

/** Primary action: Book when online booking is on, otherwise call (and WhatsApp) lead. */
export function PrimaryAction({ c, ghost, className = "" }: { c: Ctx; ghost?: boolean; className?: string }) {
  const { t, phone } = c;
  const cls = `cn-btn ${ghost ? "cn-btn-ghost" : ""} ${className}`;
  if (c.book) return <Link href={c.book} className={cls}><Icon name="calendar" className="cn-ic" />{t("clinic.book")}</Link>;
  if (phone) return <a href={`tel:${phone}`} className={cls}><Icon name="phone" className="cn-ic" />{t("csite.call")}</a>;
  return <Link href="/contact" className={cls}>{t("csite.contact")}</Link>;
}

export function SecondaryAction({ c, className = "" }: { c: Ctx; className?: string }) {
  const { t, p, phone } = c;
  const cls = `cn-btn cn-btn-ghost ${className}`;
  if (c.book && phone) return <a href={`tel:${phone}`} className={cls}><Icon name="phone" className="cn-ic" />{t("csite.call")}</a>;
  if (!c.book && p.whatsapp) return <a href={waUrl(p.whatsapp)} target="_blank" rel="noopener" className={cls}><Icon name="chat" className="cn-ic" />{t("cn.whatsapp")}</a>;
  return <Link href="/contact" className={cls}>{t("csite.contact")}</Link>;
}

/** Header actions shared by every shell: language, sign in, primary action. */
export function HeaderTools({ c }: { c: Ctx }) {
  return (
    <div className="cn-tools">
      <LangSwitch c={c} />
      <AuthLink className="cn-signin" portal={c.portal} />
      <PrimaryAction c={c} className="cn-btn-sm cn-hide-sm" />
    </div>
  );
}

export function MobileMenu({ c }: { c: Ctx }) {
  return <NavToggle links={navLinks(c)} label={c.t("cn.menu")} closeLabel={c.t("cn.menuClose")}><PrimaryAction c={c} /><AuthLink className="cn-btn cn-btn-ghost" portal={c.portal} /></NavToggle>;
}

export function StatusPill({ c }: { c: Ctx }) {
  const s = statusLine(c.p, c.t);
  if (!s) return null;
  return <span className="cn-status" data-open={s.open || undefined}><i />{s.text}</span>;
}

/** Bottom bar on phones: the one thing a visitor came for. */
export function Dock({ c }: { c: Ctx }) {
  const { t, phone, p } = c;
  return (
    <div className="cn-dock">
      {phone && <a href={`tel:${phone}`}><Icon name="phone" className="cn-ic" />{t("csite.call")}</a>}
      {p.whatsapp && <a href={waUrl(p.whatsapp)} target="_blank" rel="noopener"><Icon name="chat" className="cn-ic" />{t("cn.whatsapp")}</a>}
      {c.book && <Link href={c.book} className="cn-dock-main"><Icon name="calendar" className="cn-ic" />{t("clinic.book")}</Link>}
    </div>
  );
}

export function Footer({ c }: { c: Ctx }) {
  const { p, t, phone } = c;
  const links = navLinks(c);
  return (
    <footer className="cn-footer">
      <div className="cn-wrap cn-footer-grid">
        <div>
          <Brand c={c} />
          {p.tagline && <p className="cn-muted cn-footer-tag">{p.tagline}</p>}
          {hasSocial(p) && <SocialLinks p={p} className="cn-social" size={36} />}
        </div>
        <ul className="cn-footer-list">
          {p.addressText && <li><Icon name="pin" className="cn-ic" />{p.addressText}</li>}
          {phone && <li><Icon name="phone" className="cn-ic" /><a href={`tel:${phone}`} dir="ltr">{phone}</a></li>}
          {p.email && <li><Icon name="mail" className="cn-ic" /><a href={`mailto:${p.email}`}>{p.email}</a></li>}
        </ul>
        <nav className="cn-footer-nav" aria-label={c.name}>{links.map((l) => <Link key={l.href} href={l.href}>{l.label}</Link>)}</nav>
      </div>
      <div className="cn-wrap cn-footer-base">
        <span>© {new Date().getFullYear()} {c.name}. {t("footer.rights")}</span>
        <Credit ctx={c} />
      </div>
    </footer>
  );
}

export function SectionHead({ eyebrow, title, lead, action }: { eyebrow?: string; title: string; lead?: string; action?: React.ReactNode }) {
  return (
    <header className="cn-sechead">
      <div>
        {eyebrow && <p className="cn-eyebrow">{eyebrow}</p>}
        <h2 className="cn-h2">{title}</h2>
        {lead && <p className="cn-lead">{lead}</p>}
      </div>
      {action}
    </header>
  );
}

export function PageHead({ c, eyebrow, title, lead, crumbs }: { c: Ctx; eyebrow?: string; title: string; lead?: string; crumbs?: { href?: string; label: string }[] }) {
  return (
    <section className="cn-pagehead">
      <div className="cn-wrap">
        <nav className="cn-crumbs" aria-label="breadcrumb">
          <Link href="/">{c.t("cn.nav.home")}</Link>
          {crumbs?.map((x, i) => <span key={i}> <b aria-hidden>/</b> {x.href ? <Link href={x.href}>{x.label}</Link> : <span>{x.label}</span>}</span>)}
        </nav>
        {eyebrow && <p className="cn-eyebrow">{eyebrow}</p>}
        <h1 className="cn-h1">{title}</h1>
        {lead && <p className="cn-lead">{lead}</p>}
      </div>
    </section>
  );
}

export function DoctorCard({ c, d, wide }: { c: Ctx; d: ClinicDoctor; wide?: boolean }) {
  const { t, locale } = c;
  const specs = specNames(d, locale);
  const fee = d.defaultAppointmentFeeMinor;
  return (
    <article className={`cn-doc ${wide ? "cn-doc-wide" : ""}`}>
      <Link href={`/doctors/${d.id}`} className="cn-doc-photo" aria-label={d.displayName}>
        <Photo id={d.profileImageFileId} alt="" name={d.displayName} className="cn-doc-img" />
      </Link>
      <div className="cn-doc-body">
        <h3 className="cn-h3"><Link href={`/doctors/${d.id}`}>{d.displayName}</Link></h3>
        {specs.length > 0 && <p className="cn-doc-spec">{specs.join(" · ")}</p>}
        <p className="cn-doc-meta cn-muted">
          {d.yearsExperience ? <span>{t("cn.years", { n: d.yearsExperience })}</span> : null}
          {fee ? <span>{t("cn.fee")} {money(fee, d.currency || "EGP", locale)}</span> : null}
        </p>
        <div className="cn-doc-actions">
          <Link href={`/doctors/${d.id}`} className="cn-link">{t("cn.viewProfile")}</Link>
          {c.book && <Link href={`${c.book}?doctor=${d.id}`} className="cn-btn cn-btn-sm">{t("clinic.book")}</Link>}
        </div>
      </div>
    </article>
  );
}

export function ServiceRow({ c, s }: { c: Ctx; s: ClinicService }) {
  const { t, locale } = c;
  return (
    <li className="cn-svc">
      <div>
        <h3 className="cn-h3">{s.name}</h3>
        {s.description && <p className="cn-muted cn-svc-desc">{s.description}</p>}
        <p className="cn-svc-meta cn-muted"><Icon name="clock" className="cn-ic" />{s.durationMinutes} {t("clinic.minutes")}</p>
      </div>
      <div className="cn-svc-end">
        <strong className="cn-price">{s.priceMinor ? money(s.priceMinor, s.currency, locale) : t("cn.priceOnRequest")}</strong>
        {c.book && <Link href={`${c.book}?service=${s.id}`} className="cn-link">{t("clinic.book")}</Link>}
      </div>
    </li>
  );
}

export function SpecialtyCard({ c, s }: { c: Ctx; s: ReturnType<typeof specialtiesOf>[number] }) {
  return (
    <Link href={`/specialties/${s.key}`} className="cn-spec">
      <span className="cn-spec-ic"><Icon name="stethoscope" className="cn-ic" /></span>
      <h3 className="cn-h3">{s.name}</h3>
      <p className="cn-muted">{c.t("cn.doctorsCount", { n: s.doctors.length })}</p>
    </Link>
  );
}

export function HoursTable({ c, hours }: { c: Ctx; hours: Ctx["p"]["workingHours"] }) {
  const rows = hourRows(hours);
  if (!rows.length) return null;
  const today = todayKey();
  return (
    <dl className="cn-hours">
      {DAYS.map((d) => { const r = rows.find((x) => x.day === d)!; return (
        <div key={d} data-today={d === today || undefined} data-closed={r.closed || undefined}><dt>{c.t(`day.${d}`)}</dt><dd dir="ltr">{hoursText(r, c.t)}</dd></div>
      ); })}
    </dl>
  );
}

export function FaqList({ c }: { c: Ctx }) {
  const f = c.p.faqs ?? [];
  if (!f.length) return null;
  return <div className="cn-faq">{f.map((x, i) => <details key={i}><summary>{x.q}</summary><p>{x.a}</p></details>)}</div>;
}

export function Gallery({ c }: { c: Ctx }) {
  const g = c.p.gallery ?? [];
  if (!g.length) return null;
  return <div className="cn-gallery">{g.slice(0, 6).map((id, i) => <Photo key={id + i} id={id} alt="" className="cn-gal-img" />)}</div>;
}

export function ContactCards({ c }: { c: Ctx }) {
  const { p, t, phone } = c;
  const dir = directionsUrl(p);
  return (
    <ul className="cn-contact">
      {phone && <li><Icon name="phone" className="cn-ic" /><div><span className="cn-muted">{t("csite.call")}</span><a href={`tel:${phone}`} dir="ltr">{phone}</a></div></li>}
      {p.whatsapp && <li><Icon name="chat" className="cn-ic" /><div><span className="cn-muted">{t("cn.whatsapp")}</span><a href={waUrl(p.whatsapp)} target="_blank" rel="noopener" dir="ltr">{p.whatsapp}</a></div></li>}
      {p.email && <li><Icon name="mail" className="cn-ic" /><div><span className="cn-muted">{t("cn.email")}</span><a href={`mailto:${p.email}`}>{p.email}</a></div></li>}
      {p.addressText && <li><Icon name="pin" className="cn-ic" /><div><span className="cn-muted">{t("cn.address")}</span><span>{p.addressText}</span>{dir && <a href={dir} target="_blank" rel="noopener" className="cn-link">{t("cn.directions")}</a>}</div></li>}
    </ul>
  );
}

export function BranchList({ c }: { c: Ctx }) {
  const { p, t } = c;
  if (!p.branches.length) return null;
  return (
    <div className="cn-branches">
      {p.branches.map((b) => {
        const addr = [b.addressLine1, b.addressLine2, b.district, b.city].filter(Boolean).join("، ");
        return (
          <section key={b.id} className="cn-card cn-branch">
            <h3 className="cn-h3">{b.name}</h3>
            {addr && <p className="cn-muted">{addr}</p>}
            {b.landmark && <p className="cn-muted">{b.landmark}</p>}
            {b.phone && <p><a href={`tel:${b.phone}`} dir="ltr">{b.phone}</a></p>}
            {b.mapsUrl && <a href={b.mapsUrl} target="_blank" rel="noopener" className="cn-link">{t("cn.directions")}</a>}
            <HoursTable c={c} hours={b.workingHours} />
          </section>
        );
      })}
    </div>
  );
}

export function InsuranceChips({ c }: { c: Ctx }) {
  const i = c.p.insurance ?? [];
  if (!i.length) return null;
  return <ul className="cn-chips">{i.map((x) => <li key={x}>{x}</li>)}</ul>;
}
