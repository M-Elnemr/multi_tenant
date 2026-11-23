"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { AccountLink, CartLink, LangLink, useMenu, useScrolled } from "../vn-core/tools";
import type { ChromeProps } from "../vn-core/types";
import "../vn-core/form.css";
import "./repair.css";

/** Workshop header: dark, the name in a mono tag, tabs with a bottom rule, a "track my booking" link always in reach; on phones a side sheet slides in from the edge. */
export function RepairHeader({ name, logoUrl, announcement, closedMessage, isOpen, hasProducts, hasBooking }: ChromeProps) {
  const { t, locale } = useI18n();
  const { open, setOpen } = useMenu();
  const scrolled = useScrolled(8);
  const reduce = useReducedMotion();
  const from = locale === "ar" ? "-100%" : "100%";
  const path = usePathname();
  const links = [
    { href: "/services", label: t("vn.rp.nav.catalog") },
    ...(hasProducts ? [{ href: "/products", label: t("vn.rp.nav.parts") }] : []),
    ...(hasBooking ? [{ href: "/book/manage", label: t("vn.rp.track") }] : []),
    { href: "/contact", label: t("vn.rp.nav.find") },
  ];
  const note = !isOpen ? (closedMessage || t("shop.closedNow")) : announcement;
  return (
    <>
      {note && <div className="rp-note"><span className="rp-dot" aria-hidden />{note}</div>}
      <header className="rp-header" data-scrolled={scrolled ? "true" : "false"}>
        <div className="rp-header-in">
          <Link href="/" className="rp-brand" aria-label={name}>
            {logoUrl ? /* eslint-disable-next-line @next/next/no-img-element */ <img src={logoUrl} alt="" className="rp-logo" /> : <span className="rp-tag" aria-hidden>{Array.from(name.trim()).slice(0, 2).join("")}</span>}
            <span className="rp-brand-name">{name}</span>
          </Link>
          <nav className="rp-nav" aria-label={t("shop.menu")}>{links.map((l) => <Link key={l.href} href={l.href} aria-current={path.startsWith(l.href) ? "page" : undefined}>{l.label}</Link>)}</nav>
          <div className="rp-tools">
            <LangLink className="rp-icon rp-lang" />
            {hasProducts && <CartLink className="rp-icon" badgeClass="rp-badge" />}
            <AccountLink className="rp-icon rp-account" />
            {hasBooking && <Link href="/book" className="vf-btn rp-book">{t("vn.rp.book")}</Link>}
            <button type="button" className="rp-icon rp-burger" aria-label={t("shop.menu")} aria-expanded={open} onClick={() => setOpen(true)}><Icon name="menu" className="h-6 w-6" /></button>
          </div>
        </div>
      </header>
      <AnimatePresence>
        {open && (
          <>
            <motion.div className="rp-scrim" onClick={() => setOpen(false)} initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={{ duration: reduce ? 0 : 0.2 }} />
            <motion.aside className="rp-sheet" role="dialog" aria-modal="true" aria-label={t("shop.menu")} initial={reduce ? false : { x: from }} animate={{ x: 0 }} exit={reduce ? { opacity: 0 } : { x: from }} transition={{ duration: reduce ? 0 : 0.28, ease: [0.22, 0.8, 0.24, 1] }}>
              <div className="rp-sheet-top"><span className="rp-sheet-title">{t("shop.menu")}</span><button type="button" className="rp-icon" aria-label={t("common.close")} onClick={() => setOpen(false)}><Icon name="close" className="h-6 w-6" /></button></div>
              <nav className="rp-sheet-links">
                {[{ href: "/", label: t("shop.home") }, ...links].map((l, i) => <Link key={l.href} href={l.href} onClick={() => setOpen(false)}><span className="rp-idx tnum">{String(i + 1).padStart(2, "0")}</span>{l.label}</Link>)}
              </nav>
              {hasBooking && <Link href="/book" className="vf-btn rp-sheet-book" onClick={() => setOpen(false)}>{t("vn.rp.book")}</Link>}
              <div className="rp-sheet-foot"><LangLink className="rp-sheet-small" /><AccountLink className="rp-sheet-small" withLabel />{hasProducts && <CartLink className="rp-sheet-small" badgeClass="rp-badge" />}</div>
            </motion.aside>
          </>
        )}
      </AnimatePresence>
    </>
  );
}
