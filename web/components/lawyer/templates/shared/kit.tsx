import Link from "next/link";
import type { ReactNode } from "react";
import { money } from "@/lib/format";
import { IArrow } from "./icons";
import { CountUp, Reveal } from "./motion";
import { initials, type LnArea, type LnCtx } from "./ctx";
import { KxBook, KxTrack } from "./kit-client";
import type { LnTemplate } from "../types";
import "./kit.css";

export const kxNum = (i: number) => String(i + 1).padStart(2, "0");
export const KxArrow = () => <IArrow className="kx-arrow" />;
export type KxOpts = { areas: "cards" | "tiles" | "chips" | "list"; services: "cards" | "table" | "timeline"; reviews: "grid" | "single"; roman?: boolean };
const ROMAN = ["I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII"];

export function KxHead({ ctx, title, sub, crumb }: { ctx: LnCtx; title: string; sub?: string; crumb?: ReactNode }) {
  return <section className="kx-ph"><div className="kx-wrap">
    <nav className="kx-crumb" aria-label="breadcrumb"><Link href="/">{ctx.t("lnx.home")}</Link><span aria-hidden="true">/</span>{crumb ?? <span>{title}</span>}</nav>
    <h1>{title}</h1>{sub && <p>{sub}</p>}
  </div></section>;
}

export function KxCta({ ctx }: { ctx: LnCtx }) {
  const { t, p } = ctx;
  return <section className="kx-cta"><div className="kx-wrap">
    <h2>{t("lws.contactTitle")}</h2>
    <div className="kx-cta-row" style={{ margin: 0 }}>
      {ctx.book && <Link href="/book" className="kx-btn">{t("lws.bookCta")}<KxArrow /></Link>}
      {ctx.waHref && <a href={ctx.waHref} target="_blank" rel="noopener noreferrer" className="kx-btn kx-btn-line">{t("lws.chat")}</a>}
      {!ctx.book && p.phone && <a href={`tel:${p.phone}`} className="kx-btn" dir="ltr">{p.phone}</a>}
    </div>
  </div></section>;
}

export function KxEmpty({ ctx }: { ctx: LnCtx }) {
  return <div className="kx-wrap"><div className="kx-empty" style={{ marginBlock: 56 }}><h3>{ctx.t("lnx.empty.title")}</h3><p>{ctx.t("lnx.empty.text")}</p>{ctx.book && <div className="kx-cta-row" style={{ justifyContent: "center" }}><Link href="/book" className="kx-btn">{ctx.t("lws.bookCta")}<KxArrow /></Link></div>}</div></div>;
}

export function KxPhoto({ ctx, i = 0 }: { ctx: LnCtx; i?: number }) {
  const l = ctx.lawyers[i];
  /* eslint-disable-next-line @next/next/no-img-element */
  return l.photoUrl ? <img src={l.photoUrl} alt={l.name} className="kx-photo" /> : <div className="kx-mono" aria-hidden="true">{initials(l.name)}</div>;
}

export function KxAreas({ ctx, areas, v, roman }: { ctx: LnCtx; areas: LnArea[]; v: KxOpts["areas"]; roman?: boolean }) {
  return <div className="kx-areas" data-v={v}>{areas.map((a, i) => (
    <Reveal key={a.key} delay={Math.min(i, 5) * 0.05}>
      <Link href={`/areas/${encodeURIComponent(a.key)}`} className="kx-area"><i>{roman ? ROMAN[i] ?? kxNum(i) : kxNum(i)}</i><h3>{a.title}</h3><p>{a.description || ctx.t("lws.areaDefault")}</p></Link>
    </Reveal>))}</div>;
}

export function KxServices({ ctx, v, limit }: { ctx: LnCtx; v: KxOpts["services"]; limit?: number }) {
  const { t, locale, currency } = ctx;
  return <div className="kx-svcs" data-v={v}>{ctx.p.services.slice(0, limit).map((s) => {
    const price = s.priceMinor == null ? null : s.priceMinor === 0 ? t("lws.free") : money(s.priceMinor, currency, locale);
    return <div className="kx-svc" key={s.id}>
      <div><h3>{s.name}</h3>{s.description && <p style={{ marginBlockStart: 6 }}>{s.description}</p>}</div>
      <div className="kx-meta"><span>{t("lws.minutes", { n: s.durationMinutes })}</span><span>{t(`lws.mode.${s.mode}`)}</span>{price && <b>{price}</b>}</div>
      {ctx.book ? <div><Link href={`/book?service=${s.id}`} className="kx-btn kx-btn-line">{t("lws.book")}<KxArrow /></Link></div> : <span />}
    </div>;
  })}</div>;
}

