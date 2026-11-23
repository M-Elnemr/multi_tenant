import Link from "next/link";
import { Reveal } from "@/components/shop/templates/reveal";
import { ShopProductCard } from "@/components/shop/product-card";
import { Icon } from "@/components/icons";
import { dayRanges, groupServices, isOffer, todayWeekday } from "../vn-core/data";
import type { VnData } from "../vn-core/types";
import { ArchCard, MenuLine, StaffRow, coverUrl, uniqueStaff } from "./parts";
import { WlVisit } from "./pages";

export function WellnessHome(d: VnData) {
  const { p, cfg, products, t, locale } = d;
  const name = p?.storeName ?? "";
  const services = cfg.services;
  const groups = groupServices(cfg);
  const offers = services.filter(isOffer).slice(0, 3);
  const cover = coverUrl(p?.coverFileId) ?? coverUrl(services.find((s) => s.imageFileId)?.imageFileId);
  const blurb = p?.shortDescription || p?.about || t("vn.wl.heroDefault");
  const today = dayRanges(cfg, todayWeekday(cfg.timezone));
  const few = services.length > 0 && services.length <= 3;
  const labels = { outOfStock: t("shop.outOfStock"), off: t("shop.off"), badges: { NEW: t("badge.NEW"), SALE: t("badge.SALE"), BEST_SELLER: t("badge.BEST_SELLER"), LIMITED: t("badge.LIMITED") } };
  return (
    <div className="wl-home">
      <section className="wl-hero wl-wrap">
        <div className="wl-hero-text">
          <p className="wl-kicker wl-rise" style={{ animationDelay: "0ms" }}>{t("vn.wl.eyebrow")}</p>
          <h1 className="wl-h1 wl-rise" style={{ animationDelay: "90ms" }}>{name}</h1>
          <p className="wl-lead wl-rise" style={{ animationDelay: "180ms" }}>{blurb}</p>
          <div className="wl-cta wl-rise" style={{ animationDelay: "270ms" }}>
            {services.length > 0 && <Link href="/book" className="wl-btn"><Icon name="calendar" className="h-4 w-4" />{t("vn.wl.reserve")}</Link>}
            <Link href="/services" className="wl-btn wl-btn-ghost">{t("vn.wl.viewMenu")}</Link>
          </div>
          {today && <p className="wl-today wl-rise" style={{ animationDelay: "340ms" }}><span>{t("vn.today")}</span><bdi dir="ltr" className="tnum">{today}</bdi></p>}
        </div>
        <div className="wl-hero-art wl-rise" style={{ animationDelay: "120ms" }}>
          <div className="wl-arch">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            {cover ? <img src={cover} alt="" className="wl-arch-img" /> : <div className="wl-arch-ph" aria-hidden><span>{Array.from(name.trim())[0] ?? ""}</span></div>}
          </div>
          <span className="wl-orn" aria-hidden />
        </div>
      </section>

      <section id="menu" className="wl-sec wl-wrap">
        <Reveal className="wl-head"><p className="wl-kicker">{t("vn.wl.menuKicker")}</p><h2 className="wl-h2">{t("vn.wl.menuTitle")}</h2></Reveal>
        {services.length === 0 ? <p className="wl-empty">{t("svh.noServices")}</p> : few ? (
          <div className="wl-grid wl-grid-few">{services.map((s, i) => <ArchCard key={s.id} s={s} t={t} locale={locale} i={i} />)}</div>
        ) : (
          <div className="wl-menu">
            {groups.map((g) => (
              <Reveal key={g.key} className="wl-menu-group">
                {g.name && <h3 className="wl-h3">{g.name}</h3>}
                <ul>{g.items.slice(0, 8).map((s) => <MenuLine key={s.id} s={s} t={t} locale={locale} />)}</ul>
                {g.items.length > 8 && <Link href="/services" className="wl-more">{t("vn.moreIn", { n: g.items.length - 8 })}</Link>}
              </Reveal>
            ))}
          </div>
        )}
        {services.length > 0 && <p className="wl-center"><Link href="/services" className="wl-btn wl-btn-ghost">{t("vn.wl.viewMenu")}</Link></p>}
      </section>

      {offers.length > 0 && (
        <section className="wl-sec wl-wrap">
          <Reveal className="wl-head"><p className="wl-kicker">{t("shop.limitedTime")}</p><h2 className="wl-h2">{t("svh.offers")}</h2></Reveal>
          <div className={offers.length < 3 ? "wl-grid wl-grid-few" : "wl-grid"}>{offers.map((s, i) => <ArchCard key={s.id} s={s} t={t} locale={locale} i={i} />)}</div>
        </section>
      )}

      <StaffRow staff={uniqueStaff(services)} kicker={t("vn.wl.teamKicker")} title={t("vn.wl.teamTitle")} />

      {services.length > 0 && (
        <section className="wl-sec wl-band">
          <div className="wl-wrap">
            <Reveal className="wl-head"><p className="wl-kicker">{t("vn.wl.ritualKicker")}</p><h2 className="wl-h2">{t("svh.how")}</h2></Reveal>
            <ol className="wl-steps">
              {[[t("svh.s1"), t("svh.s1b")], [t("svh.s2"), t("svh.s2b")], [t("svh.s3"), cfg.autoConfirm ? t("svh.s3auto") : t("svh.s3b")]].map(([h, b], i) => (
                <li key={h}><Reveal delay={i * 0.1}><span className="wl-num tnum">{String(i + 1).padStart(2, "0")}</span><h3 className="wl-h3">{h}</h3><p>{b}</p></Reveal></li>
              ))}
            </ol>
          </div>
        </section>
      )}

      {products.length > 0 && (
        <section className="wl-sec wl-wrap">
          <Reveal className="wl-head"><p className="wl-kicker">{t("svh.productsHint")}</p><h2 className="wl-h2">{t("vn.wl.boutique")}</h2></Reveal>
          <div className="wl-products">{products.slice(0, 4).map((x) => <ShopProductCard key={x.id} p={x} locale={locale} labels={labels} variant="minimal" />)}</div>
          <p className="wl-center"><Link href="/products" className="wl-more">{t("shop.viewAll")}</Link></p>
        </section>
      )}

      <WlVisit d={d} />
    </div>
  );
}
