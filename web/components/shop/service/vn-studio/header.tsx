"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { AccountLink, CartLink, LangLink, useMenu } from "../vn-core/tools";
import type { ChromeProps } from "../vn-core/types";
import "../vn-core/form.css";
import "./studio.css";

/** Studio header: a plain wordmark, one lime Book button, and on phones a bottom sheet that slides up (thumb reach). */
export function StudioHeader({ name, logoUrl, announcement, closedMessage, isOpen, hasProducts, hasBooking }: ChromeProps) {
  const { t } = useI18n();
  const { open, setOpen } = useMenu();
  const reduce = useReducedMotion();
  const path = usePathname();
  const links = [
    { href: "/services", label: t("vn.st.nav.sessions") },
    ...(hasProducts ? [{ href: "/products", label: t("vn.st.nav.shop") }] : []),
    ...(hasBooking ? [{ href: "/book/manage", label: t("vn.nav.myBooking") }] : []),
    { href: "/contact", label: t("vn.st.nav.contact") },
  ];
  const note = !isOpen ? (closedMessage || t("shop.closedNow")) : announcement;
  return (
    <>
      {note && <div className="st-note">{note}</div>}
      <header className="st-header">
        <div className="st-header-in">
          <Link href="/" className="st-brand" aria-label={name}>
            {logoUrl && /* eslint-disable-next-line @next/next/no-img-element */ <img src={logoUrl} alt="" className="st-logo" />}
            <span className="st-brand-name">{name}</span>
          </Link>
          <nav className="st-nav" aria-label={t("shop.menu")}>{links.map((l) => <Link key={l.href} href={l.href} aria-current={path.startsWith(l.href) ? "page" : undefined}>{l.label}</Link>)}</nav>
          <div className="st-tools">
            <LangLink className="st-icon st-lang" />
            {hasProducts && <CartLink className="st-icon" badgeClass="st-badge" />}
            <AccountLink className="st-icon st-account" />
            {hasBooking && <Link href="/book" className="vf-btn st-book">{t("vn.st.book")}</Link>}
            <button type="button" className="st-icon st-burger" aria-label={t("shop.menu")} aria-expanded={open} onClick={() => setOpen(true)}><Icon name="menu" className="h-6 w-6" /></button>
          </div>
        </div>
      </header>
      <AnimatePresence>
        {open && (
          <>
            <motion.div className="st-scrim" onClick={() => setOpen(false)} initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} />
            <motion.div className="st-sheet" role="dialog" aria-modal="true" aria-label={t("shop.menu")} initial={reduce ? false : { y: "100%" }} animate={{ y: 0 }} exit={reduce ? { opacity: 0 } : { y: "100%" }} transition={{ duration: reduce ? 0 : 0.32, ease: [0.22, 0.8, 0.24, 1] }}>
              <div className="st-sheet-top"><span className="st-grip" aria-hidden /><button type="button" className="st-icon" aria-label={t("common.close")} onClick={() => setOpen(false)}><Icon name="close" className="h-6 w-6" /></button></div>
              <nav className="st-sheet-links">{[{ href: "/", label: t("shop.home") }, ...links].map((l) => <Link key={l.href} href={l.href} onClick={() => setOpen(false)}>{l.label}</Link>)}</nav>
              {hasBooking && <Link href="/book" className="vf-btn st-sheet-book" onClick={() => setOpen(false)}>{t("vn.st.book")}</Link>}
              <div className="st-sheet-foot"><LangLink className="st-sheet-small" /><AccountLink className="st-sheet-small" withLabel />{hasProducts && <CartLink className="st-sheet-small" badgeClass="st-badge" />}</div>
            </motion.div>
          </>
        )}
      </AnimatePresence>
    </>
  );
}
