"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { Icon } from "../icons";
import { useI18n } from "../i18n-provider";
import { useOpenCart, useOpenMe, useWishlist } from "@/lib/open-client";
import type { OpenCategory } from "@/lib/open-types";
import { OpenSearch } from "./search";

export function OpenLogo({ className = "" }: { className?: string }) {
  const { t } = useI18n();
  return (
    <Link href="/" className={`flex items-center gap-2 ${className}`} aria-label={t("open.brand")}>
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src="/brand/icon-192.png" alt="" width={36} height={36} className="h-9 w-9 rounded-xl" />
      <span className="flex items-baseline gap-1.5 leading-none">
        <span className="text-xl font-extrabold tracking-tight text-white">{t("brand.name")}</span>
        <span className="rounded-md bg-[var(--o-gold)] px-1.5 py-0.5 text-[11px] font-black uppercase tracking-wider text-[#3a2a00]">Open</span>
      </span>
    </Link>
  );
}

/** The marketplace header: announcement strip, logo + big search + account/wishlist/cart, and a category bar with a mega menu. */
export function OpenHeader({ tree, rootDomain }: { tree: OpenCategory[]; rootDomain: string }) {
  const { t, locale } = useI18n();
  const path = usePathname();
  const cartCount = useOpenCart().reduce((n, l) => n + l.quantity, 0);
  const wishCount = useWishlist().length;
  const me = useOpenMe();
  const [drawer, setDrawer] = useState(false);
  const [hover, setHover] = useState<number | null>(null);
  const leaveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const name = (c: { nameAr: string; nameEn: string }) => (locale === "ar" ? c.nameAr : c.nameEn || c.nameAr);

  useEffect(() => { const id = setTimeout(() => { setDrawer(false); setHover(null); }, 0); return () => clearTimeout(id); }, [path]);
  useEffect(() => {
    document.body.style.overflow = drawer ? "hidden" : "";
    return () => { document.body.style.overflow = ""; };
  }, [drawer]);

  const open = (i: number) => { if (leaveTimer.current) clearTimeout(leaveTimer.current); setHover(i); };
  const leave = () => { leaveTimer.current = setTimeout(() => setHover(null), 140); };
  const active = hover !== null ? tree[hover] : null;

  return (
    <header className="sticky top-0 z-50">
      <div className="hidden bg-[#070d24] text-[12px] text-white/70 md:block">
        <div className="o-container flex h-8 items-center justify-between">
          <p className="flex items-center gap-2"><Icon name="shield" className="h-3.5 w-3.5 text-[var(--o-gold)]" />{t("open.strip")}</p>
          <nav className="flex items-center gap-5 font-semibold">
            <Link href="/orders" className="transition hover:text-white">{t("open.trackOrder")}</Link>
            <a href={`https://${rootDomain}`} className="transition hover:text-white">{t("open.sellWithUs")}</a>
            <a href={`/api/lang?l=${locale === "ar" ? "en" : "ar"}`} className="transition hover:text-white">{locale === "ar" ? "English" : "العربية"}</a>
          </nav>
        </div>
      </div>

      <div className="relative bg-[var(--o-navy)] text-white shadow-[0_10px_30px_-18px_rgb(13_21_53/.9)]" onMouseLeave={leave}>
        <div className="o-container flex flex-wrap items-center gap-x-4 gap-y-2.5 py-2.5 md:flex-nowrap md:gap-5 md:py-3.5">
          <button type="button" onClick={() => setDrawer(true)} aria-label={t("open.allCategories")} className="grid h-11 w-11 place-items-center rounded-xl transition hover:bg-white/10 md:hidden"><Icon name="menu" className="h-6 w-6" /></button>
          <OpenLogo className="me-auto md:me-0" />
          <OpenSearch className="order-last w-full md:order-none md:flex-1" />
          <div className="flex items-center gap-1">
            <Link href={me ? "/account" : "/login"} className="hidden h-11 items-center gap-2 rounded-xl px-3 text-sm font-bold transition hover:bg-white/10 md:flex">
              <Icon name="users" className="h-5 w-5" />
              <span className="max-w-[8rem] truncate">{me ? me.name.split(" ")[0] : t("open.signIn")}</span>
            </Link>
            <Link href="/wishlist" aria-label={t("open.wishlist")} className="relative grid h-11 w-11 place-items-center rounded-xl transition hover:bg-white/10">
              <Icon name="heart" className="h-[22px] w-[22px]" />
              {wishCount > 0 && <span className="absolute end-1 top-1 grid h-4 min-w-4 place-items-center rounded-full bg-[var(--o-gold)] px-1 text-[10px] font-black text-[#3a2a00]">{wishCount}</span>}
            </Link>
            <Link href="/cart" aria-label={t("nav.cart")} className="relative flex h-11 items-center gap-2 rounded-xl px-2.5 transition hover:bg-white/10">
              <Icon name="cart" className="h-[24px] w-[24px]" />
              <span className="hidden text-sm font-bold md:inline">{t("nav.cart")}</span>
              {cartCount > 0 && <span className="absolute end-0.5 top-0.5 grid h-5 min-w-5 place-items-center rounded-full bg-[var(--o-accent)] px-1 text-[11px] font-black text-white md:end-auto md:start-6">{cartCount}</span>}
            </Link>
          </div>
        </div>

        <nav className="hidden border-t border-white/10 md:block" aria-label={t("open.allCategories")}>
          <div className="o-container flex items-center gap-1 overflow-x-auto py-1 o-scroll-hide">
            <button type="button" onMouseEnter={() => open(0)} onClick={() => setDrawer(true)} className="me-2 inline-flex shrink-0 items-center gap-2 rounded-lg bg-white/10 px-3.5 py-2 text-sm font-extrabold transition hover:bg-white/20">
              <Icon name="menu" className="h-4 w-4" />{t("open.allCategories")}
            </button>
            {tree.slice(0, 9).map((c, i) => (
              <Link key={c.slug} href={`/c/${c.slug}`} onMouseEnter={() => open(i)} className={`shrink-0 rounded-lg px-3 py-2 text-[13.5px] font-bold transition ${hover === i ? "bg-white text-[var(--o-navy)]" : "text-white/85 hover:text-white"}`}>
                {name(c)}
              </Link>
            ))}
            <Link href="/search?onSale=true&sort=discount" className="ms-auto shrink-0 rounded-lg px-3 py-2 text-[13.5px] font-extrabold text-[var(--o-gold)]">🔥 {t("open.deals")}</Link>
          </div>
        </nav>

        {active && active.children && active.children.length > 0 && (
          <div className="o-mega hidden text-[var(--o-ink)] md:block" onMouseEnter={() => open(hover as number)} onMouseLeave={leave}>
            <div className="o-container grid grid-cols-4 gap-x-8 gap-y-5 py-6 lg:grid-cols-5">
              <div className="col-span-full flex items-center justify-between border-b border-[var(--o-line)] pb-3">
                <p className="text-lg font-extrabold">{active.icon} {name(active)}</p>
                <Link href={`/c/${active.slug}`} className="o-link">{t("open.seeAll")} →</Link>
              </div>
              {active.children.map((ch) => (
                <Link key={ch.slug} href={`/c/${ch.slug}`} className="group flex items-center justify-between gap-2 rounded-xl px-3 py-2.5 text-sm font-semibold transition hover:bg-[var(--o-soft)]">
                  <span className="truncate group-hover:text-[var(--o-accent)]">{name(ch)}</span>
                  <span className="shrink-0 text-xs text-[var(--o-mute)]">{ch.n}</span>
                </Link>
              ))}
            </div>
          </div>
        )}
      </div>

      {drawer && (
        <>
          <div className="o-sheet-back" onClick={() => setDrawer(false)} />
          <aside className="fixed inset-y-0 start-0 z-[71] flex w-[min(22rem,88vw)] flex-col bg-white shadow-2xl o-pop" aria-label={t("open.allCategories")}>
            <div className="flex items-center justify-between bg-[var(--o-navy)] px-4 py-3.5 text-white">
              <OpenLogo />
              <button type="button" onClick={() => setDrawer(false)} aria-label={t("common.close")} className="grid h-10 w-10 place-items-center rounded-xl hover:bg-white/10"><Icon name="close" className="h-5 w-5" /></button>
            </div>
            <div className="flex-1 overflow-y-auto p-3">
              <Link href={me ? "/account" : "/login"} className="mb-2 flex items-center gap-3 rounded-2xl bg-[var(--o-soft)] p-3.5 text-sm font-extrabold"><Icon name="users" className="h-5 w-5 text-[var(--o-accent)]" />{me ? me.name : t("open.signInOrRegister")}</Link>
              <Link href="/search?onSale=true&sort=discount" className="mb-1 flex items-center gap-3 rounded-2xl px-3.5 py-3 text-sm font-extrabold text-[var(--o-accent)]">🔥 {t("open.deals")}</Link>
              <p className="px-3.5 pb-1 pt-3 text-xs font-extrabold uppercase tracking-wider text-[var(--o-mute)]">{t("open.allCategories")}</p>
              {tree.map((c) => (
                <details key={c.slug} className="group rounded-2xl">
                  <summary className="flex cursor-pointer list-none items-center justify-between rounded-2xl px-3.5 py-3 text-sm font-bold hover:bg-[var(--o-soft)]">
                    <span>{c.icon} {name(c)}</span>
                    <Icon name="arrow" className="h-4 w-4 rotate-90 text-[var(--o-mute)] transition group-open:-rotate-90 rtl:-rotate-90 rtl:group-open:rotate-90" />
                  </summary>
                  <div className="ms-4 border-s border-[var(--o-line)] ps-2 pb-2">
                    <Link href={`/c/${c.slug}`} className="block rounded-xl px-3 py-2 text-sm font-extrabold text-[var(--o-accent)]">{t("open.seeAll")}</Link>
                    {(c.children ?? []).map((ch) => <Link key={ch.slug} href={`/c/${ch.slug}`} className="block rounded-xl px-3 py-2 text-sm font-medium hover:bg-[var(--o-soft)]">{name(ch)}</Link>)}
                  </div>
                </details>
              ))}
              <div className="mt-3 grid gap-1 border-t border-[var(--o-line)] pt-3 text-sm font-semibold">
                <Link href="/shops" className="rounded-xl px-3.5 py-2.5 hover:bg-[var(--o-soft)]">{t("open.allShops")}</Link>
                <Link href="/orders" className="rounded-xl px-3.5 py-2.5 hover:bg-[var(--o-soft)]">{t("open.trackOrder")}</Link>
                <a href={`https://${rootDomain}`} className="rounded-xl px-3.5 py-2.5 hover:bg-[var(--o-soft)]">{t("open.sellWithUs")}</a>
                <a href={`/api/lang?l=${locale === "ar" ? "en" : "ar"}`} className="rounded-xl px-3.5 py-2.5 hover:bg-[var(--o-soft)]">{locale === "ar" ? "English" : "العربية"}</a>
              </div>
            </div>
          </aside>
        </>
      )}
    </header>
  );
}