export function KxQuotes({ ctx, v, limit }: { ctx: LnCtx; v: KxOpts["reviews"]; limit?: number }) {
  const items = ctx.p.testimonials.slice(0, v === "single" ? 1 : limit);
  return <div className="kx-quotes" data-v={v}>{items.map((q, i) => (
    <Reveal key={i} delay={Math.min(i, 3) * 0.06}><blockquote className="kx-q"><q>{q.text}</q><cite><b>{q.name}</b>{q.rating > 0 && <span aria-label={`${q.rating}/5`}>{"★".repeat(Math.max(0, Math.min(5, q.rating)))}</span>}</cite></blockquote></Reveal>))}</div>;
}

export function KxFaq({ ctx, limit }: { ctx: LnCtx; limit?: number }) {
  return <div className="kx-faq">{ctx.p.faq.slice(0, limit).map((f, i) => <details key={i} open={i === 0}><summary>{f.q}<i aria-hidden="true">+</i></summary><p>{f.a}</p></details>)}</div>;
}

export function KxStats({ ctx }: { ctx: LnCtx }) {
  return ctx.stats.length ? <div className="kx-stats">{ctx.stats.slice(0, 4).map((s, i) => <div key={i}><b><CountUp text={s.value} /></b><span>{s.label}</span></div>)}</div> : null;
}

