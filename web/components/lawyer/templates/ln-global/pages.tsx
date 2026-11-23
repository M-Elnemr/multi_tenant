import Link from "next/link";
import type { ReactNode } from "react";
import { money } from "@/lib/format";
import { IArrow } from "../shared/icons";
import { CountUp, Reveal } from "../shared/motion";
import { initials, type LnArea, type LnCtx } from "../shared/ctx";

const num = (i: number) => String(i + 1).padStart(2, "0");
const Arrow = () => <IArrow className="lg-arrow" />;

function PageHead({ ctx, title, sub, crumb }: { ctx: LnCtx; title: string; sub?: string; crumb?: ReactNode }) {
  return (
    <section className="lg-ph"><div className="lg-wrap">
      <nav className="lg-crumb lg-up" aria-label="breadcrumb"><Link href="/">{ctx.t("lnx.home")}</Link><span aria-hidden="true">/</span>{crumb ?? <span>{title}</span>}</nav>
      <h1>{title}</h1>
      {sub && <p>{sub}</p>}
    </div></section>
  );
}

function Cta({ ctx }: { ctx: LnCtx }) {
  const { t, p } = ctx;
  return (
    <section className="lg-cta"><div className="lg-wrap">
      <h2>{t("lws.contactTitle")}</h2>
      <div className="lg-cta-l">
        {ctx.book && <Link href="/book" className="lg-btn">{t("lws.bookCta")}<Arrow /></Link>}
        {ctx.waHref && <a href={ctx.waHref} target="_blank" rel="noopener noreferrer" className="lg-btn lg-btn-line">{t("lws.chat")}</a>}
        {!ctx.book && p.phone && <a href={`tel:${p.phone}`} className="lg-btn" dir="ltr">{p.phone}</a>}
      </div>
    </div></section>
  );
}

function Empty({ ctx, title }: { ctx: LnCtx; title: string }) {
  return <div className="lg-wrap"><div className="lg-empty" style={{ marginBlock: 64 }}><h3>{title}</h3><p>{ctx.t("lnx.empty.text")}</p>{ctx.book && <Link href="/book" className="lg-btn">{ctx.t("lws.bookCta")}<Arrow /></Link>}</div></div>;
}

function Photo({ ctx, i = 0 }: { ctx: LnCtx; i?: number }) {
  const l = ctx.lawyers[i];
  return l.photoUrl
    /* eslint-disable-next-line @next/next/no-img-element */
    ? <div className="lg-fig"><img src={l.photoUrl} alt={l.name} className="lg-photo" /></div>
    : <div className="lg-fig"><div className="lg-fig-ph" aria-hidden="true">{initials(ctx.p.displayName)}</div></div>;
}

function AreaRows({ ctx, areas }: { ctx: LnCtx; areas: LnArea[] }) {
  return (
    <ul className="lg-rows" style={{ padding: 0, margin: 0 }}>
      {areas.map((a, i) => (
        <Reveal as="li" key={a.key} delay={Math.min(i, 5) * 0.05}>
          <Link href={`/areas/${encodeURIComponent(a.key)}`} className="lg-row">
            <span>{num(i)}</span><h3>{a.title}</h3><p>{a.description || ctx.t("lws.areaDefault")}</p><Arrow />
          </Link>
        </Reveal>
      ))}
    </ul>
  );
}

function Quotes({ ctx, limit }: { ctx: LnCtx; limit?: number }) {
  const items = ctx.p.testimonials.slice(0, limit);
  return (
    <div className="lg-quotes">
      {items.map((q, i) => (
        <Reveal key={i} delay={Math.min(i, 3) * 0.06}>
          <blockquote><q>{q.text}</q><cite><b>{q.name}</b>{q.rating > 0 && <span aria-label={`${q.rating}/5`}>{"★".repeat(Math.max(0, Math.min(5, q.rating)))}</span>}</cite></blockquote>
        </Reveal>
      ))}
    </div>
  );
}

