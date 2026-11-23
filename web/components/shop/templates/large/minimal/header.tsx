"use client";
/* eslint-disable @next/next/no-img-element */

import Link from "next/link";
import { useState } from "react";
import { Icon } from "@/components/icons";
import { useI18n } from "@/components/i18n-provider";
import { buildTree } from "@/components/shop/types";
import { useCart } from "@/lib/cart";
import { AccountLink, LangLink, NavTree, usePanel } from "../chrome";
import { SearchField } from "../kit";
import type { HeaderProps } from "../types";

/** Minimal header: a small wordmark at the start, plain-text links at the end, one hairline, then a single scrolling line of category names. Phones get a top sheet. */
export function MinimalHeader({ name, logoUrl, announcement, closedMessage, isOpen, categories }: HeaderProps) {
  const { t } = useI18n();
  const [menu, setMenu] = useState(false);
  const [find, setFind] = useState(false);
  const n = useCart().reduce((s, l) => s + l.quantity, 0);
  const close = () => { setMenu(false); setFind(false); };
  usePanel(menu || find, close);
  const tree = buildTree(categories).filter((c) => c.productCount > 0);
  return (
    <>
      <a href="#lg-main" className="lg-skip">{t("lg.skip")}</a>
      {(announcement || !isOpen) && <p className="mn-note" role="status">{!isOpen ? closedMessage || t("shop.closedNow") : announcement}</p>}
      <header className="mn-head">
        <div className="mn-bar">
          <Link href="/" className="mn-mark" aria-label={name}>{logoUrl ? <img src={logoUrl} alt="" height={22} /> : null}<span>{name}</span></Link>
          <nav className="mn-links" aria-label={t("shop.categories")}>
            <Link href="/products">{t("shop.allProducts")}</Link>
            <Link href="/products?onSale=true&sort=discount">{t("shop.offers")}</Link>
            <Link href="/products?sort=newest">{t("shop.newest")}</Link>
          </nav>
          <div className="mn-end">
            <button className="mn-txt mn-find" onClick={() => setFind(true)} aria-label={t("shop.search")}><Icon name="search" className="h-[18px] w-[18px]" /><span>{t("lg.searchWord")}</span></button>
            <LangLink />
            <AccountLink />
            <Link href="/cart" className="mn-txt mn-bag" aria-label={`${t("nav.cart")} (${n})`}><Icon name="cart" className="h-[18px] w-[18px]" /><b>{n}</b></Link>
            <button className="mn-txt mn-menu" onClick={() => setMenu(true)} aria-label={t("shop.menu")} aria-expanded={menu}><Icon name="menu" className="h-5 w-5" /></button>
          </div>
        </div>
        <div className="mn-cats" aria-label={t("shop.categories")}>
          <div className="mn-cats-in">{tree.map((c) => <Link key={c.id} href={`/c/${c.slug}`}>{c.name}</Link>)}</div>
        </div>
      </header>
      {menu && (
        <div className="mn-over" role="dialog" aria-modal="true" aria-label={t("shop.menu")}>
          <div className="mn-over-card">
            <button className="mn-over-close mn-txt" onClick={close} aria-label={t("common.close")}><Icon name="close" className="h-5 w-5" /></button>
            <ul className="mn-quick"><li><Link href="/products" onClick={close}>{t("shop.allProducts")}</Link></li><li><Link href="/products?onSale=true&sort=discount" onClick={close}>{t("shop.offers")}</Link></li><li><Link href="/products?sort=newest" onClick={close}>{t("shop.newest")}</Link></li></ul>
            <NavTree nodes={tree} close={close} />
          </div>
        </div>
      )}
      {find && (
        <div className="mn-over mn-over-top" role="dialog" aria-modal="true" aria-label={t("shop.search")}>
          <div className="mn-over-card">
            <button className="mn-over-close mn-txt" onClick={close} aria-label={t("common.close")}><Icon name="close" className="h-5 w-5" /></button>
            <SearchField autoFocus onDone={close} />
          </div>
        </div>
      )}
    </>
  );
}
