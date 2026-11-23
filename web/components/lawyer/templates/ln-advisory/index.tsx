import { Cairo, IBM_Plex_Sans, Source_Serif_4 } from "next/font/google";
import Link from "next/link";
import type { ReactNode } from "react";
import { VisitTracker } from "@/components/visit-tracker";
import { money } from "@/lib/format";
import { KxLink, KxMenu } from "../shared/kit-client";
import { KxArrow, KxAreas, KxCta, KxFaq, KxQuotes, KxServices, kitTemplate, kxNum } from "../shared/kit";
import { initials, type LnCtx } from "../shared/ctx";
import { CountUp, Reveal } from "../shared/motion";
import type { LnTemplate } from "../types";
import "./ln-advisory.css";

const serif = Source_Serif_4({ display: "swap", preload: false, subsets: ["latin"], weight: ["400", "600", "700"], variable: "--ad-f-serif" });
const plex = IBM_Plex_Sans({ display: "swap", preload: false, subsets: ["latin"], weight: ["400", "500", "600"], variable: "--ad-f-plex" });
const cairo = Cairo({ display: "swap", preload: false, subsets: ["arabic"], weight: ["400", "600", "700"], variable: "--ad-f-cairo" });
const fonts = [serif, plex, cairo].map((f) => f.variable).join(" ");

