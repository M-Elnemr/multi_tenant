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

/** Pictures run down the left as a stack; the details column stays pinned beside them. Information sits in plain accordions, not tabs. */
export function EditorialBuy({ product, whatsapp, storeName, isOpen, returnDays }: { product: ProductDetail; whatsapp?: string; storeName: string; isOpen: boolean; returnDays: number }) {
  const { t, locale } = useI18n();
  const b = useBuy(product, isOpen);
  const shareUrl = useSyncExternalStore(() => () => {}, () => window.location.href, () => "");
  const out = !b.inStock;
  const cta = !isOpen ? t("shop.closedNow") : out ? t("shop.outOfStock") : t("shop.addToCart");
  const acc = (title: string, body: React.ReactNode, open = false) => <details className="ed-acc" open={open}><summary>{title}<span aria-hidden>+</span></summary><div>{body}</div></details>;
  return (
    <>
      <div className="ed-pdp">
        <LgGallery key={b.shown.map((m) => m.url).join("|")} media={b.shown} name={product.name} mode="stack" badge={b.discount > 0 ? <span className="ed-card-tag">-{b.discount}%</span> : null} />
        <div className="ed-info">
          <div className="ed-info-in">
            {product.brand && <p className="ed-eyebrow">{product.brand}</p>}
            <h1>{product.name}</h1>
            {product.reviews.count > 0 && <a href="#ed-reviews" className="ed-rate"><Stars value={product.reviews.average} count={product.reviews.count} /></a>}
            <p className="lg-price ed-price">
              <span className="lg-price-now" data-sale={b.discount > 0 || undefined}>{b.variant ? money(b.price, product.currency, locale) : "-"}</span>
              {b.discount > 0 && b.was ? <s className="lg-price-was">{money(b.was, product.currency, locale)}</s> : null}
            </p>
            {product.shortDescription && <p className="ed-lede">{product.shortDescription}</p>}
            <ProductOffers offer={product.offer} hints={product.offerHints} currency={product.currency} />
            <Options buy={b} product={product} shape="line" />
            <p className="ed-stock" data-state={out ? "out" : b.available <= 5 ? "low" : "in"} role="status">{out ? t("shop.outOfStock") : b.available <= 5 ? t("shop.onlyLeft", { n: b.available }) : t("lg.inStock")}</p>
            <div className="ed-buyrow">
              <Qty value={b.qty} onChange={b.setQty} max={b.available} />
              <button className="ed-btn" onClick={() => b.add()} disabled={out || !isOpen}>{cta}</button>
              <button className="lg-iconbtn" onClick={() => b.wish.run()} aria-label={t("shop.wishlist")}><Icon name="heart" className="h-5 w-5" /></button>
            </div>
            {b.inStock && isOpen && <>
              <input className="ed-input" value={b.note} onChange={(e) => b.setNote(e.target.value)} maxLength={200} placeholder={t("shop.itemNote")} aria-label={t("shop.itemNote")} />
              <button className="ed-btn ed-btn-ghost" onClick={() => b.add(true)}>{t("shop.buyNow")}</button>
            </>}
            {b.added && <p className="ed-added" role="status">{t("shop.added")}</p>}
            <ErrorText error={b.wish.error} />
            {whatsapp && <a className="ed-link ed-wa" href={whatsappLink(whatsapp, `${storeName}\n${product.name}${b.label ? ` (${b.label})` : ""} × ${b.qty}\n${shareUrl}`)} target="_blank" rel="noopener noreferrer">{t("shop.chatWithUs")}</a>}
            <div className="ed-accs">
              {acc(t("product.details"), <p>{product.description || t("product.noDescription")}</p>, true)}
              {!!product.specs?.length && acc(t("product.specs"), <dl>{product.specs.map((s, i) => <div key={i}><dt>{s.k}</dt><dd>{s.v}</dd></div>)}</dl>)}
              {product.sizeGuide && acc(t("product.sizeGuide"), <p>{product.sizeGuide}</p>)}
              {acc(t("product.deliveryReturns"), <><p>{t("product.deliveryInfo")}</p><p>{t("product.returnInfo", { n: returnDays })}</p></>)}
            </div>
          </div>
        </div>
      </div>
      <RecentlyViewed heading={t("lg.recent")} current={{ slug: product.slug, name: product.name, priceMinor: b.price, currency: product.currency, img: mediaUrl(b.shown[0], "thumb") ?? null }} />
    </>
  );
}
