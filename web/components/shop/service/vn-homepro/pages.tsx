import Link from "next/link";
import { Reveal } from "@/components/shop/templates/reveal";
import { ShopProductCard } from "@/components/shop/product-card";
import { Icon } from "@/components/icons";
import { money } from "@/lib/format";
import { groupServices, isOffer, serviceBody, todayWeekday, dayRanges } from "../vn-core/data";
import { Pic } from "../vn-core/tools";
import { VnWeek, firstLetter, phonesOf } from "../vn-core/shared";
import type { DetailProps, T, VnData, VnService } from "../vn-core/types";
import type { Locale } from "@/lib/i18n";
import { RequestCard } from "./request";

const where = (s: VnService, t: T) => (s.locationMode === "HOME" ? t("svh.home") : s.locationMode === "BOTH" ? t("svh.both") : t("svh.shop"));
const Price = ({ s, locale }: { s: VnService; locale: Locale }) => <span className="hp-price tnum">{isOffer(s) && <s>{money(s.priceBeforeMinor, s.currency, locale)}</s>}{money(s.priceMinor, s.currency, locale)}</span>;

/** A service as a tile: the price and time first, one clear Request button. */
function Tile({ s, t, locale }: { s: VnService; t: T; locale: Locale }) {
  return (
    <div className="hp-tile">
      <Link href={`/services/${s.id}`} className="hp-tile-main">
        <span className="hp-tile-ico"><Pic id={s.imageFileId} variant="thumb" className="hp-tile-img" fallback={<span aria-hidden>{firstLetter(s.name)}</span>} /></span>
        <span className="hp-tile-name">{s.name}</span>
        {serviceBody(s) && <span className="hp-tile-body">{serviceBody(s)}</span>}
        <span className="hp-tile-meta"><Icon name="clock" className="h-4 w-4" />{s.durationMinutes} {t("bk.min")}<Icon name="pin" className="h-4 w-4" />{where(s, t)}</span>
      </Link>
      <div className="hp-tile-foot"><span><small>{t("vn.hp.from")}</small><Price s={s} locale={locale} /></span><Link href={`/book?service=${s.id}`} className="vf-btn hp-tile-btn">{t("vn.hp.request")}</Link></div>
    </div>
  );
}

