import { Outfit, Readex_Pro } from "next/font/google";
import Link from "next/link";
import type { ReactNode } from "react";
import { VisitTracker } from "@/components/visit-tracker";
import { KxLink, KxMenu } from "../shared/kit-client";
import { KxArrow, KxAreas, KxCta, KxFaq, KxPhoto, KxServices, kitTemplate } from "../shared/kit";
import { initials, type LnCtx } from "../shared/ctx";
import { CountUp, Reveal } from "../shared/motion";
import type { LnTemplate } from "../types";
import "./ln-digital.css";

const outfit = Outfit({ display: "swap", preload: false, subsets: ["latin"], weight: ["400", "500", "600", "700"], variable: "--dg-f-outfit" });
const readex = Readex_Pro({ display: "swap", preload: false, subsets: ["arabic", "latin"], weight: ["400", "500", "600", "700"], variable: "--dg-f-readex" });
const fonts = [outfit, readex].map((f) => f.variable).join(" ");

/** ln-digital: a modern, online-first practice. Floating pill navigation, gradient hero, a bento grid, and a bottom action bar on phones. */
function Shell({ ctx, children }: { ctx: LnCtx; children: ReactNode }) {
  const { t, p, nav, name } = ctx;
  return (
    <div className={`kx dg ${fonts}`}>
      <a href="#dg-main" className="kx-skip">{t("lnx.skip")}</a>
      <header className="dg-head"><div className="dg-pill">
        <Link href="/" className="dg-brand"><span className="dg-mark">{ctx.logo ? /* eslint-disable-next-line @next/next/no-img-element */ <img src={ctx.logo} alt="" /> : initials(ctx.firm)}</span><b>{name}</b></Link>
        <nav className="dg-nav" aria-label={t("lnx.menu")}>{nav.map((n) => <KxLink key={n.key} href={n.href}>{n.label}</KxLink>)}</nav>
        <a href={ctx.langHref} className="dg-lang">{ctx.locale === "ar" ? "EN" : "عربي"}</a>
        {ctx.book && <Link href="/book" className="kx-btn dg-cta">{t("lws.book")}</Link>}
        <KxMenu nav={nav} book={ctx.book} bookLabel={t("lws.bookCta")} className="dg-burger" />
      </div></header>
      {!ctx.inFrame && <VisitTracker />}
      <main id="dg-main">{children}</main>
      <footer className="dg-foot"><div className="kx-wrap">
        <div className="dg-foot-card">
          <div><p className="dg-foot-name">{name}</p>{p.headline && <p style={{ opacity: .75, marginBlockStart: 6 }}>{p.headline}</p>}</div>
          <div className="dg-foot-links">{nav.map((n) => <Link key={n.key} href={n.href}>{n.label}</Link>)}{ctx.book && <Link href="/booking">{t("lws.track")}</Link>}</div>
          <div className="dg-foot-links">{p.phone && <a href={`tel:${p.phone}`} dir="ltr">{p.phone}</a>}{p.email && <a href={`mailto:${p.email}`}>{p.email}</a>}{ctx.address && <span>{ctx.address}</span>}<Link href="/dashboard/login">{t("lws.staff")}</Link></div>
        </div>
        <p className="dg-copy">© {ctx.year} {name}</p>
      </div></footer>
      {(ctx.book || ctx.waHref) && <div className="dg-dock">{ctx.book && <Link href="/book" className="kx-btn">{t("lws.bookCta")}</Link>}{ctx.waHref && <a href={ctx.waHref} target="_blank" rel="noopener noreferrer" className="kx-btn kx-btn-line">{t("lws.chat")}</a>}</div>}
    </div>
  );
}

function Home({ ctx }: { ctx: LnCtx }) {
  const { t, p } = ctx; const l = ctx.lawyers[0];
  return (
    <>
      <section className="dg-hero"><div className="kx-wrap">
        <Reveal kind="pop">
          <span className="dg-badge">{p.barLevel || t("lws.aboutEyebrow")}</span>
          <h1>{p.title && <small>{p.title}</small>}<span>{p.displayName}</span></h1>
          <p className="dg-lede">{p.headline || t("lws.bioDefault")}</p>
          <div className="kx-cta-row" style={{ justifyContent: "center" }}>{ctx.book && <Link href="/book" className="kx-btn">{t("lws.bookCta")}<KxArrow /></Link>}{ctx.waHref && <a href={ctx.waHref} target="_blank" rel="noopener noreferrer" className="kx-btn kx-btn-line">{t("lws.chat")}</a>}</div>
        </Reveal>
        {p.practiceAreas.length > 0 && <div className="dg-chips"><KxAreas ctx={ctx} areas={p.practiceAreas} v="chips" /></div>}
      </div></section>

      <section className="kx-sec"><div className="kx-wrap dg-bento">
        <Reveal className="dg-b dg-b-me">
          <div className="dg-b-photo"><KxPhoto ctx={ctx} /></div>
          <div><p className="kx-eyebrow">{t("lws.aboutEyebrow")}</p><h2>{l.name}</h2><p className="dg-b-bio">{l.bio || t("lws.bioDefault")}</p><div className="kx-cta-row"><Link href="/about" className="kx-btn kx-btn-line">{t("lnx.more")}<KxArrow /></Link></div></div>
        </Reveal>
        {ctx.stats.slice(0, 2).map((s, i) => <Reveal key={i} className={`dg-b dg-b-stat dg-b-s${i}`}><b><CountUp text={s.value} /></b><span>{s.label}</span></Reveal>)}
        {p.testimonials[0] && <Reveal className="dg-b dg-b-q"><q>{p.testimonials[0].text}</q><cite>{p.testimonials[0].name} {"★".repeat(Math.max(0, Math.min(5, p.testimonials[0].rating)))}</cite></Reveal>}
        {l.languages.length > 0 && <Reveal className="dg-b dg-b-lang"><p className="kx-eyebrow">{t("lws.languages")}</p><div className="kx-tags">{l.languages.map((x) => <span key={x}>{x}</span>)}</div></Reveal>}
        {p.testimonials.length > 1 && <Link href="/reviews" className="dg-b dg-b-more">{t("lws.reviewsTitle")}<KxArrow /></Link>}
      </div></section>

      {p.services.length > 0 && <section className="kx-sec"><div className="kx-wrap"><div className="kx-h"><p className="kx-eyebrow">{t("lws.servicesEyebrow")}</p><h2>{t("lws.servicesTitle")}</h2></div><KxServices ctx={ctx} v="cards" limit={3} /></div></section>}
      {p.faq.length > 0 && <section className="kx-sec"><div className="kx-wrap"><div className="kx-h"><p className="kx-eyebrow">{t("lws.faqEyebrow")}</p><h2>{t("lws.faqTitle")}</h2></div><KxFaq ctx={ctx} limit={4} /></div></section>}
      <KxCta ctx={ctx} />
    </>
  );
}

export const lnDigital: LnTemplate = { Shell, Home, ...kitTemplate({ areas: "chips", services: "cards", reviews: "grid" }) };
