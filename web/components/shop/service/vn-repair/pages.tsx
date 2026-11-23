import Link from "next/link";
import { ShopProductCard } from "@/components/shop/product-card";
import { Icon, type IconName } from "@/components/icons";
import { money } from "@/lib/format";
import type { Locale } from "@/lib/i18n";
import { dayRanges, groupServices, isOffer, serviceBody, todayWeekday } from "../vn-core/data";
import { Pic } from "../vn-core/tools";
import { VnWeek, phonesOf } from "../vn-core/shared";
import type { DetailProps, T, VnData, VnGroup, VnService } from "../vn-core/types";
import { TrackBox } from "./track";

const ICONS: IconName[] = ["settings", "mobile", "monitor", "bolt", "flask", "truck", "tablet", "sliders"];
const where = (s: VnService, t: T) => (s.locationMode === "HOME" ? t("svh.home") : s.locationMode === "BOTH" ? t("svh.both") : t("svh.shop"));
const fromPrice = (g: VnGroup) => Math.min(...g.items.map((s) => s.priceMinor));
const slug = (k: string) => `cat-${Array.from(k).map((c) => c.charCodeAt(0).toString(36)).join("").slice(0, 24)}`;

/** The spec table: one line per service with the numbers a customer compares (time, price) and the action. */
function SpecTable({ items, t, locale }: { items: VnService[]; t: T; locale: Locale }) {
  return (
    <div className="rp-table" role="table">
      <div className="rp-tr rp-th" role="row"><span role="columnheader">{t("vn.rp.col.service")}</span><span role="columnheader">{t("vn.rp.col.time")}</span><span role="columnheader">{t("vn.rp.col.price")}</span><span role="columnheader" /></div>
      {items.map((s) => (
        <div key={s.id} className="rp-tr" role="row">
          <span className="rp-td-name" role="cell"><Link href={`/services/${s.id}`}><b>{s.name}</b>{serviceBody(s) && <small>{serviceBody(s)}</small>}<em>{where(s, t)}</em></Link></span>
          <span className="rp-td tnum" role="cell" data-l={t("vn.rp.col.time")}>{s.durationMinutes} {t("bk.min")}</span>
          <span className="rp-td rp-price tnum" role="cell" data-l={t("vn.rp.col.price")}>{isOffer(s) && <s>{money(s.priceBeforeMinor, s.currency, locale)}</s>}{money(s.priceMinor, s.currency, locale)}</span>
          <span className="rp-td-go" role="cell"><Link href={`/book?service=${s.id}`} className="vf-btn rp-mini">{t("vn.rp.book")}</Link></span>
        </div>
      ))}
    </div>
  );
}