function Faqs({ ctx }: { ctx: LnCtx }) {
  return (
    <div className="lg-faq-l">
      {ctx.p.faq.map((f, i) => <details key={i} open={i === 0}><summary>{f.q}<i aria-hidden="true">+</i></summary><p>{f.a}</p></details>)}
    </div>
  );
}

function ServiceTable({ ctx }: { ctx: LnCtx }) {
  const { t, locale, currency } = ctx;
  return (
    <div className="lg-svc">
      {ctx.p.services.map((s) => {
        const price = s.priceMinor == null ? null : s.priceMinor === 0 ? t("lws.free") : money(s.priceMinor, currency, locale);
        return (
          <div className="lg-svc-r" key={s.id}>
            <div><h3>{s.name}</h3>{s.description && <p>{s.description}</p>}</div>
            <div className="lg-svc-m"><span>{t("lws.minutes", { n: s.durationMinutes })}</span><span>{t(`lws.mode.${s.mode}`)}</span>{price && <b>{price}</b>}</div>
            {ctx.book ? <Link href={`/book?service=${s.id}`} className="lg-btn lg-btn-line">{t("lws.book")}<Arrow /></Link> : <span />}
          </div>
        );
      })}
    </div>
  );
}

function LawyerBand({ ctx, wide }: { ctx: LnCtx; wide?: boolean }) {
  const { t } = ctx;
  const l = ctx.lawyers[0];
  const photo = !!l.photoUrl;
  return (
    <div className={`lg-person${photo ? "" : " lg-nophoto"}`}>
      {photo && <Reveal kind="clip"><Photo ctx={ctx} /></Reveal>}
      <Reveal>
        <p className="lg-up lg-signal">{t("lws.aboutEyebrow")}</p>
        <h2 style={{ marginBlockStart: 14 }}>{l.name}</h2>
        <p className="lg-bio" style={wide ? { WebkitLineClamp: "unset", display: "block" } : undefined}>{l.bio || t("lws.bioDefault")}</p>
        <dl className="lg-facts">
          {l.barLevel && <div><dt>{t("lnx.rank")}</dt><dd>{l.barLevel}</dd></div>}
          {l.barNumber && <div><dt>{t("lws.barNo")}</dt><dd dir="ltr">{l.barNumber}</dd></div>}
          {l.languages.length > 0 && <div><dt>{t("lws.languages")}</dt><dd>{l.languages.join("، ")}</dd></div>}
          {l.years > 0 && <div><dt>{t("lws.years")}</dt><dd>{l.years}+</dd></div>}
        </dl>
        {!wide && <Link href="/about" className="lg-btn lg-btn-line" style={{ marginBlockStart: 28 }}>{t("lnx.more")}<Arrow /></Link>}
      </Reveal>
    </div>
  );
}

