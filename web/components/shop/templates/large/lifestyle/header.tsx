"use client";
/* eslint-disable @next/next/no-img-element */

import Link from "next/link";
import { useState } from "react";
import { Icon } from "@/components/icons";
import { useI18n } from "@/components/i18n-provider";
import { buildTree } from "@/components/shop/types";
import { fileUrl } from "@/lib/media";
import { AccountLink, CartLink, LangLink, TabBar, usePanel } from "../chrome";
import { SearchField } from "../kit";
import type { HeaderProps } from "../types";

/** Lifestyle header: a soft rounded bar (logo, big pill search, icons) and a strip of round "collection" avatars you can swipe, like stories. The menu is a full sheet of collection cards. */
export function LifestyleHeader({ name, logoUrl, announcement, closedMessage, isOpen, categories }: HeaderProps) {
  const { t } = useI18n();
  const [menu, setMenu] = useState(false);
  const [find, setFind] = useState(false);
  const close = () => setMenu(false);
  usePanel(menu, close);
  const tree = buildTree(categories).filter((c) => c.productCount > 0);
  return (
    <>
      <a href="#lg-main" className="lg-skip">{t("lg.skip")}</a>
      {(announcement || !isOpen) && <p className="lf-note" role="status">{!isOpen ? closedMessage || t("shop.closedNow") : announcement}</p>}
      <header className="lf-head">
        <div className="lf-bar">
          <button className="lf-round lf-menu" onClick={() => setMenu(true)} aria-label={t("shop.menu")} aria-expanded={menu}><Icon name="menu" className="h-6 w-6" /></button>
          <Link href="/" className="lf-logo" aria-label={name}>{logoUrl ? <img src={logoUrl} alt="" height={36} /> : <i aria-hidden>{name.trim().charAt(0)}</i>}<span>{name}</span></Link>
          <SearchField className="lf-search" />
          <div className="lf-acts">
            <button className="lf-round lf-find" onClick={() => setFind(!find)} aria-label={t("shop.search")} aria-expanded={find}><Icon name="search" className="h-[22px] w-[22px]" /></button>
            <LangLink />
            <AccountLink />
            <CartLink className="lf-round lf-bag" />
          </div>
        </div>
        {find && <div className="lf-findrow"><SearchField autoFocus onDone={() => setFind(false)} /></div>}
        <nav className="lf-stories" aria-label={t("shop.categories")}>
          <Link href="/products" className="lf-story"><span className="lf-story-ring"><span className="lf-story-in"><Icon name="grid" className="h-6 w-6" /></span></span><span className="lf-story-name">{t("shop.allProducts")}</span></Link>
          {tree.map((c) => (
            <Link key={c.id} href={`/c/${c.slug}`} className="lf-story">
              <span className="lf-story-ring"><span className="lf-story-in">{c.imageFileId ? <img src={fileUrl(c.imageFileId, "thumb")} alt="" /> : <b>{c.name.trim().charAt(0)}</b>}</span></span>
              <span className="lf-story-name">{c.name}</span>
            </Link>
          ))}
        </nav>
      </header>
      {menu && (
        <div className="lf-over" role="dialog" aria-modal="true" aria-label={t("shop.categories")}>
          <div className="lf-over-in">
            <div className="lf-over-top"><b>{t("shop.categories")}</b><button className="lf-round lf-over-close" onClick={close} aria-label={t("common.close")}><Icon name="close" className="h-5 w-5" /></button></div>
            <ul className="lf-cards">
              {tree.map((c, i) => (
                <li key={c.id} data-tone={i % 5}>
                  <Link href={`/c/${c.slug}`} onClick={close}><b>{c.name}</b><small>{c.productCount}</small></Link>
                  {c.children.length > 0 && <p>{c.children.slice(0, 4).map((k) => <Link key={k.id} href={`/c/${k.slug}`} onClick={close}>{k.name}</Link>)}</p>}
                </li>
              ))}
            </ul>
            <p className="lf-over-links"><Link href="/products?onSale=true&sort=discount" onClick={close}>{t("shop.offers")}</Link><Link href="/products?sort=newest" onClick={close}>{t("shop.newest")}</Link><Link href="/products" onClick={close}>{t("shop.allProducts")}</Link></p>
          </div>
        </div>
      )}
      <TabBar />
    </>
  );
}
