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

/** Marketplace header: announcement strip, logo + one wide search + account/cart, then a category bar with an "all departments" drawer. */
export function MarketHeader({ name, logoUrl, announcement, closedMessage, isOpen, categories }: HeaderProps) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const [find, setFind] = useState(false);
  const close = () => setOpen(false);
  usePanel(open, close);
  const tree = buildTree(categories).filter((c) => c.productCount > 0);
  const tops = tree.slice(0, 7);
  return (
    <>
      <a href="#lg-main" className="lg-skip">{t("lg.skip")}</a>
      {(announcement || !isOpen) && <div className="mk-announce" role="status">{!isOpen ? closedMessage || t("shop.closedNow") : announcement}</div>}
      <header className="mk-head">
        <div className="lg-wrap mk-bar">
          <button className="lg-iconbtn mk-burger" onClick={() => setOpen(true)} aria-label={t("shop.menu")} aria-expanded={open}><Icon name="menu" className="h-6 w-6" /></button>
          <Link href="/" className="mk-logo" aria-label={name}>
            {logoUrl ? <img src={logoUrl} alt="" width={36} height={36} /> : <span className="mk-logo-mark" aria-hidden>{name.trim().charAt(0)}</span>}
            <span className="mk-logo-name">{name}</span>
          </Link>
          <SearchField className="mk-search" />
          <div className="mk-actions">
            <button className="lg-iconbtn mk-find" onClick={() => setFind(!find)} aria-label={t("shop.search")} aria-expanded={find}><Icon name="search" className="h-[22px] w-[22px]" /></button>
            <LangLink />
            <AccountLink withName />
            <CartLink />
          </div>
        </div>
        {find && <div className="lg-wrap mk-find-row"><SearchField autoFocus onDone={() => setFind(false)} /></div>}
        <nav className="mk-cats" aria-label={t("shop.categories")}>
          <div className="lg-wrap mk-cats-in">
            <button className="mk-all" onClick={() => setOpen(true)}><Icon name="menu" className="h-[18px] w-[18px]" />{t("lg.allDepartments")}</button>
            <ul>
              {tops.map((c) => <li key={c.id}><Link href={`/c/${c.slug}`}>{c.name}</Link></li>)}
              <li className="mk-deals"><Link href="/products?onSale=true&sort=discount"><Icon name="tag" className="h-4 w-4" />{t("shop.offers")}</Link></li>
            </ul>
          </div>
        </nav>
      </header>
      {open && (
        <div className="mk-drawer-wrap" role="dialog" aria-modal="true" aria-label={t("shop.categories")}>
          <button className="mk-scrim" onClick={close} aria-label={t("common.close")} />
          <aside className="mk-drawer">
            <div className="mk-drawer-head"><b>{t("shop.categories")}</b><button className="lg-iconbtn" onClick={close} aria-label={t("common.close")}><Icon name="close" className="h-5 w-5" /></button></div>
            <ul className="mk-drawer-quick">
              <li><Link href="/products" onClick={close}>{t("shop.allProducts")}</Link></li>
              <li><Link href="/products?onSale=true&sort=discount" onClick={close}>{t("shop.offers")}</Link></li>
              <li><Link href="/products?sort=newest" onClick={close}>{t("shop.newest")}</Link></li>
            </ul>
            <NavTree nodes={tree} close={close} />
          </aside>
        </div>
      )}
      <TabBar />
    </>
  );
}