export function LgHome({ ctx }: { ctx: LnCtx }) {
  const { t, p, name } = ctx;
  const l = ctx.lawyers[0];
  const words = (p.headline || t("lws.bioDefault")).trim();
  return (
    <>
      <section className="lg-hero"><div className="lg-wrap">
        <div className="lg-hero-grid">
          <Reveal kind="clip">
            {p.barLevel && <p className="lg-up lg-signal" style={{ marginBlockEnd: 20 }}>{p.barLevel}</p>}
            <h1>{p.title && <span style={{ display: "block", fontSize: ".4em", fontStyle: "italic", color: "var(--text2)" }}>{p.title}</span>}<em>{p.displayName}</em></h1>
            <p className="lg-deck">{words}</p>
            <div className="lg-hero-cta">
              {ctx.book && <Link href="/book" className="lg-btn">{t("lws.bookCta")}<Arrow /></Link>}
              {ctx.waHref ? <a href={ctx.waHref} target="_blank" rel="noopener noreferrer" className="lg-btn lg-btn-line">{t("lws.chat")}</a>
                : p.phone ? <a href={`tel:${p.phone}`} className="lg-btn lg-btn-line" dir="ltr">{p.phone}</a> : null}
            </div>
          </Reveal>
          <div>
            {p.practiceAreas.length > 0 ? (
              <>
                <div className="lg-index-h lg-up"><span>{t("lws.areasEyebrow")}</span><span>{p.practiceAreas.length}</span></div>
                <ul className="lg-index" style={{ padding: 0, margin: 0 }}>
                  {p.practiceAreas.slice(0, 7).map((a, i) => <li key={a.key}><Link href={`/areas/${encodeURIComponent(a.key)}`}><span>{num(i)}</span>{a.title}<Arrow /></Link></li>)}
                </ul>
              </>
            ) : l.photoUrl ? <div className="lg-hero-photo"><Photo ctx={ctx} /></div> : null}
          </div>
        </div>
        {ctx.stats.length > 0 && (
          <div className="lg-stats">
            {ctx.stats.slice(0, 4).map((s, i) => <div key={i}><b><CountUp text={s.value} /></b><span>{s.label}</span></div>)}
          </div>
        )}
      </div></section>

      {p.practiceAreas.length > 0 && (
        <section className="lg-sec"><div className="lg-wrap">
          <div className="lg-sec-h lg-sec-h-row"><div className="lg-sec-h" style={{ margin: 0 }}><p className="lg-up lg-signal">{t("lws.areasEyebrow")}</p><h2>{t("lws.areasTitle")}</h2></div><Link href="/areas" className="lg-btn lg-btn-line">{t("lnx.all")}<Arrow /></Link></div>
          <AreaRows ctx={ctx} areas={p.practiceAreas.slice(0, 6)} />
        </div></section>
      )}

      <section className="lg-sec lg-sec-dark"><div className="lg-wrap"><LawyerBand ctx={ctx} /></div></section>

      {p.services.length > 0 && (
        <section className="lg-sec"><div className="lg-wrap">
          <div className="lg-sec-h lg-sec-h-row"><div className="lg-sec-h" style={{ margin: 0 }}><p className="lg-up lg-signal">{t("lws.servicesEyebrow")}</p><h2>{t("lws.servicesTitle")}</h2></div><Link href="/services" className="lg-btn lg-btn-line">{t("lnx.all")}<Arrow /></Link></div>
          <ServiceTable ctx={{ ...ctx, p: { ...p, services: p.services.slice(0, 4) } }} />
        </div></section>
      )}

      {p.testimonials.length > 0 && (
        <section className="lg-sec lg-sec-alt"><div className="lg-wrap">
          <div className="lg-sec-h"><p className="lg-up lg-signal">{t("lws.reviewsEyebrow")}</p><h2>{t("lws.reviewsTitle")}</h2></div>
          <Quotes ctx={ctx} limit={4} />
        </div></section>
      )}

      {p.faq.length > 0 && (
        <section className="lg-sec"><div className="lg-wrap">
          <div className="lg-sec-h"><p className="lg-up lg-signal">{t("lws.faqEyebrow")}</p><h2>{t("lws.faqTitle")}</h2></div>
          <Faqs ctx={{ ...ctx, p: { ...p, faq: p.faq.slice(0, 5) } }} />
        </div></section>
      )}
      <span hidden>{name}</span>
      <Cta ctx={ctx} />
    </>
  );
}

export function LgAbout({ ctx }: { ctx: LnCtx }) {
  const { t } = ctx;
  const l = ctx.lawyers[0];
  return (
    <>
      <PageHead ctx={ctx} title={t("lws.nav.about")} sub={ctx.p.headline} />
      <div className="lg-wrap lg-split">
        <div className="lg-stick">{l.photoUrl ? <Photo ctx={ctx} /> : <div className="lg-fig-ph" aria-hidden="true">{initials(ctx.p.displayName)}</div>}
          <dl className="lg-facts" style={{ marginBlockStart: 36 }}>
            <div><dt>{t("lnx.name")}</dt><dd>{l.name}</dd></div>
            {l.barLevel && <div><dt>{t("lnx.rank")}</dt><dd>{l.barLevel}</dd></div>}
            {l.barNumber && <div><dt>{t("lws.barNo")}</dt><dd dir="ltr">{l.barNumber}</dd></div>}
            {l.languages.length > 0 && <div><dt>{t("lws.languages")}</dt><dd>{l.languages.join("، ")}</dd></div>}
            {l.years > 0 && <div><dt>{t("lws.years")}</dt><dd>{l.years}+</dd></div>}
          </dl>
        </div>
        <div>
          <p className="lg-prose">{l.bio || t("lws.bioDefault")}</p>
          {l.education.length > 0 && (
            <div className="lg-edu"><h3 style={{ paddingBlock: 18, fontSize: "1.6rem" }}>{t("lnx.education")}</h3>
              {l.education.map((e, i) => <div key={i}><span dir="ltr">{e.year}</span><div style={{ display: "block", padding: 0, border: 0 }}><b>{e.title}</b><br /><span>{e.place}</span></div></div>)}
            </div>
          )}
        </div>
      </div>
      {ctx.stats.length > 0 && <section className="lg-sec lg-sec-dark"><div className="lg-wrap"><div className="lg-stats">{ctx.stats.map((s, i) => <div key={i}><b><CountUp text={s.value} /></b><span>{s.label}</span></div>)}</div></div></section>}
      <Cta ctx={ctx} />
    </>
  );
}

