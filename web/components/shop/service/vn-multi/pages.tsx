import Link from "next/link";
import { ShopProductCard } from "@/components/shop/product-card";
import { Icon } from "@/components/icons";
import { money } from "@/lib/format";
import type { Locale } from "@/lib/i18n";
import { dayRanges, groupServices, isOffer, serviceBody, todayWeekday } from "../vn-core/data";
import { Pic } from "../vn-core/tools";
import { VnWeek, firstLetter, phonesOf } from "../vn-core/shared";
import type { DetailProps, T, VnData, VnService } from "../vn-core/types";

const where = (s: VnService, t: T) => (s.locationMode === "HOME" ? t("svh.home") : s.locationMode === "BOTH" ? t("svh.both") : t("svh.shop"));
/** A stable colour per category, so a category looks the same everywhere (used only as a tint behind its tag and letter). */
const hue = (name: string) => Array.from(name).reduce((h, c) => (h * 31 + c.charCodeAt(0)) % 360, 7);
const tint = (name: string): React.CSSProperties => ({ "--h": hue(name) } as React.CSSProperties);

/** A compact listing card: category tag, photo or letter, name, facts, price and a Book button. */
function Card({ s, t, locale }: { s: VnService; t: T; locale: Locale }) {
  return (
    <div className="mu-card" style={tint(s.categoryName || s.name)}>
      <Link href={`/services/${s.id}`} className="mu-card-main">
        <span className="mu-thumb"><Pic id={s.imageFileId} variant="thumb" className="mu-thumb-img" fallback={<span aria-hidden>{firstLetter(s.name)}</span>} /></span>
        <span className="mu-card-text">
          {s.categoryName && <span className="mu-tag">{s.categoryName}</span>}
          <span className="mu-card-name">{s.name}</span>
          {serviceBody(s) && <span className="mu-card-body">{serviceBody(s)}</span>}
          <span className="mu-card-meta"><Icon name="clock" className="h-3.5 w-3.5" />{s.durationMinutes} {t("bk.min")}<Icon name="pin" className="h-3.5 w-3.5" />{where(s, t)}</span>
        </span>
      </Link>
      <div className="mu-card-foot"><span className="mu-price tnum">{isOffer(s) && <s>{money(s.priceBeforeMinor, s.currency, locale)}</s>}{money(s.priceMinor, s.currency, locale)}</span><Link href={`/book?service=${s.id}`} className="vf-btn mu-mini">{t("vn.mu.book")}</Link></div>
    </div>
  );
}

export function MultiHome(d: VnData) {
  const { p, cfg, products, t, locale } = d;
  const name = p?.storeName ?? "";
  const groups = groupServices(cfg).filter((g) => g.name);
  const offers = cfg.services.filter(isOffer).slice(0, 6);
  const today = dayRanges(cfg, todayWeekday(cfg.timezone));
  const phone = phonesOf(d)[0];
  const labels = { outOfStock: t("shop.outOfStock"), off: t("shop.off"), badges: { NEW: t("badge.NEW"), SALE: t("badge.SALE"), BEST_SELLER: t("badge.BEST_SELLER"), LIMITED: t("badge.LIMITED") } };
  return (
    <div className="mu-home">
      <section className="mu-hero">
        <div className="mu-wrap">
          <h1 className="mu-h1">{name}</h1>
          <p className="mu-lead">{p?.shortDescription || p?.about || t("vn.mu.heroDefault")}</p>
          <div className="mu-facts"><span><bdi className="tnum">{cfg.services.length}</bdi> {t("vn.mu.servicesCount")}</span>{groups.length > 0 && <span><bdi className="tnum">{groups.length}</bdi> {t("vn.mu.categoriesCount")}</span>}{today && <span>{t("vn.today")} <bdi dir="ltr" className="tnum">{today}</bdi></span>}{phone && <a href={`tel:${phone}`} dir="ltr">{phone}</a>}</div>
        </div>
      </section>

      {groups.length > 0 && (
        <section className="mu-wrap mu-sec">
          <h2 className="mu-h2">{t("vn.mu.browse")}</h2>
          <ul className="mu-chips">{groups.map((g) => <li key={g.key}><Link href={`/services?cat=${encodeURIComponent(g.key)}`} className="mu-chip" style={tint(g.name)}><b>{g.name}</b><span className="tnum">{g.items.length}</span></Link></li>)}</ul>
        </section>
      )}

      {offers.length > 0 && (
        <section className="mu-wrap mu-sec"><div className="mu-shelf-head"><h2 className="mu-h2">{t("svh.offers")}</h2></div><div className="mu-rail">{offers.map((s) => <Card key={s.id} s={s} t={t} locale={locale} />)}</div></section>
      )}

      {groups.slice(0, 6).map((g) => (
        <section key={g.key} className="mu-wrap mu-sec">
          <div className="mu-shelf-head"><h2 className="mu-h2">{g.name}</h2><Link href={`/services?cat=${encodeURIComponent(g.key)}`} className="mu-more">{t("shop.viewAll")} ({g.items.length})<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Link></div>
          <div className="mu-rail">{g.items.slice(0, 8).map((s) => <Card key={s.id} s={s} t={t} locale={locale} />)}</div>
        </section>
      ))}

      {groups.length === 0 && cfg.services.length > 0 && <section className="mu-wrap mu-sec"><h2 className="mu-h2">{t("svh.services")}</h2><div className="mu-grid">{cfg.services.map((s) => <Card key={s.id} s={s} t={t} locale={locale} />)}</div></section>}
      {cfg.services.length === 0 && <p className="mu-empty mu-wrap">{t("svh.noServices")}</p>}

      {products.length > 0 && (
        <section className="mu-wrap mu-sec"><div className="mu-shelf-head"><h2 className="mu-h2">{t("svh.productsHint")}</h2><Link href="/products" className="mu-more">{t("shop.viewAll")}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Link></div>
          <div className="mu-products">{products.slice(0, 5).map((x) => <ShopProductCard key={x.id} p={x} locale={locale} labels={labels} variant="minimal" />)}</div></section>
      )}

      <section className="mu-wrap mu-sec"><div className="mu-visit"><div><h2 className="mu-h2">{t("identity.hours")}</h2><VnWeek d={d} /></div><div className="mu-visit-r">{p?.addressText && <p><Icon name="pin" className="h-5 w-5" />{p.addressText}</p>}<Link href="/contact" className="vf-btn">{t("shop.contact")}</Link></div></div></section>
    </div>
  );
}