/** The inner pages every kit-based template shares: structure comes from the data-v options, look from the template's own CSS. */
export function kitTemplate(o: KxOpts): Pick<LnTemplate, "About" | "Team" | "Areas" | "AreaDetail" | "Services" | "Contact" | "Faq" | "Reviews" | "Book" | "Track"> {
  return {
    About: ({ ctx }) => {
      const { t } = ctx; const l = ctx.lawyers[0];
      return <>
        <KxHead ctx={ctx} title={t("lws.nav.about")} sub={ctx.p.headline} />
        <div className="kx-wrap kx-about">
          <div className="kx-stick"><KxPhoto ctx={ctx} />
            <dl className="kx-facts">
              <div><dt>{t("lnx.name")}</dt><dd>{l.name}</dd></div>
              {l.barLevel && <div><dt>{t("lnx.rank")}</dt><dd>{l.barLevel}</dd></div>}
              {l.barNumber && <div><dt>{t("lws.barNo")}</dt><dd dir="ltr">{l.barNumber}</dd></div>}
              {l.languages.length > 0 && <div><dt>{t("lws.languages")}</dt><dd>{l.languages.join("، ")}</dd></div>}
              {l.years > 0 && <div><dt>{t("lws.years")}</dt><dd>{l.years}+</dd></div>}
            </dl>
          </div>
          <div><p className="kx-prose">{l.bio || t("lws.bioDefault")}</p>
            {l.education.length > 0 && <><h3 style={{ marginBlockStart: 40, fontSize: "1.5rem" }}>{t("lnx.education")}</h3><div className="kx-edu">{l.education.map((e, i) => <div key={i}><b>{e.title}</b><span>{e.place}{e.year ? ` · ${e.year}` : ""}</span></div>)}</div></>}
          </div>
        </div>
        {ctx.stats.length > 0 && <section className="kx-sec kx-alt"><div className="kx-wrap"><KxStats ctx={ctx} /></div></section>}
        <KxCta ctx={ctx} />
      </>;
    },
    Team: ({ ctx }) => {
      const { t } = ctx;
      return <>
        <KxHead ctx={ctx} title={t("lnx.team.title")} sub={t("lnx.team.sub")} />
        <section className="kx-sec"><div className="kx-wrap"><div className={`kx-team${ctx.lawyers.length === 1 ? " kx-one" : ""}`}>
          {ctx.lawyers.map((l, i) => <Reveal key={l.key} className="kx-lawyer"><KxPhoto ctx={ctx} i={i} />
            <div style={{ display: "grid", gap: 12 }}><h3>{l.name}</h3>{l.role && <p className="kx-eyebrow">{l.role}</p>}
              {l.bio && <p style={{ color: "var(--text2)", display: "-webkit-box", WebkitLineClamp: 4, WebkitBoxOrient: "vertical", overflow: "hidden", whiteSpace: "pre-line" }}>{l.bio}</p>}
              {l.languages.length > 0 && <div className="kx-tags">{l.languages.map((x) => <span key={x}>{x}</span>)}</div>}
              <div><Link href="/about" className="kx-btn kx-btn-line">{t("lnx.more")}<KxArrow /></Link></div></div>
          </Reveal>)}
        </div></div></section><KxCta ctx={ctx} />
      </>;
    },
    Areas: ({ ctx }) => <>
      <KxHead ctx={ctx} title={ctx.t("lws.areasEyebrow")} sub={ctx.t("lws.areasTitle")} />
      {ctx.p.practiceAreas.length === 0 ? <KxEmpty ctx={ctx} /> : <section className="kx-sec"><div className="kx-wrap"><KxAreas ctx={ctx} areas={ctx.p.practiceAreas} v={o.areas} roman={o.roman} /></div></section>}
      <KxCta ctx={ctx} />
    </>,
    AreaDetail: ({ ctx, area, index }) => {
      const { t, p } = ctx;
      return <>
        <KxHead ctx={ctx} title={area.title} sub={area.description || t("lws.areaDefault")} crumb={<><Link href="/areas">{t("lws.areasEyebrow")}</Link><span aria-hidden="true">/</span><span>{kxNum(index)}</span></>} />
        <div className="kx-wrap kx-split">
          <aside><p className="kx-eyebrow" style={{ marginBlockEnd: 12 }}>{t("lnx.otherAreas")}</p><div className="kx-side">{p.practiceAreas.map((a, i) => <Link key={a.key} href={`/areas/${encodeURIComponent(a.key)}`} aria-current={a.key === area.key ? "page" : undefined}><span>{a.title}</span><span>{kxNum(i)}</span></Link>)}</div></aside>
          <div><p className="kx-prose">{area.description || t("lws.areaDefault")}</p>
            <div className="kx-box" style={{ marginBlockStart: 36 }}><h3 style={{ fontSize: "1.5rem", marginBlockEnd: 10 }}>{t("lnx.area.cta")}</h3><p style={{ color: "var(--text2)" }}>{t("lws.contactText")}</p>
              <div className="kx-cta-row">{ctx.book && <Link href="/book" className="kx-btn">{t("lws.bookCta")}<KxArrow /></Link>}{ctx.waHref && <a href={ctx.waHref} target="_blank" rel="noopener noreferrer" className="kx-btn kx-btn-line">{t("lws.chat")}</a>}</div></div>
          </div>
        </div>
      </>;
    },
    Services: ({ ctx }) => <>
      <KxHead ctx={ctx} title={ctx.t("lws.servicesEyebrow")} sub={ctx.t("lws.servicesTitle")} />
      {ctx.p.services.length === 0 ? <KxEmpty ctx={ctx} /> : <section className="kx-sec"><div className="kx-wrap"><KxServices ctx={ctx} v={o.services} /></div></section>}
      <KxCta ctx={ctx} />
    </>,
    Contact: ({ ctx }) => {
      const { t, p } = ctx;
      return <>
        <KxHead ctx={ctx} title={t("lws.contactEyebrow")} sub={t("lws.contactTitle")} />
        <div className="kx-wrap kx-ct">
          <div className="kx-box"><h3>{t("lnx.office")}</h3>
            {p.phone && <div className="kx-line"><b>{t("lnx.phone")}</b><a href={`tel:${p.phone}`} dir="ltr">{p.phone}</a></div>}
            {p.email && <div className="kx-line"><b>{t("lnx.email")}</b><a href={`mailto:${p.email}`}>{p.email}</a></div>}
            {ctx.address && <div className="kx-line"><b>{t("lnx.address")}</b><span>{ctx.address}</span></div>}
            {p.mapUrl && <div className="kx-line"><b>{t("lnx.map")}</b><a href={p.mapUrl} target="_blank" rel="noopener noreferrer" style={{ color: "var(--signal)" }}>{t("lws.directions")}</a></div>}
            <div className="kx-cta-row">{ctx.book && <Link href="/book" className="kx-btn">{t("lws.bookCta")}<KxArrow /></Link>}{ctx.waHref && <a href={ctx.waHref} target="_blank" rel="noopener noreferrer" className="kx-btn kx-btn-line">{t("lws.chat")}</a>}</div>
          </div>
          <div className="kx-box"><h3>{t("lws.hours")}</h3><dl className="kx-hours">{ctx.hours.map((h) => <div key={h.w}><dt>{h.name}</dt><dd dir="ltr">{h.rows.length ? h.rows.join(" · ") : t("lws.closed")}</dd></div>)}</dl></div>
        </div>
      </>;
    },
    Faq: ({ ctx }) => <><KxHead ctx={ctx} title={ctx.t("lws.faqEyebrow")} sub={ctx.t("lws.faqTitle")} /><section className="kx-sec"><div className="kx-wrap">{ctx.p.faq.length ? <KxFaq ctx={ctx} /> : <KxEmpty ctx={ctx} />}</div></section><KxCta ctx={ctx} /></>,
    Reviews: ({ ctx }) => <><KxHead ctx={ctx} title={ctx.t("lws.reviewsEyebrow")} sub={ctx.t("lws.reviewsTitle")} /><section className="kx-sec"><div className="kx-wrap">{ctx.p.testimonials.length ? <KxQuotes ctx={ctx} v="grid" /> : <KxEmpty ctx={ctx} />}</div></section><KxCta ctx={ctx} /></>,
    Book: ({ ctx }) => <><KxHead ctx={ctx} title={ctx.t("lws.bookTitle", { name: ctx.name })} sub={ctx.t("lws.bookSub")} /><div className="kx-wrap"><KxBook services={ctx.p.services} phone={ctx.p.phone} open={ctx.book} /></div></>,
    Track: ({ ctx }) => <><KxHead ctx={ctx} title={ctx.t("lws.trackTitle")} /><KxTrack /></>,
  };
}
