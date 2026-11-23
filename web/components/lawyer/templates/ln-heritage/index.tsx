import { EB_Garamond, Libre_Caslon_Text, Noto_Naskh_Arabic } from "next/font/google";
import Link from "next/link";
import type { ReactNode } from "react";
import { VisitTracker } from "@/components/visit-tracker";
import { KxLink, KxMenu } from "../shared/kit-client";
import { KxArrow, KxAreas, KxCta, KxFaq, KxPhoto, KxQuotes, KxServices, kitTemplate } from "../shared/kit";
import { initials, type LnCtx } from "../shared/ctx";
import { Reveal } from "../shared/motion";
import type { LnTemplate } from "../types";
import "./ln-heritage.css";

const garamond = EB_Garamond({ display: "swap", preload: false, subsets: ["latin"], weight: ["400", "500", "600"], style: ["normal", "italic"], variable: "--hr-f-gar" });
const caslon = Libre_Caslon_Text({ display: "swap", preload: false, subsets: ["latin"], weight: ["400", "700"], variable: "--hr-f-cas" });
const naskh = Noto_Naskh_Arabic({ display: "swap", preload: false, subsets: ["arabic"], weight: ["400", "500", "700"], variable: "--hr-f-nask" });
const fonts = [garamond, caslon, naskh].map((f) => f.variable).join(" ");

const Orn = () => <span className="hr-orn" aria-hidden="true">❖</span>;