const SORTS = ["", "price", "price-desc", "name"] as const;

export function MultiCatalog({ d, cat, q, sort }: { d: VnData; cat?: string; q?: string; sort?: string }) {
  const { cfg, t, locale, failed } = d;
  const groups = groupServices(cfg).filter((g) => g.name);
  const needle = (q ?? "").trim().toLowerCase();
  let list = cfg.services.filter((s) => (!cat || (s.categoryName || "") === cat) && (!needle || `${s.name} ${s.shortDescription ?? ""} ${s.description ?? ""} ${s.categoryName ?? ""}`.toLowerCase().includes(needle)));
  if (sort === "price") list = [...list].sort((a, b) => a.priceMinor - b.priceMinor);
  else if (sort === "price-desc") list = [...list].sort((a, b) => b.priceMinor - a.priceMinor);
  else if (sort === "name") list = [...list].sort((a, b) => a.name.localeCompare(b.name, locale));
  const qs = (o: { cat?: string; sort?: string }) => { const u = new URLSearchParams(); const c = "cat" in o ? o.cat : cat; const s = "sort" in o ? o.sort : sort; if (c) u.set("cat", c); if (q) u.set("q", q); if (s) u.set("sort", s); const x = u.toString(); return `/services${x ? `?${x}` : ""}`; };
  return (
    <div className="mu-wrap mu-page">
      <div className="mu-results-head"><h1 className="mu-h1 mu-h1-sm">{cat || (needle ? `“${q}”` : t("svh.services"))}</h1><p className="mu-count"><bdi className="tnum">{list.length}</bdi> {t("vn.mu.results")}</p></div>
      {failed ? <p className="mu-empty">{t("vn.loadFailed")}</p> : (
        <div className="mu-layout">
          <aside className="mu-filters" aria-label={t("vn.categories")}>
            <h2 className="mu-h4">{t("vn.categories")}</h2>
            <ul><li><Link href={qs({ cat: "" })} data-on={!cat ? "true" : undefined}>{t("vn.all")}<bdi className="tnum">{cfg.services.length}</bdi></Link></li>{groups.map((g) => <li key={g.key}><Link href={qs({ cat: g.key })} data-on={cat === g.key ? "true" : undefined}>{g.name}<bdi className="tnum">{g.items.length}</bdi></Link></li>)}</ul>
            <h2 className="mu-h4">{t("vn.mu.sort")}</h2>
            <ul>{SORTS.map((s) => <li key={s || "d"}><Link href={qs({ sort: s })} data-on={(sort ?? "") === s ? "true" : undefined}>{t(`vn.mu.sort.${s || "default"}`)}</Link></li>)}</ul>
          </aside>
          <div>{list.length === 0 ? <div className="mu-none"><p>{t("vn.mu.none")}</p><Link href="/services" className="vf-btn">{t("vn.all")}</Link></div> : <div className="mu-grid">{list.map((s) => <Card key={s.id} s={s} t={t} locale={locale} />)}</div>}</div>
        </div>
      )}
    </div>
  );
}

