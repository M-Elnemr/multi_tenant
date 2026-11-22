"use client";

import Link from "next/link";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { usePathname } from "next/navigation";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { AccountLink, CartLink, LangLink, useMenu, useScrolled } from "../vn-core/tools";
import type { ChromeProps } from "../vn-core/types";
import "./wellness.css";

/** Luxury header: the name sits in the middle in spaced capitals, quiet links either side, one understated reserve button. */
export function WellnessHeader({ name, logoUrl, announcement, closedMessage, isOpen, hasProducts, hasBooking }: ChromeProps) {
  const { t } = useI18n();
  const { open, setOpen } = useMenu();
  const scrolled = useScrolled(12);
  const reduce = useReducedMotion();
  const path = usePathname();
  const links = [
    { href: "/services", label: t("vn.wl.nav.treatments") },
    ...(hasProducts ? [{ href: "/products", label: t("vn.wl.nav.boutique") }] : []),
    { href: "/contact", label: t("vn.wl.nav.visit") },
    ...(hasBooking ? [{ href: "/book/manage", label: t("vn.nav.myBooking") }] : []),
  ];
  const left = links.slice(0, 2), right = links.slice(2);
  const note = !isOpen ? (closedMessage || t("shop.closedNow")) : announcement;
  const brand = (
    <Link href="/" className="wl-brand" aria-label={name}>
      {logoUrl && /* eslint-disable-next-line @next/next/no-img-element */ <img src={logoUrl} alt="" className="wl-logo" />}
      <span className="wl-brand-name">{name}</span>
    </Link>
  );
  return (
    <>
      {note && <div className="wl-note">{note}</div>}
      <header className="wl-header" data-scrolled={scrolled ? "true" : "false"}>
        <div className="wl-header-row">
          <nav className="wl-nav wl-nav-l" aria-label={t("shop.menu")}>{left.map((l) => <Link key={l.href} href={l.href} aria-current={path.startsWith(l.href) ? "page" : undefined}>{l.label}</Link>)}</nav>
          {brand}
          <div className="wl-tools">
            <nav className="wl-nav wl-nav-r" aria-label={t("shop.menu")}>{right.map((l) => <Link key={l.href} href={l.href} aria-current={path.startsWith(l.href) ? "page" : undefined}>{l.label}</Link>)}</nav>
            <LangLink className="wl-icon wl-lang" />
            {hasProducts && <CartLink className="wl-icon wl-cart" badgeClass="wl-badge" />}
            <AccountLink className="wl-icon wl-account" />
            {hasBooking && <Link href="/book" className="wl-btn wl-btn-sm wl-reserve">{t("vn.wl.reserve")}</Link>}
            <button type="button" className="wl-icon wl-burger" aria-label={t("shop.menu")} aria-expanded={open} onClick={() => setOpen(true)}><Icon name="menu" className="h-6 w-6" /></button>
          </div>
        </div>
      </header>
      <AnimatePresence>
        {open && (
          <motion.div className="wl-drawer" role="dialog" aria-modal="true" aria-label={t("shop.menu")} initial={reduce ? false : { opacity: 0 }} animate={{ opacity: 1 }} exit={reduce ? { opacity: 0 } : { opacity: 0 }} transition={{ duration: reduce ? 0 : 0.35 }}>
            <div className="wl-drawer-top">{brand}<button type="button" className="wl-icon" aria-label={t("common.close")} onClick={() => setOpen(false)}><Icon name="close" className="h-6 w-6" /></button></div>
            <motion.ul className="wl-drawer-links" initial={reduce ? false : "h"} animate="s" variants={{ s: { transition: { staggerChildren: 0.06, delayChildren: 0.1 } } }}>
              {[{ href: "/", label: t("shop.home") }, ...links, ...(hasBooking ? [{ href: "/book", label: t("vn.wl.reserve") }] : [])].map((l) => (
                <motion.li key={l.href} variants={{ h: { opacity: 0, y: 14 }, s: { opacity: 1, y: 0 } }}><Link href={l.href} onClick={() => setOpen(false)}>{l.label}</Link></motion.li>
              ))}
            </motion.ul>
            <div className="wl-drawer-foot"><LangLink className="wl-drawer-lang" /><AccountLink className="wl-drawer-lang" withLabel />{hasProducts && <CartLink className="wl-drawer-lang" badgeClass="wl-badge" />}</div>
          </motion.div>
        )}
      </AnimatePresence>
    </>
  );
}