/** ln-heritage: a long-established chambers. Centred emblem under double rules, framed pages, Roman numerals, a drop-cap biography. */
function Shell({ ctx, children }: { ctx: LnCtx; children: ReactNode }) {
  const { t, p, nav, name } = ctx;
  return (
    <div className={`kx hr ${fonts}`}>
      <a href="#hr-main" className="kx-skip">{t("lnx.skip")}</a>
      <header className="hr-head">
        <div className="kx-wrap">
          <div className="hr-strip"><span>{p.barLevel}{p.barNumber ? ` · ${p.barNumber}` : ""}</span><span className="hr-strip-r">{p.phone && <a href={`tel:${p.phone}`} dir="ltr">{p.phone}</a>}<a href={ctx.langHref}>{ctx.locale === "ar" ? "EN" : "عربي"}</a></span></div>
          <Link href="/" className="hr-brand">
            <span className="hr-seal">{ctx.logo ? /* eslint-disable-next-line @next/next/no-img-element */ <img src={ctx.logo} alt="" /> : initials(ctx.firm)}</span>
            <b>{name}</b>
          </Link>
          <div className="hr-nav-row">
            <nav className="hr-nav" aria-label={t("lnx.menu")}>{nav.map((n) => <KxLink key={n.key} href={n.href}>{n.label}</KxLink>)}</nav>
            <KxMenu nav={nav} book={ctx.book} bookLabel={t("lws.bookCta")} className="hr-burger" />
          </div>
        </div>
      </header>
      {!ctx.inFrame && <VisitTracker />}
      <main id="hr-main">{children}</main>
      <footer className="hr-foot"><div className="kx-wrap">
        <Orn /><p className="hr-foot-name">{name}</p>
        <div className="hr-foot-g">
          <div><h4>{t("lws.nav.contact")}</h4>{p.phone && <a href={`tel:${p.phone}`} dir="ltr">{p.phone}</a>}{p.email && <a href={`mailto:${p.email}`}>{p.email}</a>}{ctx.address && <span>{ctx.address}</span>}</div>
          <div><h4>{t("lws.hours")}</h4>{ctx.hasHours ? ctx.hours.filter((h) => h.rows.length).slice(0, 5).map((h) => <span key={h.w}>{h.name}: <span dir="ltr">{h.rows.join(" · ")}</span></span>) : <span>{t("lws.closed")}</span>}</div>
          <div><h4>{t("lnx.menu")}</h4>{nav.map((n) => <Link key={n.key} href={n.href}>{n.label}</Link>)}{ctx.book && <Link href="/booking">{t("lws.track")}</Link>}</div>
        </div>
        <p className="hr-copy">© {ctx.year} {name} · <Link href="/dashboard/login">{t("lws.staff")}</Link></p>
      </div></footer>
      {ctx.waHref && <a href={ctx.waHref} target="_blank" rel="noopener noreferrer" aria-label="WhatsApp" className="kx-wa"><svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M20.5 3.5A11.8 11.8 0 0 0 1.9 17.6L.4 23.5l6-1.6A11.8 11.8 0 0 0 20.5 3.5Zm-8.6 18.2c-1.8 0-3.5-.5-5-1.4l-.4-.2-3.6.9 1-3.5-.2-.4a9.8 9.8 0 1 1 8.2 4.6Z" /></svg></a>}
    </div>
  );
}

function Home({ ctx }: { ctx: LnCtx }) {
  const { t, p } = ctx; const l = ctx.lawyers[0];
  const steps = [1, 2, 3].map((i) => ({ n: i, h: t(`lws.step${i}`), d: t(`lws.step${i}d`) }));
  return (
    <>
      <section className="hr-hero"><div className="kx-wrap"><Reveal kind="slow">
        <div className="hr-frame">
          <p className="kx-eyebrow">{p.barLevel || t("lws.aboutEyebrow")}</p>
          <h1>{p.title && <small>{p.title}</small>}{p.displayName}</h1>
          <Orn />
          <p className="hr-lede">{p.headline || t("lws.bioDefault")}</p>
          <div className="kx-cta-row" style={{ justifyContent: "center" }}>{ctx.book && <Link href="/book" className="kx-btn">{t("lws.bookCta")}<KxArrow /></Link>}{ctx.waHref && <a href={ctx.waHref} target="_blank" rel="noopener noreferrer" className="kx-btn kx-btn-line">{t("lws.chat")}</a>}</div>
          {ctx.stats.length > 0 && <ul className="hr-figs">{ctx.stats.slice(0, 3).map((s, i) => <li key={i}><b>{s.value}</b><span>{s.label}</span></li>)}</ul>}
        </div>
      </Reveal></div></section>

      <section className="kx-sec"><div className="kx-wrap hr-about">
        <div className="hr-portrait"><KxPhoto ctx={ctx} /></div>
        <div><p className="kx-eyebrow">{t("lws.aboutEyebrow")}</p><h2>{l.name}</h2><Orn /><p className="hr-bio">{l.bio || t("lws.bioDefault")}</p><div className="kx-cta-row"><Link href="/about" className="kx-btn kx-btn-line">{t("lnx.more")}<KxArrow /></Link></div></div>
      </div></section>

      {p.practiceAreas.length > 0 && <section className="kx-sec kx-alt"><div className="kx-wrap">
        <div className="kx-h" style={{ textAlign: "center", justifyItems: "center" }}><p className="kx-eyebrow">{t("lws.areasEyebrow")}</p><h2>{t("lws.areasTitle")}</h2><Orn /></div>
        <KxAreas ctx={ctx} areas={p.practiceAreas.slice(0, 6)} v="list" roman />
      </div></section>}

      {p.testimonials.length > 0 && <section className="kx-sec"><div className="kx-wrap"><KxQuotes ctx={ctx} v="single" /></div></section>}

      <section className="kx-sec kx-alt"><div className="kx-wrap hr-steps">
        <div className="kx-h" style={{ textAlign: "center", justifyItems: "center" }}><p className="kx-eyebrow">{t("lws.servicesEyebrow")}</p><h2>{t("lws.servicesTitle")}</h2><Orn /></div>
        {p.services.length > 0 && <KxServices ctx={ctx} v="timeline" limit={4} />}
        <ol className="hr-proc">{steps.map((s) => <li key={s.n}><b>{s.h}</b><p>{s.d}</p></li>)}</ol>
      </div></section>

      {p.faq.length > 0 && <section className="kx-sec"><div className="kx-wrap"><div className="kx-h" style={{ textAlign: "center", justifyItems: "center" }}><p className="kx-eyebrow">{t("lws.faqEyebrow")}</p><h2>{t("lws.faqTitle")}</h2><Orn /></div><div style={{ display: "grid", justifyItems: "center" }}><KxFaq ctx={ctx} limit={4} /></div></div></section>}
      <KxCta ctx={ctx} />
    </>
  );
}

export const lnHeritage: LnTemplate = { Shell, Home, ...kitTemplate({ areas: "list", services: "timeline", reviews: "single", roman: true }) };