export function HomeproHome(d: VnData) {
  const { p, cfg, products, t, locale } = d;
  const name = p?.storeName ?? "";
  const services = cfg.services;
  const phones = phonesOf(d);
  const offers = services.filter(isOffer).slice(0, 3);
  const areas = Array.from(new Set(services.map((s) => s.serviceArea).filter(Boolean))) as string[];
  const today = dayRanges(cfg, todayWeekday(cfg.timezone));
  const labels = { outOfStock: t("shop.outOfStock"), off: t("shop.off"), badges: { NEW: t("badge.NEW"), SALE: t("badge.SALE"), BEST_SELLER: t("badge.BEST_SELLER"), LIMITED: t("badge.LIMITED") } };
  return (
    <div className="hp-home">
      <section className="hp-hero">
        <div className="hp-wrap hp-hero-in">
          <div className="hp-hero-text">
            <p className="hp-pill">{t("vn.hp.pill")}</p>
            <h1 className="hp-h1">{name}</h1>
            <p className="hp-lead">{p?.shortDescription || p?.about || t("vn.hp.heroDefault")}</p>
            <ul className="hp-ticks"><li><Icon name="check" className="h-5 w-5" />{t("svh.s1")}</li><li><Icon name="check" className="h-5 w-5" />{t("svh.s2")}</li><li><Icon name="check" className="h-5 w-5" />{cfg.autoConfirm ? t("svh.s3auto") : t("svh.s3")}</li></ul>
            {phones[0] && <a href={`tel:${phones[0]}`} className="hp-call" dir="ltr"><Icon name="phone" className="h-5 w-5" />{phones[0]}</a>}
          </div>
          {services.length > 0 ? <RequestCard services={services} maxDays={cfg.maxDaysAhead} /> : <div className="hp-card"><p className="hp-card-sub">{t("svh.noServices")}</p></div>}
        </div>
      </section>

      <section className="hp-sec hp-wrap" id="services">
        <div className="hp-head"><h2 className="hp-h2">{t("vn.hp.servicesTitle")}</h2><Link href="/services" className="hp-more">{t("shop.viewAll")}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Link></div>
        {services.length === 0 ? <p className="hp-empty">{t("svh.noServices")}</p> : <div className="hp-tiles">{services.slice(0, 9).map((s) => <Tile key={s.id} s={s} t={t} locale={locale} />)}</div>}
      </section>

      {services.length > 0 && (
        <section className="hp-band">
          <div className="hp-wrap">
            <h2 className="hp-h2">{t("svh.how")}</h2>
            <ol className="hp-steps">{[[t("svh.s1"), t("svh.s1b")], [t("svh.s2"), t("svh.s2b")], [t("svh.s3"), cfg.autoConfirm ? t("svh.s3auto") : t("svh.s3b")]].map(([h, b], i) => <li key={h}><Reveal delay={i * 0.08}><span className="hp-step-n tnum">{i + 1}</span><h3>{h}</h3><p>{b}</p></Reveal></li>)}</ol>
          </div>
        </section>
      )}

      {offers.length > 0 && (
        <section className="hp-sec hp-wrap"><div className="hp-head"><h2 className="hp-h2">{t("svh.offers")}</h2></div><div className="hp-tiles">{offers.map((s) => <Tile key={s.id} s={s} t={t} locale={locale} />)}</div></section>
      )}

      {(areas.length > 0 || today) && (
        <section className="hp-sec hp-wrap">
          <div className="hp-cover">
            {areas.length > 0 && <div><h2 className="hp-h3"><Icon name="pin" className="h-5 w-5" />{t("vn.hp.area")}</h2><ul className="hp-chips">{areas.map((a) => <li key={a}>{a}</li>)}</ul></div>}
            {today && <div><h2 className="hp-h3"><Icon name="clock" className="h-5 w-5" />{t("vn.today")}</h2><p className="hp-today tnum" dir="ltr">{today}</p></div>}
          </div>
        </section>
      )}

      {products.length > 0 && (
        <section className="hp-sec hp-wrap"><div className="hp-head"><h2 className="hp-h2">{t("svh.productsHint")}</h2><Link href="/products" className="hp-more">{t("shop.viewAll")}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Link></div>
          <div className="hp-products">{products.slice(0, 4).map((x) => <ShopProductCard key={x.id} p={x} locale={locale} labels={labels} variant="minimal" />)}</div></section>
      )}

      {services.length > 0 && (
        <section className="hp-final"><div className="hp-wrap hp-final-in"><h2>{t("vn.hp.finalTitle")}</h2><div className="hp-final-cta"><Link href="/book" className="vf-btn hp-light">{t("vn.hp.request")}</Link>{phones[0] && <a href={`tel:${phones[0]}`} dir="ltr" className="vf-btn hp-outline"><Icon name="phone" className="h-5 w-5" />{phones[0]}</a>}</div></div></section>
      )}
    </div>
  );
}

export function HomeproCatalog({ d, cat }: { d: VnData; cat?: string }) {
  const { cfg, t, locale, failed } = d;
  const groups = groupServices(cfg);
  const shown = cat ? groups.filter((g) => g.key === cat) : groups;
  return (
    <div className="hp-wrap hp-page">
      <h1 className="hp-h1 hp-h1-sm">{t("svh.services")}</h1>
      {groups.length > 1 && <nav className="hp-filters" aria-label={t("vn.categories")}><Link href="/services" data-on={!cat ? "true" : undefined}>{t("vn.all")}</Link>{groups.map((g) => <Link key={g.key} href={`/services?cat=${encodeURIComponent(g.key)}`} data-on={cat === g.key ? "true" : undefined}>{g.name || t("vn.other")}</Link>)}</nav>}
      {failed ? <p className="hp-empty">{t("vn.loadFailed")}</p> : cfg.services.length === 0 ? <p className="hp-empty">{t("svh.noServices")}</p> : shown.map((g) => (
        <section key={g.key} className="hp-group">
          {(g.name || groups.length > 1) && <h2 className="hp-h3">{g.name || t("vn.other")}</h2>}
          <ul className="hp-rows">{g.items.map((s) => (
            <li key={s.id} className="hp-row">
              <Link href={`/services/${s.id}`} className="hp-row-main">
                <span className="hp-row-ico"><Pic id={s.imageFileId} variant="thumb" className="hp-tile-img" fallback={<span aria-hidden>{firstLetter(s.name)}</span>} /></span>
                <span className="hp-row-text"><b>{s.name}</b>{serviceBody(s) && <small>{serviceBody(s)}</small>}<em>{s.durationMinutes} {t("bk.min")} · {where(s, t)}</em></span>
              </Link>
              <span className="hp-row-end"><Price s={s} locale={locale} /><Link href={`/book?service=${s.id}`} className="vf-btn hp-tile-btn">{t("vn.hp.request")}</Link></span>
            </li>))}</ul>
        </section>
      ))}
    </div>
  );
}

