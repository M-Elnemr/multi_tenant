"use client";

import Link from "next/link";
import { usePathname, useSearchParams } from "next/navigation";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";
import { AccountLink, CartLink, LangLink } from "../vn-core/tools";
import type { ChromeProps } from "../vn-core/types";
import "../vn-core/form.css";
import "./multi.css";

/** Directory header: name and a real search box on top (a plain GET form to /services?q=), a scrolling strip of categories below. No hidden menu: everything is on the strip. */
export function MultiHeader({ name, logoUrl, announcement, closedMessage, isOpen, hasProducts, hasBooking, categories }: ChromeProps) {
  const { t } = useI18n();
  const path = usePathname();
  const sp = useSearchParams();
  const note = !isOpen ? (closedMessage || t("shop.closedNow")) : announcement;
  const cat = sp.get("cat");
  const cats = categories ?? [];
  return (
    <>
      {note && <div className="mu-note">{note}</div>}
      <header className="mu-header">
        <div className="mu-top">
          <Link href="/" className="mu-brand" aria-label={name}>
            {logoUrl ? /* eslint-disable-next-line @next/next/no-img-element */ <img src={logoUrl} alt="" className="mu-logo" /> : <span className="mu-mark" aria-hidden>{Array.from(name.trim())[0] ?? ""}</span>}
            <span className="mu-brand-name">{name}</span>
          </Link>
          <form className="mu-search" action="/services" role="search">
            <Icon name="search" className="h-5 w-5" />
            <input type="search" name="q" defaultValue={path.startsWith("/services") ? sp.get("q") ?? "" : ""} placeholder={t("vn.mu.search")} aria-label={t("vn.mu.search")} />
            <button className="mu-search-go">{t("vn.mu.go")}</button>
          </form>
          <div className="mu-tools">
            <LangLink className="mu-icon mu-lang" />
            {hasProducts && <CartLink className="mu-icon" badgeClass="mu-badge" />}
            <AccountLink className="mu-icon" />
          </div>
        </div>
        <nav className="mu-strip" aria-label={t("vn.categories")}>
          <Link href="/services" aria-current={path === "/services" && !cat ? "page" : undefined}>{t("vn.mu.all")}</Link>
          {cats.map((c) => <Link key={c} href={`/services?cat=${encodeURIComponent(c)}`} aria-current={cat === c ? "page" : undefined}>{c}</Link>)}
          <span className="mu-strip-sep" aria-hidden />
          {hasBooking && <Link href="/book/manage" aria-current={path.startsWith("/book/manage") ? "page" : undefined}>{t("vn.nav.myBooking")}</Link>}
          {hasProducts && <Link href="/products">{t("svh.products")}</Link>}
          <Link href="/contact" aria-current={path.startsWith("/contact") ? "page" : undefined}>{t("shop.contact")}</Link>
        </nav>
      </header>
    </>
  );
}
