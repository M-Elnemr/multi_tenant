import Link from "next/link";
import { Icon } from "@/components/icons";
import type { ShopProduct } from "@/components/shop/product-card";
import type { ShopProfile } from "@/components/shop/types";
import { Reveal } from "@/components/shop/templates/reveal";
import type { ProductDetail } from "@/app/t-store/(shop)/(page)/products/[slug]/buy";
import { backendJson } from "@/lib/backend";
import type { Locale } from "@/lib/i18n";
import { whatsappLink } from "@/lib/whatsapp";
import { PnButton, PnCard, PnEmpty, PnPhoto, PnPrice, PnStars, cardPic, picOf, type T } from "../pn-kit/primitives";

type Props = { p: ShopProfile | undefined; products: ShopProduct[]; t: T; locale: Locale };

/** Single-product flagship: the product is the whole page. Everything below the hero is that product's own data (specs, photos, description, reviews); a section with no data is simply not drawn. */
export async function FlagshipHome({ p, products, t, locale }: Props) {
  const name = p?.storeName ?? "";
  const hero = products[0];
  if (!hero) return <div data-small-home data-pn="flagship"><PnEmpty name={name} t={t} /></div>;
  const rest = products.slice(1, 3);
  const d = await backendJson<ProductDetail>(`/shop/products/${encodeURIComponent(hero.slug)}?lang=${locale}`).catch(() => null);
  const blurb = d?.shortDescription || p?.shortDescription || "";
  const heroPic = picOf(d?.media[0]) ?? cardPic(hero);
  const photos = (d?.media ?? []).slice(1, 4).map((m) => picOf(m)).filter((x): x is string => !!x);
  const specs = (d?.specs ?? []).slice(0, 6);
  const paras = (d?.description ?? "").split(/\n+/).map((s) => s.trim()).filter(Boolean).slice(0, 3);
  const reviews = (d?.reviews.reviews ?? []).filter((r) => r.reviewText).slice(0, 3);
  const href = `/products/${hero.slug}`;
  const wa = p?.whatsapp ? whatsappLink(p.whatsapp, hero.name) : null;
  return (
    <div data-small-home data-pn="flagship">
      <section className="pn-wrap pnf-hero">
        <div className="pnf-hero-text">
          <p className="pn-eyebrow">{name}</p>
          <h1>{hero.name}</h1>
          {blurb && <p className="pnf-lede">{blurb}</p>}
          <div className="pnf-buyrow"><PnPrice x={hero} locale={locale} className="pnf-price" /><PnButton href={href}>{t("pn.flag.buy")}</PnButton></div>
          {d && <PnStars value={d.reviews.average} count={d.reviews.count} label={t("shop.reviews")} />}
          {!hero.inStock && <p className="pn-note">{t("shop.outOfStock")}</p>}
        </div>
        <div className="pnf-hero-media"><PnPhoto src={heroPic} alt={hero.name} priority />{hero.offer && <span className="pnf-badge">{hero.offer.badge || t("shop.off")}</span>}</div>
      </section>

      {specs.length > 0 && (
        <section className="pn-wrap pnf-specs"><Reveal><h2>{t("pn.flag.specs")}</h2><dl>{specs.map((s) => <div key={s.k}><dt>{s.k}</dt><dd>{s.v}</dd></div>)}</dl></Reveal></section>
      )}

      {photos.length > 0 && (
        <section className="pn-wrap pnf-gallery" data-n={photos.length}><Reveal>{photos.map((src, i) => <PnPhoto key={i} src={src} alt={hero.name} />)}</Reveal></section>
      )}

      {paras.length > 0 && (
        <section className="pn-wrap pnf-story"><Reveal><h2>{t("pn.flag.story")}</h2>{paras.map((s, i) => <p key={i}>{s}</p>)}</Reveal></section>
      )}

      {reviews.length > 0 && (
        <section className="pn-wrap pnf-reviews"><Reveal><h2>{t("shop.reviews")}</h2>
          <ul>{reviews.map((r, i) => <li key={i}><span className="pn-gold">{"★".repeat(r.rating)}</span><p>{r.reviewText}</p><b>{r.firstName}</b></li>)}</ul></Reveal></section>
      )}

      {rest.length > 0 && (
        <section className="pn-wrap pnf-more"><Reveal><h2>{t("sm.more")}</h2>
          <div className="pnf-more-grid" data-n={rest.length}>{rest.map((x) => <PnCard key={x.id} x={x} locale={locale} t={t} />)}</div></Reveal></section>
      )}

      <section className="pn-wrap pnf-cta"><Reveal>
        <h2>{hero.name}</h2><PnPrice x={hero} locale={locale} className="pnf-price" />
        <div className="pnf-cta-row"><PnButton href={href}>{t("pn.flag.buy")}</PnButton>
          {wa && <a href={wa} className="pn-btn pn-btn-ghost" target="_blank" rel="noopener noreferrer"><Icon name="chat" className="h-4 w-4" />{t("shop.chatWithUs")}</a>}</div>
        <ul className="pnf-trust">{[t("trust.cod"), t("trust.delivery"), t("trust.returns")].map((x) => <li key={x}><Icon name="check" className="h-4 w-4" />{x}</li>)}</ul>
        <p className="pnf-all"><Link href="/products">{t("shop.viewAll")}</Link></p>
      </Reveal></section>
    </div>
  );
}
