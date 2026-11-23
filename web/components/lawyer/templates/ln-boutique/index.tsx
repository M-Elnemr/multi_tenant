import { Amiri, Cormorant_Garamond, Tajawal } from "next/font/google";
import Link from "next/link";
import type { ReactNode } from "react";
import { VisitTracker } from "@/components/visit-tracker";
import { KxLink, KxMenu } from "../shared/kit-client";
import { KxArrow, KxAreas, KxCta, KxFaq, KxPhoto, KxQuotes, KxServices, kitTemplate, kxNum } from "../shared/kit";
import { initials, type LnCtx } from "../shared/ctx";
import { Reveal } from "../shared/motion";
import type { LnTemplate } from "../types";
import "./ln-boutique.css";

const corm = Cormorant_Garamond({ display: "swap", preload: false, subsets: ["latin"], weight: ["400", "500", "600"], style: ["normal", "italic"], variable: "--lb-f-corm" });
const amiri = Amiri({ display: "swap", preload: false, subsets: ["arabic"], weight: ["400", "700"], variable: "--lb-f-amiri" });
const taj = Tajawal({ display: "swap", preload: false, subsets: ["arabic"], weight: ["400", "500", "700"], variable: "--lb-f-taj" });
const fonts = [corm, amiri, taj].map((f) => f.variable).join(" ");

