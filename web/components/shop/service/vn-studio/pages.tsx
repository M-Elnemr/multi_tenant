import Link from "next/link";
import { ShopProductCard } from "@/components/shop/product-card";
import { Reveal } from "@/components/shop/templates/reveal";
import { Icon } from "@/components/icons";
import { money } from "@/lib/format";
import type { Locale } from "@/lib/i18n";
import { groupServices, isOffer, nextDays, serviceBody } from "../vn-core/data";
import { Pic } from "../vn-core/tools";
import { VnWeek, firstLetter } from "../vn-core/shared";
import type { DetailProps, T, VnData, VnService } from "../vn-core/types";

const where = (s: VnService, t: T) => (s.locationMode === "HOME" ? t("svh.home") : s.locationMode === "BOTH" ? t("svh.both") : t("svh.shop"));

/** The next seven days as a strip: tap an open day to go straight to its free times. A closed day is shown as closed, not hidden. */
function WeekStrip({ cfg, t, locale, service }: { cfg: VnData["cfg"]; t: T; locale: Locale; service?: string }) {
  const days = nextDays(cfg, 7);
  const month = new Intl.DateTimeFormat(locale === "ar" ? "ar-EG" : "en-US", { month: "short", timeZone: cfg.timezone });
  return (
    <ol className="st-week" aria-label={t("vn.st.thisWeek")}>
      {days.map((d) => {
        const body = (
          <>
            <span className="st-wk-dow">{d.today ? t("vn.st.todayShort") : t(`bk.day.${d.weekday}`).slice(0, 3)}</span>
            <span className="st-wk-day tnum">{d.day}</span>
            <span className="st-wk-mon">{month.format(new Date(d.iso + "T12:00:00Z"))}</span>
            <span className="st-wk-hours tnum" dir="ltr">{d.ranges ? d.ranges.split(" · ")[0] : t("hours.closed")}</span>
          </>
        );
        return <li key={d.iso}>{d.ranges ? <Link className="st-wk" href={`/book?${service ? `service=${service}&` : ""}date=${d.iso}`} data-today={d.today ? "true" : undefined}>{body}</Link> : <span className="st-wk" data-off="true">{body}</span>}</li>;
      })}
    </ol>
  );
}

/** One line of the session index: a big number, the name, and the facts on the right. */
function IndexRow({ s, i, t, locale }: { s: VnService; i: number; t: T; locale: Locale }) {
  return (
    <li>
      <Link href={`/services/${s.id}`} className="st-row">
        <span className="st-row-n tnum">{String(i + 1).padStart(2, "0")}</span>
        <span className="st-row-main"><span className="st-row-name">{s.name}</span>{serviceBody(s) && <span className="st-row-body">{serviceBody(s)}</span>}</span>
        <span className="st-row-facts"><span className="tnum">{s.durationMinutes} {t("bk.min")}</span><span className="st-row-price tnum">{isOffer(s) && <s>{money(s.priceBeforeMinor, s.currency, locale)}</s>}{money(s.priceMinor, s.currency, locale)}</span></span>
        <Icon name="arrow" className="st-row-arrow h-6 w-6 flip-rtl" />
      </Link>
    </li>
  );
}

export function StudioHome(d: VnData) {
  const { p, cfg, products, t, locale } = d;
  const name = p?.storeName ?? "";
  const services = cfg.services;
  const staff = Array.from(new Map(services.flatMap((s) => s.staff ?? []).map((x) => [x.id, x])).values());
  const labels = { outOfStock: t("shop.outOfStock"), off: t("shop.off"), badges: { NEW: t("badge.NEW"), SALE: t("badge.SALE"), BEST_SELLER: t("badge.BEST_SELLER"), LIMITED: t("badge.LIMITED") } };
  return (
    <div className="st-home">
      <section className="st-hero st-wrap">
        <p className="st-tag">{t("vn.st.eyebrow")}</p>
        <h1 className="st-h1">{name}</h1>
        <p className="st-lead">{p?.shortDescription || p?.about || t("vn.st.heroDefault")}</p>
      </section>

      <section className="st-wrap st-weeksec">
        <div className="st-weekhead"><h2 className="st-h3">{t("vn.st.thisWeek")}</h2><Link href="/book" className="st-link">{t("vn.st.fullCalendar")}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Link></div>
        {services.length > 0 ? <WeekStrip cfg={cfg} t={t} locale={locale} /> : <p className="st-empty">{t("svh.noServices")}</p>}
      </section>

      {services.length > 0 && (
        <section className="st-wrap st-sec">
          <div className="st-weekhead"><h2 className="st-h2">{t("vn.st.sessions")}</h2><Link href="/services" className="st-link">{t("shop.viewAll")}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Link></div>
          <ul className="st-index">{services.slice(0, 8).map((s, i) => <IndexRow key={s.id} s={s} i={i} t={t} locale={locale} />)}</ul>
        </section>
      )}

      {staff.length > 0 && (
        <section className="st-wrap st-sec"><h2 className="st-h2">{t("vn.wl.teamTitle")}</h2>
          <ul className="st-crew">{staff.map((x) => <li key={x.id}><span className="st-crew-pic"><Pic id={x.imageFileId} variant="thumb" className="st-crew-img" fallback={<span aria-hidden>{firstLetter(x.name)}</span>} /></span><span>{x.name}</span></li>)}</ul></section>
      )}

      {products.length > 0 && (
        <section className="st-wrap st-sec"><div className="st-weekhead"><h2 className="st-h2">{t("vn.st.nav.shop")}</h2><Link href="/products" className="st-link">{t("shop.viewAll")}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Link></div>
          <div className="st-products">{products.slice(0, 4).map((x) => <ShopProductCard key={x.id} p={x} locale={locale} labels={labels} variant="minimal" />)}</div></section>
      )}

      {services.length > 0 && (
        <section className="st-cta"><Reveal className="st-wrap st-cta-in"><h2>{t("vn.st.ctaTitle")}</h2><Link href="/book" className="st-cta-btn">{t("vn.st.book")}<Icon name="arrow" className="h-6 w-6 flip-rtl" /></Link></Reveal></section>
      )}
    </div>
  );
}