export function LgTeam({ ctx }: { ctx: LnCtx }) {
  const { t } = ctx;
  return (
    <>
      <PageHead ctx={ctx} title={t("lnx.team.title")} sub={t("lnx.team.sub")} />
      <section className="lg-sec"><div className="lg-wrap"><div className="lg-cards">
        {ctx.lawyers.map((l, i) => (
          <Reveal key={l.key} className={`lg-lawyer${ctx.lawyers.length === 1 ? " lg-wide" : ""}`}>
            {l.photoUrl ? <Photo ctx={ctx} i={i} /> : <div className="lg-fig-ph" aria-hidden="true">{initials(ctx.p.displayName)}</div>}
            <div style={{ display: "grid", gap: 14 }}>
              <h3>{l.name}</h3>
              {l.role && <p className="lg-up lg-signal">{l.role}</p>}
              {l.bio && <p className="lg-bio" style={{ color: "var(--text2)", display: "-webkit-box", WebkitLineClamp: 4, WebkitBoxOrient: "vertical", overflow: "hidden" }}>{l.bio}</p>}
              {l.languages.length > 0 && <div className="lg-tags">{l.languages.map((x) => <span key={x}>{x}</span>)}</div>}
              <div><Link href="/about" className="lg-btn lg-btn-line">{t("lnx.more")}<Arrow /></Link></div>
            </div>
          </Reveal>
        ))}
      </div></div></section>
      <Cta ctx={ctx} />
    </>
  );
}

export function LgAreas({ ctx }: { ctx: LnCtx }) {
  const { t, p } = ctx;
  return (
    <>
      <PageHead ctx={ctx} title={t("lws.areasEyebrow")} sub={t("lws.areasTitle")} />
      {p.practiceAreas.length === 0 ? <Empty ctx={ctx} title={t("lnx.empty.title")} /> : <section className="lg-sec"><div className="lg-wrap"><AreaRows ctx={ctx} areas={p.practiceAreas} /></div></section>}
      <Cta ctx={ctx} />
    </>
  );
}

export function LgAreaDetail({ ctx, area, index }: { ctx: LnCtx; area: LnArea; index: number }) {
  const { t, p } = ctx;
  return (
    <>
      <PageHead ctx={ctx} title={area.title} sub={area.description || t("lws.areaDefault")}
        crumb={<><Link href="/areas">{t("lws.areasEyebrow")}</Link><span aria-hidden="true">/</span><span>{num(index)}</span></>} />
      <div className="lg-wrap lg-split">
        <aside className="lg-stick"><p className="lg-up lg-signal" style={{ marginBlockEnd: 14 }}>{t("lnx.otherAreas")}</p>
          <div className="lg-side">{p.practiceAreas.map((a, i) => <Link key={a.key} href={`/areas/${encodeURIComponent(a.key)}`} aria-current={a.key === area.key ? "page" : undefined}><span>{a.title}</span><span>{num(i)}</span></Link>)}</div>
        </aside>
        <div>
          <p className="lg-prose">{area.description || t("lws.areaDefault")}</p>
          <div className="lg-box" style={{ marginBlockStart: 40 }}>
            <h3 style={{ fontSize: "1.7rem", marginBlockEnd: 12 }}>{t("lnx.area.cta")}</h3>
            <p style={{ color: "var(--text2)" }}>{t("lws.contactText")}</p>
            <div className="lg-hero-cta">
              {ctx.book && <Link href="/book" className="lg-btn">{t("lws.bookCta")}<Arrow /></Link>}
              {ctx.waHref && <a href={ctx.waHref} target="_blank" rel="noopener noreferrer" className="lg-btn lg-btn-line">{t("lws.chat")}</a>}
            </div>
          </div>
        </div>
      </div>
    </>
  );
}