export function HomeproDetail(d: DetailProps) {
  const { service: s, cfg, t, locale, p } = d;
  const phone = phonesOf(d)[0];
  const related = cfg.services.filter((x) => x.id !== s.id && (x.categoryName || "") === (s.categoryName || "")).slice(0, 3);
  const pics = [s.imageFileId, ...(s.gallery ?? [])].filter((x): x is string => !!x);
  return (
    <div className="hp-wrap hp-page">
      <nav className="hp-crumbs" aria-label="breadcrumb"><Link href="/services">{t("svh.services")}</Link><span aria-hidden>/</span><span>{s.name}</span></nav>
      <div className="hp-detail">
        <div className="hp-detail-main">
          {s.categoryName && <p className="hp-pill">{s.categoryName}</p>}
          <h1 className="hp-h1 hp-h1-sm">{s.name}</h1>
          {pics[0] && <div className="hp-hero-pic"><Pic id={pics[0]} variant="original" className="hp-pic" /></div>}
          {pics.length > 1 && <ul className="hp-thumbs">{pics.slice(1, 5).map((id) => <li key={id}><Pic id={id} variant="thumb" className="hp-thumb" /></li>)}</ul>}
          {s.description && <div className="hp-block"><h2 className="hp-h3">{t("vn.hp.included")}</h2><p className="hp-copy">{s.description}</p></div>}
          {s.instructions && <div className="hp-block hp-warn"><h2 className="hp-h3">{t("vn.beforeVisit")}</h2><p className="hp-copy">{s.instructions}</p></div>}
          {(s.addons ?? []).length > 0 && <div className="hp-block"><h2 className="hp-h3">{t("book.options")}</h2><ul className="hp-addons">{(s.addons ?? []).map((a) => <li key={a.id}><span>{a.name}</span><bdi className="tnum">+{money(a.priceMinor, s.currency, locale)} · +{a.durationMinutes} {t("bk.min")}</bdi></li>)}</ul></div>}
          {s.serviceArea && <p className="hp-copy"><Icon name="pin" className="inline h-4 w-4" /> {t("book.area", { area: s.serviceArea })}</p>}
          {s.cancellationPolicy && <p className="hp-copy">{s.cancellationPolicy}</p>}
        </div>
        <aside className="hp-aside">
          <div className="hp-card">
            <Price s={s} locale={locale} />
            <ul className="hp-facts"><li><Icon name="clock" className="h-4 w-4" />{s.durationMinutes} {t("bk.min")}</li><li><Icon name="pin" className="h-4 w-4" />{where(s, t)}</li></ul>
            <Link href={`/book?service=${s.id}`} className="vf-btn hp-card-go">{t("vn.hp.request")}</Link>
            {phone && <a href={`tel:${phone}`} dir="ltr" className="vf-btn vf-btn-ghost hp-card-go"><Icon name="phone" className="h-5 w-5" />{phone}</a>}
            {p?.whatsapp && <p className="hp-card-sub">{t("vn.hp.questions")}</p>}
          </div>
          <div className="hp-card"><h2 className="hp-h3">{t("identity.hours")}</h2><VnWeek d={d} /></div>
        </aside>
      </div>
      {related.length > 0 && <section className="hp-sec"><h2 className="hp-h2">{t("vn.related")}</h2><div className="hp-tiles">{related.map((x) => <Tile key={x.id} s={x} t={t} locale={locale} />)}</div></section>}
    </div>
  );
}
