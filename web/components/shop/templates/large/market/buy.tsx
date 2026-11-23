"use client";

import { useState, useSyncExternalStore } from "react";
import { Icon } from "@/components/icons";
import { useI18n } from "@/components/i18n-provider";
import { ProductOffers } from "@/components/shop/offer-strip";
import { ErrorText } from "@/components/ui";
import type { ProductDetail } from "@/app/t-store/(shop)/(page)/products/[slug]/buy";
import { money } from "@/lib/format";
import { whatsappLink } from "@/lib/whatsapp";
import { LgGallery, Options, Qty, RecentlyViewed, Stars, useBuy } from "../kit";
import { mediaUrl } from "@/lib/media";

type Tab = "details" | "specs" | "size" | "delivery";

/** Gallery with thumbnails on one side, the buy box on the other (price, options, stock, quantity, add to cart / buy now), tabs below, and a sticky bar on phones. */
export function MarketBuy({ product, whatsapp, storeName, isOpen, returnDays }: { product: ProductDetail; whatsapp?: string; storeName: string; isOpen: boolean; returnDays: number }) {
  const { t, locale } = useI18n();
  const b = useBuy(product, isOpen);
  const [tab, setTab] = useState<Tab>("details");
  const shareUrl = useSyncExternalStore(() => () => {}, () => window.location.href, () => "");
  const tabs = ([
    ["details", t("product.details"), true], ["specs", t("product.specs"), !!product.specs?.length],
    ["size", t("product.sizeGuide"), !!product.sizeGuide], ["delivery", t("product.deliveryReturns"), true],
  ] as [Tab, string, boolean][]).filter((x) => x[2]);
  const badgeText = b.discount > 0 ? `-${b.discount}%` : product.badge ? t(`badge.${product.badge}`) : "";
  const out = !b.inStock;
  const cta = !isOpen ? t("shop.closedNow") : out ? t("shop.outOfStock") : t("shop.addToCart");
  return (
    <>
      <div className="mk-pdp">
        <LgGallery key={b.shown.map((m) => m.url).join("|")} media={b.shown} name={product.name} mode="thumbs" badge={badgeText ? <span className="lg-badge" data-kind={b.discount > 0 ? "sale" : "new"}>{badgeText}</span> : null} />
        <div className="mk-buy">
          {product.brand && <p className="mk-brand">{product.brand}</p>}
          <h1>{product.name}</h1>
          {product.reviews.count > 0 && <a href="#mk-reviews" className="mk-rating"><Stars value={product.reviews.average} count={product.reviews.count} /></a>}
          {product.shortDescription && <p className="mk-short">{product.shortDescription}</p>}
          <p className="lg-price mk-pdp-price">
            <span className="lg-price-now" data-sale={b.discount > 0 || undefined}>{b.variant ? money(b.price, product.currency, locale) : "-"}</span>
            {b.discount > 0 && b.was ? <><s className="lg-price-was">{money(b.was, product.currency, locale)}</s><span className="mk-save">{t("lg.save", { amount: money(b.was - b.price, product.currency, locale) })}</span></> : null}
          </p>
          <ProductOffers offer={product.offer} hints={product.offerHints} currency={product.currency} />
          <Options buy={b} product={product} shape="pill" />
          <p className="mk-stock" data-state={out ? "out" : b.available <= 5 ? "low" : "in"} role="status">
            <Icon name={out ? "alert" : "check"} className="h-4 w-4" />
            {out ? t("shop.outOfStock") : b.available <= 5 ? t("shop.onlyLeft", { n: b.available }) : t("lg.inStock")}
          </p>
          <div className="mk-actions-row">
            <Qty value={b.qty} onChange={b.setQty} max={b.available} />
            <button className="mk-btn mk-add" onClick={() => b.add()} disabled={out || !isOpen}>{cta}</button>
            <button className="lg-iconbtn mk-wish" onClick={() => b.wish.run()} aria-label={t("shop.wishlist")}><Icon name="heart" className="h-5 w-5" /></button>
          </div>
          {b.inStock && isOpen && <>
            <input className="mk-note" value={b.note} onChange={(e) => b.setNote(e.target.value)} maxLength={200} placeholder={t("shop.itemNote")} aria-label={t("shop.itemNote")} />
            <button className="mk-btn mk-btn-ghost" onClick={() => b.add(true)}>{t("shop.buyNow")}</button>
          </>}
          {b.added && <p className="mk-added" role="status"><Icon name="check" className="h-4 w-4" />{t("shop.added")}</p>}
          <ErrorText error={b.wish.error} />
          {whatsapp && <a className="mk-wa" href={whatsappLink(whatsapp, `${storeName}\n${product.name}${b.label ? ` (${b.label})` : ""} × ${b.qty}\n${shareUrl}`)} target="_blank" rel="noopener noreferrer"><Icon name="chat" className="h-4 w-4" />{t("shop.chatWithUs")}</a>}
          <ul className="mk-assure">
            <li><Icon name="shield" className="h-4 w-4" />{t("trust.cod")}</li><li><Icon name="truck" className="h-4 w-4" />{t("trust.deliveryText")}</li>
            <li><Icon name="return" className="h-4 w-4" />{t("trust.returnsText", { n: returnDays })}</li><li><Icon name="lock" className="h-4 w-4" />{t("product.secure")}</li>
          </ul>
        </div>
      </div>
      <section className="mk-tabs">
        <div className="mk-tablist" role="tablist">{tabs.map(([k, label]) => <button key={k} role="tab" aria-selected={tab === k} onClick={() => setTab(k)}>{label}</button>)}</div>
        <div className="mk-tabpanel" role="tabpanel">
          {tab === "details" && <p className="mk-prose">{product.description || t("product.noDescription")}</p>}
          {tab === "specs" && <dl className="mk-specs">{product.specs?.map((s, i) => <div key={i}><dt>{s.k}</dt><dd>{s.v}</dd></div>)}</dl>}
          {tab === "size" && <p className="mk-prose">{product.sizeGuide}</p>}
          {tab === "delivery" && <div className="mk-prose"><p>{t("product.deliveryInfo")}</p><p>{t("product.returnInfo", { n: returnDays })}</p></div>}
        </div>
      </section>
      <div className="mk-buybar" data-buybar>
        <div><span>{product.name}</span><b>{b.variant ? money(b.price, product.currency, locale) : "-"}</b></div>
        <button className="mk-btn" onClick={() => b.add()} disabled={out || !isOpen}>{cta}</button>
      </div>
      <RecentlyViewed heading={t("lg.recent")} current={{ slug: product.slug, name: product.name, priceMinor: b.price, currency: product.currency, img: mediaUrl(b.shown[0], "thumb") ?? null }} />
    </>
  );
}
