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

/** A mosaic of pictures beside a framed buy panel with a hard shadow; the details follow as stacked framed blocks. Sticky buy bar on phones. */
export function BoldBuy({ product, whatsapp, storeName, isOpen, returnDays }: { product: ProductDetail; whatsapp?: string; storeName: string; isOpen: boolean; returnDays: number }) {
  const { t, locale } = useI18n();
  const b = useBuy(product, isOpen);
  const shareUrl = useSyncExternalStore(() => () => {}, () => window.location.href, () => "");
  const out = !b.inStock;
  const cta = !isOpen ? t("shop.closedNow") : out ? t("shop.outOfStock") : t("shop.addToCart");
  const block = (title: string, body: React.ReactNode) => <section className="bd-block"><h2>{title}</h2><div>{body}</div></section>;
  return (
    <>
      <div className="bd-pdp">
        <LgGallery key={b.shown.map((m) => m.url).join("|")} media={b.shown} name={product.name} mode="grid" badge={b.discount > 0 ? <span className="bd-sticker" data-kind="sale">-{b.discount}%</span> : null} />
        <div className="bd-panel">
          {product.brand && <p className="bd-kicker">{product.brand}</p>}
          <h1>{product.name}</h1>
          {product.reviews.count > 0 && <a href="#bd-reviews" className="bd-rate"><Stars value={product.reviews.average} count={product.reviews.count} /></a>}
          <p className="lg-price bd-price"><span className="lg-price-now" data-sale={b.discount > 0 || undefined}>{b.variant ? money(b.price, product.currency, locale) : "-"}</span>{b.discount > 0 && b.was ? <s className="lg-price-was">{money(b.was, product.currency, locale)}</s> : null}</p>
          {product.shortDescription && <p className="bd-short">{product.shortDescription}</p>}
          <ProductOffers offer={product.offer} hints={product.offerHints} currency={product.currency} />
          <Options buy={b} product={product} shape="square" />
          <p className="bd-stock" data-state={out ? "out" : b.available <= 5 ? "low" : "in"} role="status"><Icon name={out ? "alert" : "check"} className="h-4 w-4" />{out ? t("shop.outOfStock") : b.available <= 5 ? t("shop.onlyLeft", { n: b.available }) : t("lg.inStock")}</p>
          <div className="bd-row"><Qty value={b.qty} onChange={b.setQty} max={b.available} /><button className="bd-btn bd-add" onClick={() => b.add()} disabled={out || !isOpen}>{cta}</button><button className="bd-box" onClick={() => b.wish.run()} aria-label={t("shop.wishlist")}><Icon name="heart" className="h-5 w-5" /></button></div>
          {b.inStock && isOpen && <><input className="bd-input" value={b.note} onChange={(e) => b.setNote(e.target.value)} maxLength={200} placeholder={t("shop.itemNote")} aria-label={t("shop.itemNote")} /><button className="bd-btn bd-btn-o" onClick={() => b.add(true)}>{t("shop.buyNow")}</button></>}
          {b.added && <p className="bd-added" role="status"><Icon name="check" className="h-4 w-4" />{t("shop.added")}</p>}
          <ErrorText error={b.wish.error} />
          {whatsapp && <a className="bd-btn bd-btn-wa" href={whatsappLink(whatsapp, `${storeName}\n${product.name}${b.label ? ` (${b.label})` : ""} × ${b.qty}\n${shareUrl}`)} target="_blank" rel="noopener noreferrer"><Icon name="chat" className="h-4 w-4" />{t("shop.chatWithUs")}</a>}
          <ul className="bd-assure"><li>{t("trust.cod")}</li><li>{t("trust.deliveryText")}</li><li>{t("trust.returnsText", { n: returnDays })}</li><li>{t("product.secure")}</li></ul>
        </div>
      </div>
      <div className="bd-blocks2">
        {block(t("product.details"), <p>{product.description || t("product.noDescription")}</p>)}
        {!!product.specs?.length && block(t("product.specs"), <dl>{product.specs.map((s, i) => <div key={i}><dt>{s.k}</dt><dd>{s.v}</dd></div>)}</dl>)}
        {product.sizeGuide && block(t("product.sizeGuide"), <p>{product.sizeGuide}</p>)}
        {block(t("product.deliveryReturns"), <><p>{t("product.deliveryInfo")}</p><p>{t("product.returnInfo", { n: returnDays })}</p></>)}
      </div>
      <div className="bd-buybar" data-buybar><div><span>{product.name}</span><b>{b.variant ? money(b.price, product.currency, locale) : "-"}</b></div><button className="bd-btn" onClick={() => b.add()} disabled={out || !isOpen}>{cta}</button></div>
      <RecentlyViewed heading={t("lg.recent")} current={{ slug: product.slug, name: product.name, priceMinor: b.price, currency: product.currency, img: mediaUrl(b.shown[0], "thumb") ?? null }} />
    </>
  );
}