export function StudioCatalog({ d, cat }: { d: VnData; cat?: string }) {
  const { cfg, t, locale, failed } = d;
  const groups = groupServices(cfg);
  const shown = cat ? groups.filter((g) => g.key === cat) : groups;
  let n = 0;
  return (
    <div className="st-wrap st-page">
      <h1 className="st-h1 st-h1-sm">{t("vn.st.sessions")}</h1>
      {groups.length > 1 && <nav className="st-tabs" aria-label={t("vn.categories")}><Link href="/services" data-on={!cat ? "true" : undefined}>{t("vn.all")}</Link>{groups.map((g) => <Link key={g.key} href={`/services?cat=${encodeURIComponent(g.key)}`} data-on={cat === g.key ? "true" : undefined}>{g.name || t("vn.other")}</Link>)}</nav>}
      {failed ? <p className="st-empty">{t("vn.loadFailed")}</p> : cfg.services.length === 0 ? <p className="st-empty">{t("svh.noServices")}</p> : shown.map((g) => (
        <section key={g.key} className="st-group">
          {(g.name || groups.length > 1) && <h2 className="st-h3">{g.name || t("vn.other")}</h2>}
          <ul className="st-index">{g.items.map((s) => <IndexRow key={s.id} s={s} i={n++} t={t} locale={locale} />)}</ul>
        </section>
      ))}
    </div>
  );
}

export function StudioDetail(d: DetailProps) {
  const { service: s, cfg, t, locale } = d;
  const pics = [s.imageFileId, ...(s.gallery ?? [])].filter((x): x is string => !!x);
  const related = cfg.services.filter((x) => x.id !== s.id).slice(0, 3);
  return (
    <div className="st-wrap st-page">
      <Link href="/services" className="st-link st-back"><Icon name="arrow" className="h-4 w-4 rotate-180 flip-rtl" />{t("svh.services")}</Link>
      <div className="st-detail">
        <div className="st-detail-l">
          {s.categoryName && <p className="st-tag">{s.categoryName}</p>}
          <h1 className="st-h1 st-h1-sm">{s.name}</h1>
          <p className="st-facts"><span className="st-fact-price tnum">{isOffer(s) && <s>{money(s.priceBeforeMinor, s.currency, locale)}</s>}{money(s.priceMinor, s.currency, locale)}</span><span className="tnum">{s.durationMinutes} {t("bk.min")}</span><span>{where(s, t)}</span></p>
          {pics[0] && <div className="st-pic-wrap"><Pic id={pics[0]} variant="original" className="st-pic" /></div>}
          {s.description && <p className="st-copy">{s.description}</p>}
          {s.instructions && <div className="st-box"><h2 className="st-h3">{t("vn.beforeVisit")}</h2><p className="st-copy">{s.instructions}</p></div>}
          {(s.addons ?? []).length > 0 && <div className="st-box"><h2 className="st-h3">{t("book.options")}</h2><ul className="st-opts">{(s.addons ?? []).map((a) => <li key={a.id}><span>{a.name}</span><bdi className="tnum">+{money(a.priceMinor, s.currency, locale)} · +{a.durationMinutes} {t("bk.min")}</bdi></li>)}</ul></div>}
          {(s.staff ?? []).length > 0 && <p className="st-copy">{t("vn.withWhom")}: {(s.staff ?? []).map((x) => x.name).join("، ")}</p>}
          {s.cancellationPolicy && <p className="st-copy">{s.cancellationPolicy}</p>}
        </div>
        <aside className="st-detail-r">
          <h2 className="st-h3">{t("vn.st.pickDay")}</h2>
          <WeekStrip cfg={cfg} t={t} locale={locale} service={s.id} />
          <Link href={`/book?service=${s.id}`} className="vf-btn st-big">{t("vn.st.book")}</Link>
          <div className="st-hours"><h2 className="st-h3">{t("identity.hours")}</h2><VnWeek d={d} /></div>
        </aside>
      </div>
      {related.length > 0 && <section className="st-sec"><h2 className="st-h2">{t("vn.related")}</h2><ul className="st-index">{related.map((x, i) => <IndexRow key={x.id} s={x} i={i} t={t} locale={locale} />)}</ul></section>}
    </div>
  );
}