/** ln-boutique: a small, personal practice. Centred masthead, arched portrait, soft plum on blush, one big quote. */
function Shell({ ctx, children }: { ctx: LnCtx; children: ReactNode }) {
  const { t, p, nav, name } = ctx;
  return (
    <div className={`kx lb ${fonts}`}>
      <a href="#lb-main" className="kx-skip">{t("lnx.skip")}</a>
      <header className="lb-head">
        <div className="kx-wrap lb-top">
          <div className="lb-top-l"><a href={ctx.langHref}>{ctx.locale === "ar" ? "EN" : "عربي"}</a></div>
          <Link href="/" className="lb-brand">
            <span className="lb-mark">{ctx.logo ? /* eslint-disable-next-line @next/next/no-img-element */ <img src={ctx.logo} alt="" /> : initials(ctx.firm)}</span>
            <b>{name}</b>{p.barLevel && <small>{p.barLevel}</small>}
          </Link>
          <div className="lb-top-r">{ctx.book ? <Link href="/book" className="kx-btn lb-cta">{t("lws.book")}</Link> : null}<KxMenu nav={nav} book={ctx.book} bookLabel={t("lws.bookCta")} className="lb-burger" /></div>
        </div>
        <nav className="kx-wrap lb-nav" aria-label={t("lnx.menu")}>{nav.map((n) => <KxLink key={n.key} href={n.href}>{n.label}</KxLink>)}</nav>
      </header>
      {!ctx.inFrame && <VisitTracker />}
      <main id="lb-main">{children}</main>
      <footer className="lb-foot"><div className="kx-wrap">
        <p className="lb-foot-name">{name}</p>
        <div className="lb-foot-row">
          <div>{p.phone && <a href={`tel:${p.phone}`} dir="ltr">{p.phone}</a>}{p.email && <a href={`mailto:${p.email}`}>{p.email}</a>}{ctx.address && <span>{ctx.address}</span>}</div>
          <div>{nav.map((n) => <Link key={n.key} href={n.href}>{n.label}</Link>)}{ctx.book && <Link href="/booking">{t("lws.track")}</Link>}</div>
          <div><Link href="/dashboard/login">{t("lws.staff")}</Link>{Object.entries(p.social ?? {}).filter(([, v]) => v).map(([k, v]) => <a key={k} href={v} target="_blank" rel="noopener noreferrer" style={{ textTransform: "capitalize" }}>{k}</a>)}</div>
        </div>
        <p className="lb-copy">© {ctx.year} {name}</p>
      </div></footer>
      {ctx.waHref && <a href={ctx.waHref} target="_blank" rel="noopener noreferrer" aria-label="WhatsApp" className="kx-wa"><svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M20.5 3.5A11.8 11.8 0 0 0 1.9 17.6L.4 23.5l6-1.6A11.8 11.8 0 0 0 20.5 3.5Zm-8.6 18.2c-1.8 0-3.5-.5-5-1.4l-.4-.2-3.6.9 1-3.5-.2-.4a9.8 9.8 0 1 1 8.2 4.6Z" /></svg></a>}
    </div>
  );
}

function Home({ ctx }: { ctx: LnCtx }) {
  const { t, p } = ctx; const l = ctx.lawyers[0];
  return (
    <>
      <section className="lb-hero"><div className="kx-wrap lb-hero-g">
        <Reveal kind="pop" className="lb-arch"><KxPhoto ctx={ctx} /></Reveal>
        <Reveal>
          <p className="kx-eyebrow">{p.barLevel || t("lws.aboutEyebrow")}</p>
          <h1>{p.title && <small>{p.title}</small>}{p.displayName}</h1>
          <p className="lb-lede">{p.headline || t("lws.bioDefault")}</p>
          <div className="kx-cta-row">{ctx.book && <Link href="/book" className="kx-btn">{t("lws.bookCta")}<KxArrow /></Link>}{ctx.waHref && <a href={ctx.waHref} target="_blank" rel="noopener noreferrer" className="kx-btn kx-btn-line">{t("lws.chat")}</a>}</div>
          {ctx.stats.length > 0 && <ul className="lb-figs">{ctx.stats.slice(0, 3).map((s, i) => <li key={i}><b>{s.value}</b><span>{s.label}</span></li>)}</ul>}
        </Reveal>
      </div></section>

      {p.practiceAreas.length > 0 && <section className="kx-sec"><div className="kx-wrap">
        <div className="kx-h" style={{ textAlign: "center", justifyItems: "center" }}><p className="kx-eyebrow">{t("lws.areasEyebrow")}</p><h2>{t("lws.areasTitle")}</h2></div>
        <KxAreas ctx={ctx} areas={p.practiceAreas.slice(0, 6)} v="cards" />
        <div className="kx-cta-row" style={{ justifyContent: "center" }}><Link href="/areas" className="kx-btn kx-btn-line">{t("lnx.all")}<KxArrow /></Link></div>
      </div></section>}

      <section className="kx-sec lb-letter"><div className="kx-wrap lb-letter-g">
        <div><p className="kx-eyebrow">{t("lws.aboutEyebrow")}</p><h2>{l.name}</h2></div>
        <div><p className="lb-bio">{l.bio || t("lws.bioDefault")}</p><div className="kx-cta-row"><Link href="/about" className="kx-btn kx-btn-line">{t("lnx.more")}<KxArrow /></Link></div></div>
      </div></section>

      {p.testimonials.length > 0 && <section className="kx-sec"><div className="kx-wrap"><KxQuotes ctx={ctx} v="single" /></div></section>}

      {p.services.length > 0 && <section className="kx-sec kx-alt"><div className="kx-wrap">
        <div className="kx-h"><p className="kx-eyebrow">{t("lws.servicesEyebrow")}</p><h2>{t("lws.servicesTitle")}</h2></div>
        <KxServices ctx={ctx} v="cards" limit={3} />
      </div></section>}

      {p.faq.length > 0 && <section className="kx-sec"><div className="kx-wrap"><div className="kx-h"><p className="kx-eyebrow">{t("lws.faqEyebrow")}</p><h2>{t("lws.faqTitle")}</h2></div><KxFaq ctx={ctx} limit={4} /></div></section>}
      <KxCta ctx={ctx} />
      <span hidden>{kxNum(0)}</span>
    </>
  );
}

export const lnBoutique: LnTemplate = { Shell, Home, ...kitTemplate({ areas: "cards", services: "cards", reviews: "single" }) };