export function MultiDetail(d: DetailProps) {
  const { service: s, cfg, t, locale } = d;
  const pics = [s.imageFileId, ...(s.gallery ?? [])].filter((x): x is string => !!x);
  const related = cfg.services.filter((x) => x.id !== s.id && (x.categoryName || "") === (s.categoryName || "")).slice(0, 6);
  return (
    <div className="mu-wrap mu-page">
      <nav className="mu-crumbs" aria-label="breadcrumb"><Link href="/">{t("shop.home")}</Link><span aria-hidden>/</span><Link href="/services">{t("svh.services")}</Link>{s.categoryName && <><span aria-hidden>/</span><Link href={`/services?cat=${encodeURIComponent(s.categoryName)}`}>{s.categoryName}</Link></>}</nav>
      <div className="mu-detail" style={tint(s.categoryName || s.name)}>
        <div className="mu-gallery">
          <div className="mu-gallery-main"><Pic id={pics[0]} variant="original" className="mu-gallery-img" fallback={<span className="mu-gallery-ph" aria-hidden>{firstLetter(s.name)}</span>} /></div>
          {pics.length > 1 && <ul className="mu-gallery-thumbs">{pics.slice(1, 5).map((id) => <li key={id}><Pic id={id} variant="thumb" className="mu-gallery-t" /></li>)}</ul>}
        </div>
        <div className="mu-info">
          {s.categoryName && <span className="mu-tag">{s.categoryName}</span>}
          <h1 className="mu-h1 mu-h1-sm">{s.name}</h1>
          <p className="mu-detail-price"><span className="mu-price tnum">{isOffer(s) && <s>{money(s.priceBeforeMinor, s.currency, locale)}</s>}{money(s.priceMinor, s.currency, locale)}</span></p>
          <ul className="mu-pillfacts"><li><Icon name="clock" className="h-4 w-4" />{s.durationMinutes} {t("bk.min")}</li><li><Icon name="pin" className="h-4 w-4" />{where(s, t)}</li>{s.serviceArea && <li>{s.serviceArea}</li>}</ul>
          <Link href={`/book?service=${s.id}`} className="vf-btn mu-big">{t("vn.mu.book")}</Link>
          {(s.staff ?? []).length > 0 && <p className="mu-note">{t("vn.withWhom")}: {(s.staff ?? []).map((x) => x.name).join("، ")}</p>}
        </div>
      </div>
      <div className="mu-cols">
        <div>
          {s.description && <section className="mu-sect"><h2 className="mu-h4">{t("vn.mu.about")}</h2><p className="mu-copy">{s.description}</p></section>}
          {s.instructions && <section className="mu-sect"><h2 className="mu-h4">{t("vn.beforeVisit")}</h2><p className="mu-copy">{s.instructions}</p></section>}
          {(s.addons ?? []).length > 0 && <section className="mu-sect"><h2 className="mu-h4">{t("book.options")}</h2><ul className="mu-opts">{(s.addons ?? []).map((a) => <li key={a.id}><span>{a.name}</span><bdi className="tnum">+{money(a.priceMinor, s.currency, locale)} · +{a.durationMinutes} {t("bk.min")}</bdi></li>)}</ul></section>}
          {s.cancellationPolicy && <section className="mu-sect"><h2 className="mu-h4">{t("vn.rp.policy")}</h2><p className="mu-copy">{s.cancellationPolicy}</p></section>}
        </div>
        <aside className="mu-sect mu-hoursbox"><h2 className="mu-h4">{t("identity.hours")}</h2><VnWeek d={d} /></aside>
      </div>
      {related.length > 0 && <section className="mu-sec"><div className="mu-shelf-head"><h2 className="mu-h2">{t("vn.related")}</h2></div><div className="mu-rail">{related.map((x) => <Card key={x.id} s={x} t={t} locale={locale} />)}</div></section>}
      <div className="mu-dock"><Link href={`/book?service=${s.id}`} className="vf-btn">{t("vn.mu.book")}</Link></div>
    </div>
  );
}
