"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { Icon } from "../icons";
import { useI18n } from "../i18n-provider";
import { useOpenCart, useOpenMe, useWishlist } from "@/lib/open-client";
import type { OpenCategory } from "@/lib/open-types";
import { OpenSearch } from "./search";

export function OpenLogo({ className = "", light = false }: { className?: string; light?: boolean }) {
  const { t } = useI18n();
  return (
    <Link href="/" className={`flex items-center gap-2.5 ${className}`} aria-label={t("open.brand")}>
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src="/brand/icon-192.png" alt="" width={34} height={34} className="h-[34px] w-[34px] rounded-[10px]" />
      <span className="flex items-baseline gap-1.5 leading-none">
        <span className={`o-display text-[1.45rem] ${light ? "text-white" : "text-[var(--o-ink)]"}`}>{t("brand.name")}</span>
        <span className={`rounded-full border px-2 py-[3px] text-[10px] font-extrabold uppercase tracking-[.14em] ${light ? "border-white/40 text-white" : "border-[var(--o-accent)] text-[var(--o-accent)]"}`}>Open</span>
      </span>
    </Link>
  );
}

const iconBtn = "relative grid h-11 w-11 place-items-center rounded-full transition hover:bg-[var(--o-soft)]";

/** The marketplace header: a thin utility strip, logo + wide search + account/wishlist/cart, and a category bar whose hover opens a mega menu. Phones get a drawer. */
export function OpenHeader({ tree, rootDomain }: { tree: OpenCategory[]; rootDomain: string }) {
  const { t, locale } = useI18n();
  const path = usePathname();
  const reduce = useReducedMotion();
  const cartCount = useOpenCart().reduce((n, l) => n + l.quantity, 0);
  const wishCount = useWishlist().length;
  const me = useOpenMe();
  const [drawer, setDrawer] = useState(false);
  const [hover, setHover] = useState<number | null>(null);
  const leaveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const closeBtn = useRef<HTMLButtonElement>(null);
  const name = (c: { nameAr: string; nameEn: string }) => (locale === "ar" ? c.nameAr : c.nameEn || c.nameAr);

  useEffect(() => { const id = setTimeout(() => { setDrawer(false); setHover(null); }, 0); return () => clearTimeout(id); }, [path]);
  useEffect(() => {
    document.body.style.overflow = drawer ? "hidden" : "";
    if (drawer) closeBtn.current?.focus();
    const esc = (e: KeyboardEvent) => { if (e.key === "Escape") { setDrawer(false); setHover(null); } };
    document.addEventListener("keydown", esc);
    return () => { document.body.style.overflow = ""; document.removeEventListener("keydown", esc); };
  }, [drawer]);

  const open = (i: number) => { if (leaveTimer.current) clearTimeout(leaveTimer.current); setHover(i); };
  const leave = () => { leaveTimer.current = setTimeout(() => setHover(null), 140); };
  const active = hover !== null ? tree[hover] : null;
  const showMega = !!active && !!active.children && active.children.length > 0;
  const other = locale === "ar" ? { l: "en", label: "English" } : { l: "ar", label: "العربية" };
  const dur = reduce ? 0 : 0.2;

  return (
    <header className="sticky top-0 z-50">
      <div className="hidden bg-[var(--o-ink)] text-[12px] text-white/75 md:block">
        <div className="o-container flex h-9 items-center justify-between">
          <p className="flex items-center gap-2"><Icon name="truck" className="h-3.5 w-3.5 text-[var(--o-gold)]" />{t("open.trust.cod")}</p>
          <nav className="flex items-center gap-6 font-medium" aria-label={t("open.footer.help")}>
            <Link href="/orders" className="transition hover:text-white">{t("open.trackOrder")}</Link>
            <a href={`https://${rootDomain}`} className="transition hover:text-white">{t("open.sellWithUs")}</a>
            <a href={`/api/lang?l=${other.l}`} className="transition hover:text-white">{other.label}</a>
          </nav>
        </div>
      </div>

      <div className="relative border-b border-[var(--o-line)] bg-[var(--o-paper)]/95 backdrop-blur-xl" onMouseLeave={leave}>
        <div className="o-container flex flex-wrap items-center gap-x-3 gap-y-2 py-2.5 md:flex-nowrap md:gap-6 md:py-3.5">
          <button type="button" onClick={() => setDrawer(true)} aria-label={t("open.allCategories")} aria-expanded={drawer} className={`${iconBtn} -ms-2 md:hidden`}><Icon name="menu" className="h-6 w-6" /></button>
          <OpenLogo className="me-auto md:me-0" />
          <OpenSearch className="order-last w-full md:order-none md:max-w-2xl md:flex-1" />
          <div className="flex items-center gap-0.5 md:ms-auto">
            <Link href={me ? "/account" : "/login"} className="hidden h-11 items-center gap-2 rounded-full px-3.5 text-sm font-semibold transition hover:bg-[var(--o-soft)] md:flex">
              <Icon name="users" className="h-5 w-5" />
              <span className="max-w-[8rem] truncate">{me ? me.name.split(" ")[0] : t("open.signIn")}</span>
            </Link>
            <Link href="/wishlist" aria-label={t("open.wishlist")} className={iconBtn}>
              <Icon name="heart" className="h-[22px] w-[22px]" />
              {wishCount > 0 && <span className="o-num absolute end-1 top-1 grid h-4 min-w-4 place-items-center rounded-full bg-[var(--o-ink)] px-1 text-[10px] font-extrabold text-white">{wishCount}</span>}
            </Link>
            <Link href="/cart" aria-label={`${t("nav.cart")} (${cartCount})`} className={iconBtn}>
              <Icon name="cart" className="h-[23px] w-[23px]" />
              {cartCount > 0 && <span className="o-num absolute end-0.5 top-0.5 grid h-[18px] min-w-[18px] place-items-center rounded-full bg-[var(--o-accent)] px-1 text-[10px] font-extrabold text-white">{cartCount}</span>}
            </Link>
          </div>
        </div>

        <nav className="hidden border-t border-[var(--o-line)] md:block" aria-label={t("open.allCategories")}>
          <div className="o-container flex items-center gap-1 overflow-x-auto o-scroll-hide">
            <button type="button" onMouseEnter={() => open(0)} onClick={() => setDrawer(true)} className="me-3 inline-flex shrink-0 items-center gap-2 py-3 text-sm font-bold">
              <Icon name="menu" className="h-4 w-4" />{t("open.allCategories")}
            </button>
            {tree.slice(0, 9).map((c, i) => (
              <Link key={c.slug} href={`/c/${c.slug}`} onMouseEnter={() => open(i)} onFocus={() => open(i)} aria-current={hover === i ? "page" : undefined} className="o-underline shrink-0 px-3 py-3 text-[13.5px] font-semibold text-[var(--o-ink-2)] transition hover:text-[var(--o-ink)]">
                {name(c)}
              </Link>
            ))}
            <Link href="/search?onSale=true&sort=discount" className="ms-auto shrink-0 px-3 py-3 text-[13.5px] font-bold text-[var(--o-accent)]">{t("open.deals")}</Link>
          </div>
        </nav>

        <AnimatePresence>
          {showMega && active && (
            <motion.div key="mega" className="o-mega hidden md:block" initial={reduce ? false : { opacity: 0, y: -6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} transition={{ duration: dur }} onMouseEnter={() => open(hover as number)} onMouseLeave={leave}>
              <div className="o-container grid grid-cols-4 gap-x-8 gap-y-1 py-7 lg:grid-cols-5">
                <div className="col-span-full mb-3 flex items-center justify-between border-b border-[var(--o-line)] pb-4">
                  <p className="o-display text-xl">{active.icon} {name(active)}</p>
                  <Link href={`/c/${active.slug}`} className="o-link">{t("open.seeAll")}</Link>
                </div>
                {(active.children ?? []).map((ch) => (
                  <Link key={ch.slug} href={`/c/${ch.slug}`} className="group flex items-center justify-between gap-2 rounded-lg px-2 py-2.5 text-sm font-medium transition hover:bg-[var(--o-soft)]">
                    <span className="truncate group-hover:text-[var(--o-accent)]">{name(ch)}</span>
                    <span className="o-num shrink-0 text-xs text-[var(--o-faint)]">{ch.n}</span>
                  </Link>
                ))}
              </div>
            </motion.div>
          )}
        </AnimatePresence>
      </div>

      <AnimatePresence>
        {drawer && (
          <>
            <motion.div key="back" className="o-sheet-back" onClick={() => setDrawer(false)} initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={{ duration: dur }} />
            <motion.aside key="drawer" role="dialog" aria-modal="true" aria-label={t("open.allCategories")} className="fixed inset-y-0 start-0 z-[71] flex w-[min(22rem,88vw)] flex-col bg-[var(--o-paper)] shadow-2xl"
              initial={reduce ? false : { x: locale === "ar" ? "100%" : "-100%" }} animate={{ x: 0 }} exit={{ x: locale === "ar" ? "100%" : "-100%" }} transition={reduce ? { duration: 0 } : { type: "spring", stiffness: 380, damping: 38 }}>
              <div className="flex items-center justify-between border-b border-[var(--o-line)] px-4 py-3">
                <OpenLogo />
                <button ref={closeBtn} type="button" onClick={() => setDrawer(false)} aria-label={t("common.close")} className={iconBtn}><Icon name="close" className="h-5 w-5" /></button>
              </div>
              <div className="flex-1 overflow-y-auto p-3">
                <Link href={me ? "/account" : "/login"} className="mb-2 flex items-center gap-3 rounded-2xl bg-[var(--o-soft)] p-3.5 text-sm font-bold"><Icon name="users" className="h-5 w-5 text-[var(--o-accent)]" />{me ? me.name : t("open.signInOrRegister")}</Link>
                <Link href="/search?onSale=true&sort=discount" className="flex items-center gap-3 rounded-2xl px-3.5 py-3 text-sm font-bold text-[var(--o-accent)]"><Icon name="tag" className="h-5 w-5" />{t("open.deals")}</Link>
                <p className="o-eyebrow px-3.5 pb-1 pt-4 !text-[var(--o-mute)]">{t("open.allCategories")}</p>
                {tree.map((c) => (
                  <details key={c.slug} className="group rounded-2xl">
                    <summary className="flex min-h-12 cursor-pointer list-none items-center justify-between rounded-2xl px-3.5 text-sm font-semibold hover:bg-[var(--o-soft)]">
                      <span>{c.icon} {name(c)}</span>
                      <Icon name="chevron" className="h-4 w-4 text-[var(--o-mute)] transition group-open:rotate-180" />
                    </summary>
                    <div className="ms-5 border-s border-[var(--o-line)] ps-2 pb-2">
                      <Link href={`/c/${c.slug}`} className="block rounded-xl px-3 py-2.5 text-sm font-bold text-[var(--o-accent)]">{t("open.seeAll")}</Link>
                      {(c.children ?? []).map((ch) => <Link key={ch.slug} href={`/c/${ch.slug}`} className="block rounded-xl px-3 py-2.5 text-sm hover:bg-[var(--o-soft)]">{name(ch)}</Link>)}
                    </div>
                  </details>
                ))}
                <div className="mt-3 grid gap-0.5 border-t border-[var(--o-line)] pt-3 text-sm font-medium">
                  <Link href="/shops" className="rounded-xl px-3.5 py-3 hover:bg-[var(--o-soft)]">{t("open.allShops")}</Link>
                  <Link href="/orders" className="rounded-xl px-3.5 py-3 hover:bg-[var(--o-soft)]">{t("open.trackOrder")}</Link>
                  <a href={`https://${rootDomain}`} className="rounded-xl px-3.5 py-3 hover:bg-[var(--o-soft)]">{t("open.sellWithUs")}</a>
                  <a href={`/api/lang?l=${other.l}`} className="rounded-xl px-3.5 py-3 hover:bg-[var(--o-soft)]">{other.label}</a>
                </div>
              </div>
            </motion.aside>
          </>
        )}
      </AnimatePresence>
    </header>
  );
}
