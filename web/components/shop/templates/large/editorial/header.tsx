"use client";
/* eslint-disable @next/next/no-img-element */

import Link from "next/link";
import { useState } from "react";
import { Icon } from "@/components/icons";
import { useI18n } from "@/components/i18n-provider";
import { buildTree } from "@/components/shop/types";
import { useCart } from "@/lib/cart";
import { AccountLink, LangLink, usePanel } from "../chrome";
import { SearchField } from "../kit";
import type { HeaderProps } from "../types";

/** Editorial header: a centred serif wordmark, "Menu" and "Search" as words on either side, and a full-screen menu of large serif category names. */
export function EditorialHeader({ name, logoUrl, announcement, closedMessage, isOpen, categories }: HeaderProps) {
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
      {(announcement || !isOpen) && <p className="ed-note" role="status">{!isOpen ? closedMessage || t("shop.closedNow") : announcement}</p>}
      <header className="ed-head">
        <div className="ed-bar">
          <div className="ed-side">
            <button className="ed-word" onClick={() => setMenu(true)} aria-expanded={menu}><Icon name="menu" className="h-5 w-5 ed-ico" /><span>{t("shop.menu")}</span></button>
            <button className="ed-word" onClick={() => setFind(true)} aria-expanded={find}><Icon name="search" className="h-5 w-5 ed-ico" /><span>{t("lg.searchWord")}</span></button>
          </div>
          <Link href="/" className="ed-mark" aria-label={name}>{logoUrl ? <img src={logoUrl} alt="" height={40} /> : null}<span>{name}</span></Link>
          <div className="ed-side ed-side-end">
            <LangLink />
            <AccountLink />
            <Link href="/cart" className="ed-word ed-bag" aria-label={`${t("nav.cart")} (${n})`}><span>{t("nav.cart")}</span><b>({n})</b></Link>
          </div>
        </div>
        <nav className="ed-nav" aria-label={t("shop.categories")}>
          {tree.slice(0, 6).map((c) => <Link key={c.id} href={`/c/${c.slug}`}>{c.name}</Link>)}
          <Link href="/products?onSale=true&sort=discount" className="ed-nav-sale">{t("shop.offers")}</Link>
        </nav>
      </header>
      {menu && (
        <div className="ed-over" role="dialog" aria-modal="true" aria-label={t("shop.menu")}>
          <div className="ed-over-top"><span className="ed-mark-s">{name}</span><button className="ed-word" onClick={close}><Icon name="close" className="h-5 w-5" /><span>{t("common.close")}</span></button></div>
          <ul className="ed-big">
            <li><Link href="/products" onClick={close}><i>00</i>{t("shop.allProducts")}</Link></li>
            {tree.map((c, i) => (
              <li key={c.id}>
                <Link href={`/c/${c.slug}`} onClick={close}><i>{String(i + 1).padStart(2, "0")}</i>{c.name}</Link>
                {c.children.length > 0 && <div className="ed-sub">{c.children.map((k) => <Link key={k.id} href={`/c/${k.slug}`} onClick={close}>{k.name}</Link>)}</div>}
              </li>
            ))}
            <li><Link href="/products?onSale=true&sort=discount" onClick={close}><i>★</i>{t("shop.offers")}</Link></li>
          </ul>
        </div>
      )}
      {find && (
        <div className="ed-over ed-over-find" role="dialog" aria-modal="true" aria-label={t("shop.search")}>
          <div className="ed-over-top"><span className="ed-mark-s">{t("shop.search")}</span><button className="ed-word" onClick={close}><Icon name="close" className="h-5 w-5" /><span>{t("common.close")}</span></button></div>
          <div className="ed-find"><SearchField autoFocus big onDone={close} /></div>
        </div>
      )}
    </>
  );
}
