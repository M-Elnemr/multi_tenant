"use client";

import { useSyncExternalStore } from "react";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { ProductOffers } from "@/components/shop/offer-strip";
import { ErrorText } from "@/components/ui";
import type { ProductDetail } from "@/app/t-store/(shop)/(page)/products/[slug]/buy";
import { money } from "@/lib/format";
import { mediaUrl } from "@/lib/media";
import { whatsappLink } from "@/lib/whatsapp";
import { LgGallery, Options, Qty, RecentlyViewed, Stars, useBuy } from "../kit";

/** A swipeable carousel of pictures, then a rounded buy card; benefits as icon tiles under it, details as a segmented "story" below. */
export function LifestyleBuy({ product, whatsapp, storeName, isOpen, returnDays }: { product: ProductDetail; whatsapp?: string; storeName: string; isOpen: boolean; returnDays: number }) {
  const { t, locale } = useI18n();
  const b = useBuy(product, isOpen);
  const shareUrl = useSyncExternalStore(() => () => {}, () => window.location.href, () => "");
  const out = !b.inStock;
  const cta = !isOpen ? t("shop.closedNow") : out ? t("shop.outOfStock") : t("shop.addToCart");
  return (
    <>
      <div className="lf-pdp">
        <LgGallery key={b.shown.map((m) => m.url).join("|")} media={b.shown} name={product.name} mode="carousel" badge={b.discount > 0 ? <span className="lf-dot" data-kind="sale">-{b.discount}%</span> : null} />
        <div className="lf-buy">
          {product.brand && <p className="lf-eyebrow">{product.brand}</p>}
          <h1>{product.name}</h1>
          {product.reviews.count > 0 && <a href="#lf-reviews" className="lf-rate"><Stars value={product.reviews.average} count={product.reviews.count} /></a>}
          <p className="lg-price lf-price"><span className="lg-price-now" data-sale={b.discount > 0 || undefined}>{b.variant ? money(b.price, product.currency, locale) : "-"}</span>{b.discount > 0 && b.was ? <s className="lg-price-was">{money(b.was, product.currency, locale)}</s> : null}</p>
          {product.shortDescription && <p className="lf-short">{product.shortDescription}</p>}
          <ProductOffers offer={product.offer} hints={product.offerHints} currency={product.currency} />
          <Options buy={b} product={product} shape="pill" />
          <p className="lf-stock" data-state={out ? "out" : b.available <= 5 ? "low" : "in"} role="status"><Icon name={out ? "alert" : "check"} className="h-4 w-4" />{out ? t("shop.outOfStock") : b.available <= 5 ? t("shop.onlyLeft", { n: b.available }) : t("lg.inStock")}</p>
          <div className="lf-row"><Qty value={b.qty} onChange={b.setQty} max={b.available} /><button className="lf-btn lf-add" onClick={() => b.add()} disabled={out || !isOpen}>{cta}</button><button className="lf-round" onClick={() => b.wish.run()} aria-label={t("shop.wishlist")}><Icon name="heart" className="h-5 w-5" /></button></div>
          {b.inStock && isOpen && <><input className="lf-input" value={b.note} onChange={(e) => b.setNote(e.target.value)} maxLength={200} placeholder={t("shop.itemNote")} aria-label={t("shop.itemNote")} /><button className="lf-btn lf-btn-soft" onClick={() => b.add(true)}>{t("shop.buyNow")}</button></>}
          {b.added && <p className="lf-added" role="status"><Icon name="check" className="h-4 w-4" />{t("shop.added")}</p>}
          <ErrorText error={b.wish.error} />
          {whatsapp && <a className="lf-btn lf-btn-wa" href={whatsappLink(whatsapp, `${storeName}\n${product.name}${b.label ? ` (${b.label})` : ""} × ${b.qty}\n${shareUrl}`)} target="_blank" rel="noopener noreferrer"><Icon name="chat" className="h-4 w-4" />{t("shop.chatWithUs")}</a>}
          <ul className="lf-perks"><li><Icon name="shield" className="h-5 w-5" />{t("trust.cod")}</li><li><Icon name="truck" className="h-5 w-5" />{t("trust.deliveryText")}</li><li><Icon name="return" className="h-5 w-5" />{t("trust.returnsText", { n: returnDays })}</li><li><Icon name="lock" className="h-5 w-5" />{t("product.secure")}</li></ul>
        </div>
      </div>
      <section className="lf-story-box">
        <article><h2>{t("product.details")}</h2><p>{product.description || t("product.noDescription")}</p></article>
        {!!product.specs?.length && <article><h2>{t("product.specs")}</h2><dl>{product.specs.map((s, i) => <div key={i}><dt>{s.k}</dt><dd>{s.v}</dd></div>)}</dl></article>}
        {product.sizeGuide && <article><h2>{t("product.sizeGuide")}</h2><p>{product.sizeGuide}</p></article>}
        <article><h2>{t("product.deliveryReturns")}</h2><p>{t("product.deliveryInfo")}</p><p>{t("product.returnInfo", { n: returnDays })}</p></article>
      </section>
      <div className="lf-buybar" data-buybar><div><span>{product.name}</span><b>{b.variant ? money(b.price, product.currency, locale) : "-"}</b></div><button className="lf-btn" onClick={() => b.add()} disabled={out || !isOpen}>{cta}</button></div>
      <RecentlyViewed heading={t("lg.recent")} current={{ slug: product.slug, name: product.name, priceMinor: b.price, currency: product.currency, img: mediaUrl(b.shown[0], "thumb") ?? null }} />
    </>
  );
}