export function RepairHome(d: VnData) {
  const { p, cfg, products, t, locale } = d;
  const name = p?.storeName ?? "";
  const groups = groupServices(cfg);
  const named = groups.filter((g) => g.name);
  const popular = cfg.services.slice(0, 8);
  const offers = cfg.services.filter(isOffer).slice(0, 4);
  const today = dayRanges(cfg, todayWeekday(cfg.timezone));
  const phone = phonesOf(d)[0];
  const labels = { outOfStock: t("shop.outOfStock"), off: t("shop.off"), badges: { NEW: t("badge.NEW"), SALE: t("badge.SALE"), BEST_SELLER: t("badge.BEST_SELLER"), LIMITED: t("badge.LIMITED") } };
  return (
    <div className="rp-home">
      <section className="rp-hero">
        <div className="rp-wrap rp-hero-in">
          <div>
            <p className="rp-label">{t("vn.rp.eyebrow")}</p>
            <h1 className="rp-h1">{name}</h1>
            <p className="rp-lead">{p?.shortDescription || p?.about || t("vn.rp.heroDefault")}</p>
            <div className="rp-meta">{today && <span><Icon name="clock" className="h-4 w-4" />{t("vn.today")} <bdi dir="ltr" className="tnum">{today}</bdi></span>}{phone && <a href={`tel:${phone}`} dir="ltr"><Icon name="phone" className="h-4 w-4" />{phone}</a>}</div>
          </div>
          {cfg.services.length > 0 && <TrackBox />}
        </div>
      </section>

      <section className="rp-sec rp-wrap">
        <div className="rp-head"><p className="rp-label">01</p><h2 className="rp-h2">{t("vn.rp.whatFix")}</h2></div>
        {cfg.services.length === 0 ? <p className="rp-empty">{t("svh.noServices")}</p> : named.length > 0 ? (
          <div className="rp-cats">{named.map((g, i) => (
            <Link key={g.key} href={`/services?cat=${encodeURIComponent(g.key)}`} className="rp-cat">
              <Icon name={ICONS[i % ICONS.length]} className="h-7 w-7" />
              <span className="rp-cat-name">{g.name}</span>
              <span className="rp-cat-meta"><bdi className="tnum">{g.items.length}</bdi> {t("vn.rp.services")} · {t("vn.hp.from")} <bdi className="tnum">{money(fromPrice(g), g.items[0].currency, locale)}</bdi></span>
              <Icon name="arrow" className="rp-cat-arrow h-5 w-5 flip-rtl" />
            </Link>))}</div>
        ) : <SpecTable items={popular} t={t} locale={locale} />}
      </section>

      {named.length > 0 && cfg.services.length > 0 && (
        <section className="rp-sec rp-wrap">
          <div className="rp-head"><p className="rp-label">02</p><h2 className="rp-h2">{t("vn.rp.popular")}</h2></div>
          <SpecTable items={popular} t={t} locale={locale} />
          <p className="rp-center"><Link href="/services" className="vf-btn vf-btn-ghost rp-ghost">{t("vn.wl.viewMenu")}</Link></p>
        </section>
      )}

      {cfg.services.length > 0 && (
        <section className="rp-sec rp-wrap">
          <div className="rp-head"><p className="rp-label">03</p><h2 className="rp-h2">{t("svh.how")}</h2></div>
          <ol className="rp-flow">{[[t("svh.s1"), t("svh.s1b")], [t("svh.s2"), t("svh.s2b")], [t("svh.s3"), cfg.autoConfirm ? t("svh.s3auto") : t("svh.s3b")]].map(([h, b], i) => <li key={h}><span className="rp-flow-n tnum">{String(i + 1).padStart(2, "0")}</span><div><h3>{h}</h3><p>{b}</p></div></li>)}</ol>
        </section>
      )}

      {offers.length > 0 && <section className="rp-sec rp-wrap"><div className="rp-head"><p className="rp-label">%</p><h2 className="rp-h2">{t("svh.offers")}</h2></div><SpecTable items={offers} t={t} locale={locale} /></section>}

      {products.length > 0 && (
        <section className="rp-sec rp-wrap"><div className="rp-head"><p className="rp-label">{t("vn.rp.nav.parts")}</p><h2 className="rp-h2">{t("svh.productsHint")}</h2></div>
          <div className="rp-products">{products.slice(0, 4).map((x) => <ShopProductCard key={x.id} p={x} locale={locale} labels={labels} variant="minimal" />)}</div></section>
      )}

      <section className="rp-sec rp-wrap">
        <div className="rp-visit">
          <div><p className="rp-label">{t("vn.rp.nav.find")}</p><h2 className="rp-h2">{t("identity.hours")}</h2><VnWeek d={d} /></div>
          <div className="rp-visit-r">{p?.addressText && <p className="rp-addr"><Icon name="pin" className="h-5 w-5" />{p.addressText}</p>}{p?.mapsUrl && <a href={p.mapsUrl} target="_blank" rel="noopener noreferrer" className="vf-link">{t("shop.openMap")}</a>}<Link href="/contact" className="vf-btn vf-btn-ghost rp-ghost">{t("shop.contact")}</Link></div>
        </div>
      </section>
    </div>
  );
}

