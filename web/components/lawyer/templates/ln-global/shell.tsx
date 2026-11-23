import Link from "next/link";
import type { ReactNode } from "react";
import { VisitTracker } from "@/components/visit-tracker";
import { IArrow } from "../shared/icons";
import { initials, type LnCtx } from "../shared/ctx";
import { lgFonts } from "./fonts";
import { LgNav } from "./nav";
import "./ln-global.css";

const WA = "M20.5 3.5A11.8 11.8 0 0 0 1.9 17.6L.4 23.5l6-1.6A11.8 11.8 0 0 0 20.5 3.5Zm-8.6 18.2c-1.8 0-3.5-.5-5-1.4l-.4-.2-3.6.9 1-3.5-.2-.4a9.8 9.8 0 1 1 8.2 4.6Zm5.4-7.3c-.3-.1-1.7-.9-2-1s-.5-.1-.7.1-.8 1-1 1.2-.4.2-.6.1a8 8 0 0 1-2.3-1.4 8.6 8.6 0 0 1-1.6-2c-.2-.3 0-.5.1-.6l.5-.6.3-.5a.6.6 0 0 0 0-.6c-.1-.1-.7-1.6-.9-2.2s-.5-.5-.7-.5h-.6a1.2 1.2 0 0 0-.9.4 3.7 3.7 0 0 0-1.1 2.7 6.4 6.4 0 0 0 1.4 3.4 14.7 14.7 0 0 0 5.6 4.9c2.1.9 2.9.9 3.9.8a3.3 3.3 0 0 0 2.2-1.6 2.7 2.7 0 0 0 .2-1.6c-.1-.1-.3-.2-.6-.3Z";

/** ln-global frame: dark utility strip, sticky masthead with a mega menu, an oversized footer with the firm name. */
export function LgShell({ ctx, children }: { ctx: LnCtx; children: ReactNode }) {
  const { p, t, name, nav, book } = ctx;
  const areas = p.practiceAreas.map((a) => ({ key: a.key, title: a.title }));
  const social = Object.entries(p.social ?? {}).filter(([, v]) => v);
  return (
    <div className={`lg ${lgFonts}`}>
      <a href="#lg-main" className="lg-skip">{t("lnx.skip")}</a>
      <div className="lg-util">
        <div className="lg-wrap">
          <div className="lg-util-l">
            {p.phone && <a href={`tel:${p.phone}`} dir="ltr">{p.phone}</a>}
            {p.email && <a href={`mailto:${p.email}`}>{p.email}</a>}
          </div>
          <div className="lg-util-r">
            <Link href="/dashboard/login">{t("lws.staff")}</Link>
            <a href={ctx.langHref}>{ctx.locale === "ar" ? "EN" : "عربي"}</a>
          </div>
        </div>
      </div>
      <header className="lg-head">
        <div className="lg-wrap">
          <Link href="/" className="lg-brand">
            <span className="lg-mark">{ctx.logo ? /* eslint-disable-next-line @next/next/no-img-element */ <img src={ctx.logo} alt="" /> : initials(ctx.firm)}</span>
            <span style={{ minInlineSize: 0 }}><b>{name}</b>{p.barLevel && <small>{p.barLevel}</small>}</span>
          </Link>
          <LgNav nav={nav} areas={areas} book={false} bookLabel="" />
          <div className="lg-head-r">
            {book && <Link href="/book" className="lg-btn">{t("lws.bookCta")}<IArrow className="lg-arrow" /></Link>}
            <LgNav nav={nav} areas={areas} book={book} bookLabel={t("lws.bookCta")} mobile />
          </div>
        </div>
      </header>
      {!ctx.inFrame && <VisitTracker />}
      <main id="lg-main">{children}</main>
      <footer className="lg-foot">
        <div className="lg-wrap">
          <div className="lg-foot-big">{name}</div>
          <div className="lg-foot-g">
            <div><h4>{t("lws.nav.contact")}</h4><ul>
              {p.phone && <li><a href={`tel:${p.phone}`} dir="ltr">{p.phone}</a></li>}
              {p.email && <li><a href={`mailto:${p.email}`}>{p.email}</a></li>}
              {ctx.address && <li>{ctx.address}</li>}
              {p.mapUrl && <li><a href={p.mapUrl} target="_blank" rel="noopener noreferrer">{t("lws.directions")}</a></li>}
            </ul></div>
            <div><h4>{t("lnx.menu")}</h4><ul>{nav.map((n) => <li key={n.key}><Link href={n.href}>{n.label}</Link></li>)}{book && <li><Link href="/booking">{t("lws.track")}</Link></li>}</ul></div>
            <div><h4>{t("lws.hours")}</h4>
              {ctx.hasHours ? <ul>{ctx.hours.filter((h) => h.rows.length).map((h) => <li key={h.w}>{h.name}: <span dir="ltr">{h.rows.join(" · ")}</span></li>)}</ul> : <ul><li>{t("lws.closed")}</li></ul>}
              {social.length > 0 && <ul style={{ marginBlockStart: 14 }}>{social.map(([k, v]) => <li key={k}><a href={v} target="_blank" rel="noopener noreferrer" style={{ textTransform: "capitalize" }}>{k}</a></li>)}</ul>}
            </div>
          </div>
          <div className="lg-foot-b"><span>© {ctx.year} {name}</span>{p.barNumber && <span>{t("lws.barNo")}: <span dir="ltr">{p.barNumber}</span></span>}</div>
        </div>
      </footer>
      {ctx.waHref && <a href={ctx.waHref} target="_blank" rel="noopener noreferrer" aria-label="WhatsApp" className="lg-wa"><svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d={WA} /></svg></a>}
    </div>
  );
}