/** ln-advisory: a corporate advisory firm. Info rail on top, left-aligned header, a hero with a consultation card, a figures bar, tile grids. */
function Shell({ ctx, children }: { ctx: LnCtx; children: ReactNode }) {
  const { t, p, nav, name } = ctx;
  return (
    <div className={`kx ad ${fonts}`}>
      <a href="#ad-main" className="kx-skip">{t("lnx.skip")}</a>
      <div className="ad-rail"><div className="kx-wrap">
        <span>{ctx.address || p.barLevel}</span>
        <span className="ad-rail-r">{p.phone && <a href={`tel:${p.phone}`} dir="ltr">{p.phone}</a>}<a href={ctx.langHref}>{ctx.locale === "ar" ? "EN" : "عربي"}</a></span>
      </div></div>
      <header className="ad-head"><div className="kx-wrap ad-head-g">
        <Link href="/" className="ad-brand"><span className="ad-mark">{ctx.logo ? /* eslint-disable-next-line @next/next/no-img-element */ <img src={ctx.logo} alt="" /> : initials(ctx.firm)}</span><b>{name}</b></Link>
        <nav className="ad-nav" aria-label={t("lnx.menu")}>{nav.map((n) => <KxLink key={n.key} href={n.href}>{n.label}</KxLink>)}</nav>
        {ctx.book && <Link href="/book" className="kx-btn ad-cta">{t("lws.bookCta")}</Link>}
        <KxMenu nav={nav} book={ctx.book} bookLabel={t("lws.bookCta")} className="ad-burger" />
      </div></header>
      {!ctx.inFrame && <VisitTracker />}
      <main id="ad-main">{children}</main>
      <footer className="ad-foot"><div className="kx-wrap">
        <div className="ad-foot-g">
          <div><p className="ad-foot-name">{name}</p>{p.headline && <p className="ad-foot-sub">{p.headline}</p>}</div>
          <div><h4>{t("lnx.menu")}</h4>{nav.map((n) => <Link key={n.key} href={n.href}>{n.label}</Link>)}</div>
          <div><h4>{t("lws.nav.contact")}</h4>{p.phone && <a href={`tel:${p.phone}`} dir="ltr">{p.phone}</a>}{p.email && <a href={`mailto:${p.email}`}>{p.email}</a>}{ctx.address && <span>{ctx.address}</span>}{ctx.book && <Link href="/booking">{t("lws.track")}</Link>}</div>
        </div>
        <div className="ad-foot-b"><span>© {ctx.year} {name}</span><Link href="/dashboard/login">{t("lws.staff")}</Link></div>
      </div></footer>
      {ctx.waHref && <a href={ctx.waHref} target="_blank" rel="noopener noreferrer" aria-label="WhatsApp" className="kx-wa"><svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M20.5 3.5A11.8 11.8 0 0 0 1.9 17.6L.4 23.5l6-1.6A11.8 11.8 0 0 0 20.5 3.5Zm-8.6 18.2c-1.8 0-3.5-.5-5-1.4l-.4-.2-3.6.9 1-3.5-.2-.4a9.8 9.8 0 1 1 8.2 4.6Z" /></svg></a>}
    </div>
  );
}

function Home({ ctx }: { ctx: LnCtx }) {
  const { t, p, locale, currency } = ctx; const l = ctx.lawyers[0];
  const steps = [1, 2, 3].map((i) => ({ n: i, h: t(`lws.step${i}`), d: t(`lws.step${i}d`) }));
  return (
    <>
      <section className="ad-hero"><div className="kx-wrap ad-hero-g">
        <Reveal>
          <p className="kx-eyebrow">{p.barLevel || t("lws.aboutEyebrow")}</p>
          <h1>{p.title && <small>{p.title} </small>}{p.displayName}</h1>
          <p className="ad-lede">{p.headline || t("lws.bioDefault")}</p>
          <div className="kx-cta-row">{ctx.book && <Link href="/book" className="kx-btn">{t("lws.bookCta")}<KxArrow /></Link>}<Link href="/about" className="kx-btn kx-btn-line ad-ghost">{t("lnx.more")}</Link></div>
        </Reveal>
        <Reveal kind="side" rtl={ctx.locale === "ar"}>
          <aside className="ad-card" aria-label={t("lws.servicesEyebrow")}>
            <p className="kx-eyebrow">{t("lws.servicesEyebrow")}</p>
            {p.services.length > 0 ? <ul>{p.services.slice(0, 3).map((s) => <li key={s.id}><Link href={ctx.book ? `/book?service=${s.id}` : "/services"}><span><b>{s.name}</b><small>{t("lws.minutes", { n: s.durationMinutes })} · {t(`lws.mode.${s.mode}`)}</small></span><em>{s.priceMinor == null ? "" : s.priceMinor === 0 ? t("lws.free") : money(s.priceMinor, currency, locale)}</em></Link></li>)}</ul>
              : <p style={{ color: "var(--text2)" }}>{t("lws.contactText")}</p>}
            {ctx.book && <Link href="/book" className="kx-btn" style={{ inlineSize: "100%" }}>{t("lws.bookCta")}<KxArrow /></Link>}
          </aside>
        </Reveal>
      </div></section>

      {ctx.stats.length > 0 && <div className="ad-bar"><div className="kx-wrap"><ul>{ctx.stats.slice(0, 4).map((s, i) => <li key={i}><b><CountUp text={s.value} /></b><span>{s.label}</span></li>)}</ul></div></div>}

      {p.practiceAreas.length > 0 && <section className="kx-sec"><div className="kx-wrap">
        <div className="kx-h"><p className="kx-eyebrow">{t("lws.areasEyebrow")}</p><h2>{t("lws.areasTitle")}</h2></div>
        <KxAreas ctx={ctx} areas={p.practiceAreas.slice(0, 6)} v="tiles" />
      </div></section>}

      <section className="kx-sec kx-alt"><div className="kx-wrap ad-steps">
        <div><p className="kx-eyebrow">{t("lws.nav.about")}</p><h2>{l.name}</h2><p className="ad-bio">{l.bio || t("lws.bioDefault")}</p></div>
        <ol>{steps.map((s) => <li key={s.n}><span>{kxNum(s.n - 1)}</span><div><b>{s.h}</b><p>{s.d}</p></div></li>)}</ol>
      </div></section>

      {p.services.length > 0 && <section className="kx-sec"><div className="kx-wrap"><div className="kx-h"><p className="kx-eyebrow">{t("lws.servicesEyebrow")}</p><h2>{t("lws.servicesTitle")}</h2></div><KxServices ctx={ctx} v="table" limit={4} /></div></section>}

      {p.testimonials.length > 0 && <section className="kx-sec kx-alt"><div className="kx-wrap"><div className="kx-h"><p className="kx-eyebrow">{t("lws.reviewsEyebrow")}</p><h2>{t("lws.reviewsTitle")}</h2></div><KxQuotes ctx={ctx} v="grid" limit={3} /></div></section>}

      {p.faq.length > 0 && <section className="kx-sec"><div className="kx-wrap ad-faq"><div><p className="kx-eyebrow">{t("lws.faqEyebrow")}</p><h2>{t("lws.faqTitle")}</h2></div><KxFaq ctx={ctx} limit={5} /></div></section>}
      <KxCta ctx={ctx} />
    </>
  );
}

export const lnAdvisory: LnTemplate = { Shell, Home, ...kitTemplate({ areas: "tiles", services: "table", reviews: "grid" }) };