export function RepairCatalog({ d, cat }: { d: VnData; cat?: string }) {
  const { cfg, t, locale, failed } = d;
  const groups = groupServices(cfg);
  const shown = cat ? groups.filter((g) => g.key === cat) : groups;
  return (
    <div className="rp-wrap rp-page">
      <p className="rp-label">{t("vn.rp.nav.catalog")}</p>
      <h1 className="rp-h1 rp-h1-sm">{t("svh.services")}</h1>
      {failed ? <p className="rp-empty">{t("vn.loadFailed")}</p> : cfg.services.length === 0 ? <p className="rp-empty">{t("svh.noServices")}</p> : (
        <div className="rp-split">
          {groups.length > 1 && (
            <nav className="rp-rail" aria-label={t("vn.categories")}>
              <Link href="/services" data-on={!cat ? "true" : undefined}><span>{t("vn.all")}</span><bdi className="tnum">{cfg.services.length}</bdi></Link>
              {groups.map((g) => <Link key={g.key} href={`/services?cat=${encodeURIComponent(g.key)}`} data-on={cat === g.key ? "true" : undefined}><span>{g.name || t("vn.other")}</span><bdi className="tnum">{g.items.length}</bdi></Link>)}
            </nav>
          )}
          <div className="rp-split-main">{shown.map((g) => (
            <section key={g.key} id={slug(g.key)} className="rp-group">
              {(g.name || groups.length > 1) && <h2 className="rp-h3">{g.name || t("vn.other")}</h2>}
              <SpecTable items={g.items} t={t} locale={locale} />
            </section>))}</div>
        </div>
      )}
    </div>
  );
}

export function RepairDetail(d: DetailProps) {
  const { service: s, cfg, t, locale } = d;
  const related = cfg.services.filter((x) => x.id !== s.id && (x.categoryName || "") === (s.categoryName || "")).slice(0, 4);
  const pics = [s.imageFileId, ...(s.gallery ?? [])].filter((x): x is string => !!x);
  const rows: [string, React.ReactNode][] = [
    [t("vn.rp.col.price"), <span key="p" className="rp-price tnum">{isOffer(s) && <s>{money(s.priceBeforeMinor, s.currency, locale)}</s>}{money(s.priceMinor, s.currency, locale)}</span>],
    [t("vn.rp.col.time"), <span key="t" className="tnum">{s.durationMinutes} {t("bk.min")}</span>],
    [t("vn.rp.where"), where(s, t)],
    ...(s.serviceArea ? [[t("vn.hp.area"), s.serviceArea] as [string, React.ReactNode]] : []),
    ...(s.categoryName ? [[t("vn.rp.type"), s.categoryName] as [string, React.ReactNode]] : []),
  ];
  return (
    <div className="rp-wrap rp-page">
      <nav className="rp-crumbs" aria-label="breadcrumb"><Link href="/services">{t("svh.services")}</Link>{s.categoryName && <><span aria-hidden>/</span><Link href={`/services?cat=${encodeURIComponent(s.categoryName)}`}>{s.categoryName}</Link></>}</nav>
      <h1 className="rp-h1 rp-h1-sm">{s.name}</h1>
      <div className="rp-sheetgrid">
        <div>
          {pics[0] && <div className="rp-pic-wrap"><Pic id={pics[0]} variant="original" className="rp-pic" /></div>}
          {s.description && <section className="rp-box"><h2 className="rp-label">{t("vn.rp.about")}</h2><p className="rp-copy">{s.description}</p></section>}
          {s.instructions && <section className="rp-box rp-box-warn"><h2 className="rp-label">{t("vn.beforeVisit")}</h2><p className="rp-copy">{s.instructions}</p></section>}
          {(s.addons ?? []).length > 0 && <section className="rp-box"><h2 className="rp-label">{t("book.options")}</h2><ul className="rp-opts">{(s.addons ?? []).map((a) => <li key={a.id}><span>{a.name}</span><bdi className="tnum">+{money(a.priceMinor, s.currency, locale)} · +{a.durationMinutes} {t("bk.min")}</bdi></li>)}</ul></section>}
          {s.cancellationPolicy && <section className="rp-box"><h2 className="rp-label">{t("vn.rp.policy")}</h2><p className="rp-copy">{s.cancellationPolicy}</p></section>}
        </div>
        <aside className="rp-spec">
          <dl>{rows.map(([k, v]) => <div key={k} className="rp-spec-row"><dt>{k}</dt><dd>{v}</dd></div>)}</dl>
          <Link href={`/book?service=${s.id}`} className="vf-btn rp-spec-go">{t("vn.rp.book")}</Link>
        </aside>
      </div>
      {related.length > 0 && <section className="rp-sec"><div className="rp-head"><h2 className="rp-h2">{t("vn.related")}</h2></div><SpecTable items={related} t={t} locale={locale} /></section>}
    </div>
  );
}
