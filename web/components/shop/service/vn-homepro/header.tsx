"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { AccountLink, CartLink, LangLink, useMenu, useScrolled } from "../vn-core/tools";
import type { ChromeProps } from "../vn-core/types";
import "../vn-core/form.css";
import "./homepro.css";

/** Home-services header: a utility strip with the phone number first, the name and links below, and (on phones) a bottom bar with Call and Request. */
export function HomeproHeader({ name, logoUrl, announcement, closedMessage, isOpen, hasProducts, hasBooking, phone }: ChromeProps) {
  const { t } = useI18n();
  const { open, setOpen } = useMenu();
  const scrolled = useScrolled(8);
  const path = usePathname();
  const links = [
    { href: "/services", label: t("svh.services") },
    ...(hasProducts ? [{ href: "/products", label: t("svh.products") }] : []),
    ...(hasBooking ? [{ href: "/book/manage", label: t("vn.nav.myBooking") }] : []),
    { href: "/contact", label: t("shop.contact") },
  ];
  const note = !isOpen ? (closedMessage || t("shop.closedNow")) : announcement;
  return (
    <>
      <div className="hp-util">
        <div className="hp-util-in">
          <span className="hp-util-note">{note || t("vn.hp.utility")}</span>
          <span className="hp-util-r">{phone && <a href={`tel:${phone}`} dir="ltr" className="hp-util-tel"><Icon name="phone" className="h-4 w-4" />{phone}</a>}<LangLink className="hp-util-lang" /></span>
        </div>
      </div>
      <header className="hp-header" data-scrolled={scrolled ? "true" : "false"}>
        <div className="hp-header-in">
          <Link href="/" className="hp-brand" aria-label={name}>
            {logoUrl ? /* eslint-disable-next-line @next/next/no-img-element */ <img src={logoUrl} alt="" className="hp-logo" /> : <span className="hp-mark" aria-hidden>{Array.from(name.trim())[0] ?? ""}</span>}
            <span className="hp-brand-name">{name}</span>
          </Link>
          <nav className="hp-nav" aria-label={t("shop.menu")}>{links.map((l) => <Link key={l.href} href={l.href} aria-current={path.startsWith(l.href) ? "page" : undefined}>{l.label}</Link>)}</nav>
          <div className="hp-tools">
            {hasProducts && <CartLink className="hp-icon" badgeClass="hp-badge" />}
            <AccountLink className="hp-icon hp-account" />
            {hasBooking && <Link href="/book" className="vf-btn hp-req">{t("vn.hp.request")}</Link>}
            <button type="button" className="hp-icon hp-burger" aria-label={t("shop.menu")} aria-expanded={open} onClick={() => setOpen(!open)}><Icon name={open ? "close" : "menu"} className="h-6 w-6" /></button>
          </div>
        </div>
        {open && (
          <div className="hp-panel" role="dialog" aria-label={t("shop.menu")}>
            {[{ href: "/", label: t("shop.home") }, ...links].map((l) => <Link key={l.href} href={l.href} onClick={() => setOpen(false)}>{l.label}<Icon name="arrow" className="h-4 w-4 flip-rtl" /></Link>)}
          </div>
        )}
      </header>
      {hasBooking && (
        <div className="hp-dock">
          {phone && <a href={`tel:${phone}`} className="vf-btn vf-btn-ghost"><Icon name="phone" className="h-5 w-5" />{t("vn.hp.call")}</a>}
          <Link href="/book" className="vf-btn"><Icon name="calendar" className="h-5 w-5" />{t("vn.hp.request")}</Link>
        </div>
      )}
    </>
  );
}