export function LgServices({ ctx }: { ctx: LnCtx }) {
  const { t, p } = ctx;
  return (
    <>
      <PageHead ctx={ctx} title={t("lws.servicesEyebrow")} sub={t("lws.servicesTitle")} />
      {p.services.length === 0 ? <Empty ctx={ctx} title={t("lnx.empty.title")} /> : <section className="lg-sec"><div className="lg-wrap"><ServiceTable ctx={ctx} /></div></section>}
      <Cta ctx={ctx} />
    </>
  );
}

export function LgContact({ ctx }: { ctx: LnCtx }) {
  const { t, p } = ctx;
  return (
    <>
      <PageHead ctx={ctx} title={t("lws.contactEyebrow")} sub={t("lws.contactTitle")} />
      <section className="lg-sec"><div className="lg-wrap lg-ct">
        <div className="lg-box">
          <h3>{t("lnx.office")}</h3>
          {p.phone && <div className="lg-line"><b>{t("lnx.phone")}</b><a href={`tel:${p.phone}`} dir="ltr">{p.phone}</a></div>}
          {p.email && <div className="lg-line"><b>{t("lnx.email")}</b><a href={`mailto:${p.email}`}>{p.email}</a></div>}
          {ctx.address && <div className="lg-line"><b>{t("lnx.address")}</b><span>{ctx.address}</span></div>}
          {p.mapUrl && <div className="lg-line"><b>{t("lnx.map")}</b><a href={p.mapUrl} target="_blank" rel="noopener noreferrer" className="lg-signal">{t("lws.directions")}</a></div>}
          <div className="lg-hero-cta">
            {ctx.book && <Link href="/book" className="lg-btn">{t("lws.bookCta")}<Arrow /></Link>}
            {ctx.waHref && <a href={ctx.waHref} target="_blank" rel="noopener noreferrer" className="lg-btn lg-btn-line">{t("lws.chat")}</a>}
          </div>
        </div>
        <div className="lg-box">
          <h3>{t("lws.hours")}</h3>
          <dl className="lg-hours">{ctx.hours.map((h) => <div key={h.w}><dt>{h.name}</dt><dd dir="ltr">{h.rows.length ? h.rows.join(" · ") : t("lws.closed")}</dd></div>)}</dl>
        </div>
      </div></section>
    </>
  );
}

export function LgFaq({ ctx }: { ctx: LnCtx }) {
  const { t, p } = ctx;
  return (
    <>
      <PageHead ctx={ctx} title={t("lws.faqEyebrow")} sub={t("lws.faqTitle")} />
      <section className="lg-sec"><div className="lg-wrap">{p.faq.length === 0 ? <Empty ctx={ctx} title={t("lnx.empty.title")} /> : <Faqs ctx={ctx} />}</div></section>
      <Cta ctx={ctx} />
    </>
  );
}

export function LgReviews({ ctx }: { ctx: LnCtx }) {
  const { t, p } = ctx;
  return (
    <>
      <PageHead ctx={ctx} title={t("lws.reviewsEyebrow")} sub={t("lws.reviewsTitle")} />
      <section className="lg-sec"><div className="lg-wrap">{p.testimonials.length === 0 ? <Empty ctx={ctx} title={t("lnx.empty.title")} /> : <Quotes ctx={ctx} />}</div></section>
      <Cta ctx={ctx} />
    </>
  );
}
