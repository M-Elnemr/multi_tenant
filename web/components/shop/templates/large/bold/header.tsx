"use client";
/* eslint-disable @next/next/no-img-element */

import Link from "next/link";
import { useState } from "react";
import { Icon } from "@/components/icons";
import { useI18n } from "@/components/i18n-provider";
import { buildTree } from "@/components/shop/types";
import { AccountLink, CartLink, LangLink, NavTree, TabBar, usePanel } from "../chrome";
import { SearchField } from "../kit";
import type { HeaderProps } from "../types";

/** Bold header: a scrolling ticker strip, a thick-bordered bar with a coloured logo block, boxed icon buttons and a row of chunky category tabs. */
export function BoldHeader({ name, logoUrl, announcement, closedMessage, isOpen, categories }: HeaderProps) {
  const { t } = useI18n();
  const [menu, setMenu] = useState(false);
  const [find, setFind] = useState(false);
  const close = () => setMenu(false);
  usePanel(menu, close);
  const tree = buildTree(categories).filter((c) => c.productCount > 0);
  const msg = (!isOpen ? closedMessage || t("shop.closedNow") : announcement) || t("shop.offers");
  return (
    <>
      <a href="#lg-main" className="lg-skip">{t("lg.skip")}</a>
      <div className="bd-ticker" role="status"><div className="bd-ticker-track" aria-hidden={false}>{[0, 1, 2, 3].map((i) => <span key={i} aria-hidden={i > 0}>{msg}<i>✦</i></span>)}</div></div>
      <header className="bd-head">
        <div className="bd-bar">
          <button className="bd-box bd-menu" onClick={() => setMenu(true)} aria-label={t("shop.menu")} aria-expanded={menu}><Icon name="menu" className="h-6 w-6" /></button>
          <Link href="/" className="bd-logo" aria-label={name}>{logoUrl ? <img src={logoUrl} alt="" height={32} /> : null}<span>{name}</span></Link>
          <SearchField className="bd-search" />
          <div className="bd-acts">
            <button className="bd-box bd-find" onClick={() => setFind(!find)} aria-label={t("shop.search")} aria-expanded={find}><Icon name="search" className="h-[22px] w-[22px]" /></button>
            <LangLink className="bd-lang" />
            <AccountLink />
            <CartLink className="bd-box bd-bag" />
          </div>
        </div>
        {find && <div className="bd-findrow"><SearchField autoFocus onDone={() => setFind(false)} /></div>}
        <nav className="bd-tabs" aria-label={t("shop.categories")}>
          <Link href="/products">{t("shop.allProducts")}</Link>
          {tree.slice(0, 8).map((c) => <Link key={c.id} href={`/c/${c.slug}`}>{c.name}</Link>)}
          <Link href="/products?onSale=true&sort=discount" className="bd-hot">{t("shop.offers")}</Link>
        </nav>
      </header>
      {menu && (
        <div className="bd-drawer-wrap" role="dialog" aria-modal="true" aria-label={t("shop.categories")}>
          <button className="bd-scrim" onClick={close} aria-label={t("common.close")} />
          <aside className="bd-drawer">
            <div className="bd-drawer-head"><b>{t("shop.categories")}</b><button className="bd-box" onClick={close} aria-label={t("common.close")}><Icon name="close" className="h-5 w-5" /></button></div>
            <ul className="bd-quick"><li><Link href="/products" onClick={close}>{t("shop.allProducts")}</Link></li><li><Link href="/products?onSale=true&sort=discount" onClick={close}>{t("shop.offers")}</Link></li><li><Link href="/products?sort=newest" onClick={close}>{t("shop.newest")}</Link></li></ul>
            <NavTree nodes={tree} close={close} />
          </aside>
        </div>
      )}
      <TabBar />
    </>
  );
}
