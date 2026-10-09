"use client";

import Link from "next/link";
import { useState } from "react";
import { useCart } from "@/lib/cart";
import { useMe } from "../hooks";
import { Icon } from "../icons";
import { useI18n } from "../i18n-provider";
import { SearchBox } from "./search-box";
import { buildTree, type CategoryNode, type ShopCategory } from "./types";

/** Men / Women / Kids shortcuts: the standard "for whom" filter applied to the whole shop. */
const FOR_LINKS = [{ key: "MEN", value: "MEN" }, { key: "WOMEN", value: "WOMEN" }, { key: "KIDS", value: "BOYS,GIRLS,BABY" }];

function Logo({ name, logoUrl, size = "h-10 w-10" }: { name: string; logoUrl: string | null; size?: string }) {
  return (
    <span className="flex items-center gap-3">
      {logoUrl ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img src={logoUrl} alt={name} className={`${size} rounded-2xl bg-white object-contain shadow-sm ring-1 ring-black/5`} />
      ) : (
        <span className={`${size} grid place-items-center rounded-2xl bg-brand-gradient text-lg font-extrabold text-white shadow-sm`}>{name.trim().charAt(0).toUpperCase()}</span>
      )}
      <span className="text-lg font-extrabold leading-tight tracking-tight sm:text-xl">{name}</span>
    </span>
  );
}

function CartButton() {
  const { t } = useI18n();
  const count = useCart().reduce((n, l) => n + l.quantity, 0);
  return (
    <Link href="/cart" aria-label={t("nav.cart")} className="relative grid h-11 w-11 place-items-center rounded-full transition hover:bg-[var(--s-soft)]">
      <Icon name="cart" className="h-[22px] w-[22px]" />
      {count > 0 && <span key={count} className="absolute -end-0.5 -top-0.5 grid h-5 min-w-5 animate-pop place-items-center rounded-full px-1 text-[11px] font-extrabold text-white" style={{ background: "var(--brand)" }}>{count}</span>}
    </Link>
  );
}

function AccountButton() {
  const { t } = useI18n();
  const { me, ready } = useMe();
  if (!ready) return <span className="h-11 w-11" />;
  const staff = !!me && me.roles.some((r) => r !== "CUSTOMER");
  return (
    <div className="flex items-center gap-1">
      {staff && <Link href="/dashboard" className="hidden rounded-full bg-[var(--s-ink)] px-4 py-2 text-sm font-bold text-white sm:inline-block">{t("nav.dashboard")}</Link>}
      <Link href={me ? "/account" : "/login"} aria-label={me ? t("nav.account") : t("nav.login")} className="flex h-11 items-center gap-2 rounded-full px-3 text-sm font-bold transition hover:bg-[var(--s-soft)]">
        <svg viewBox="0 0 24 24" className="h-[22px] w-[22px]" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round"><circle cx="12" cy="8" r="4" /><path d="M4 21a8 8 0 0 1 16 0" /></svg>
        <span className="hidden lg:inline">{me ? me.firstName || t("nav.account") : t("nav.login")}</span>
      </Link>
    </div>
  );
}

function MegaItem({ node }: { node: CategoryNode }) {
  return (
    <div className="group relative">
      <Link href={`/c/${node.slug}`} className="inline-flex items-center gap-1 whitespace-nowrap rounded-full px-4 py-2 text-sm font-bold transition hover:bg-[var(--s-soft)] hover:text-[var(--brand)]">
        {node.name}{node.children.length > 0 && <svg viewBox="0 0 12 12" className="h-2.5 w-2.5 opacity-50"><path d="M2 4l4 4 4-4" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" /></svg>}
      </Link>
      {node.children.length > 0 && (
        <div className="invisible absolute start-0 top-full z-40 w-[min(46rem,92vw)] pt-2 opacity-0 transition duration-150 group-hover:visible group-hover:opacity-100">
          <div className="s-dropdown grid grid-cols-2 gap-x-6 gap-y-4 rounded-3xl border border-[var(--s-line)] bg-white p-6 shadow-[var(--s-shadow-lg)] sm:grid-cols-3">
            {node.children.map((c) => (
              <div key={c.id}>
                <Link href={`/c/${c.slug}`} className="text-sm font-extrabold hover:text-[var(--brand)]">{c.name}</Link>
                <ul className="mt-1.5 space-y-1">
                  {c.children.slice(0, 6).map((g) => <li key={g.id}><Link href={`/c/${g.slug}`} className="text-sm text-[var(--s-mute)] transition hover:text-[var(--brand)]">{g.name}</Link></li>)}
                </ul>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

function DrawerNode({ node, depth, close }: { node: CategoryNode; depth: number; close: () => void }) {
  const link = <Link href={`/c/${node.slug}`} onClick={close} className="flex-1 py-2.5 text-[15px] font-bold">{node.name} <span className="text-xs font-normal text-[var(--s-mute)]">({node.productCount})</span></Link>;
  if (node.children.length === 0) return <div style={{ paddingInlineStart: depth * 14 }}>{link}</div>;
  return (
    <details style={{ paddingInlineStart: depth * 14 }}>
      <summary className="flex cursor-pointer items-center">{link}<svg viewBox="0 0 12 12" className="h-3 w-3 opacity-50"><path d="M2 4l4 4 4-4" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" /></svg></summary>
      {node.children.map((c) => <DrawerNode key={c.id} node={c} depth={depth + 1} close={close} />)}
    </details>
  );
}

export function ShopHeader({ name, logoUrl, announcement, closedMessage, isOpen, categories }: { name: string; logoUrl: string | null; announcement?: string; closedMessage?: string; isOpen: boolean; categories: ShopCategory[] }) {
  const { t, locale } = useI18n();
  const [drawer, setDrawer] = useState(false);
  const tree = buildTree(categories);
  return (
    <>
      {!isOpen && <div className="bg-[var(--s-ink)] px-4 py-2 text-center text-sm font-semibold text-white">🌙 {closedMessage || t("shop.closedNow")}</div>}
      {announcement && isOpen && <div className="bg-brand-gradient px-4 py-2 text-center text-sm font-bold text-white">{announcement}</div>}
      <header className="sticky top-0 z-40 border-b border-[var(--s-line)] bg-[var(--s-bg)]/90 backdrop-blur-xl">
        <div className="s-container flex items-center gap-3 py-3">
          <button onClick={() => setDrawer(true)} aria-label={t("shop.menu")} className="grid h-11 w-11 place-items-center rounded-full transition hover:bg-[var(--s-soft)] lg:hidden"><Icon name="menu" className="h-6 w-6" /></button>
          <Link href="/" className="min-w-0 shrink-0"><Logo name={name} logoUrl={logoUrl} /></Link>
          <SearchBox className="mx-2 hidden flex-1 md:block lg:mx-6" />
          <div className="ms-auto flex items-center gap-0.5">
            <a href={`/api/lang?l=${locale === "ar" ? "en" : "ar"}`} className="hidden h-11 items-center rounded-full px-3 text-sm font-bold text-[var(--s-mute)] transition hover:bg-[var(--s-soft)] sm:flex">{locale === "ar" ? "EN" : "عربي"}</a>
            <AccountButton />
            <CartButton />
          </div>
        </div>
        <div className="s-container pb-3 md:hidden"><SearchBox /></div>
        <nav className="hidden border-t border-[var(--s-line)] lg:block">
          <div className="s-container flex items-center gap-1 py-1.5">
            <Link href="/products" className="rounded-full px-4 py-2 text-sm font-extrabold" style={{ color: "var(--brand)" }}>{t("shop.allProducts")}</Link>
            {FOR_LINKS.map((f) => <Link key={f.key} href={`/products?audience=${f.value}`} className="rounded-full bg-[var(--s-soft)] px-3.5 py-1.5 text-sm font-extrabold transition hover:text-[var(--brand)]">{t(`audience.${f.key}`)}</Link>)}
            {tree.slice(0, 6).map((n) => <MegaItem key={n.id} node={n} />)}
            <span className="ms-auto flex items-center gap-1">
              <Link href="/products?onSale=true" className="rounded-full px-4 py-2 text-sm font-extrabold text-[#e5484d] transition hover:bg-red-50">🔥 {t("shop.offers")}</Link>
              <Link href="/branches" className="rounded-full px-4 py-2 text-sm font-bold transition hover:bg-[var(--s-soft)]">{t("shop.branches")}</Link>
              <Link href="/contact" className="rounded-full px-4 py-2 text-sm font-bold transition hover:bg-[var(--s-soft)]">{t("shop.contact")}</Link>
            </span>
          </div>
        </nav>
      </header>
      {drawer && (
        <div className="fixed inset-0 z-50 lg:hidden" role="dialog" aria-modal>
          <div className="absolute inset-0 animate-fade-in bg-black/40" onClick={() => setDrawer(false)} />
          <aside className="absolute inset-y-0 start-0 flex w-[min(22rem,88vw)] animate-slide-in flex-col bg-[var(--s-bg)] shadow-2xl">
            <div className="flex items-center justify-between border-b border-[var(--s-line)] p-4"><Logo name={name} logoUrl={logoUrl} size="h-9 w-9" /><button onClick={() => setDrawer(false)} aria-label={t("common.close")} className="grid h-10 w-10 place-items-center rounded-full hover:bg-[var(--s-soft)]"><Icon name="close" /></button></div>
            <div className="flex-1 space-y-1 overflow-y-auto p-4">
              <Link href="/products" onClick={() => setDrawer(false)} className="block py-2.5 text-[15px] font-extrabold" style={{ color: "var(--brand)" }}>{t("shop.allProducts")}</Link>
              <Link href="/products?onSale=true" onClick={() => setDrawer(false)} className="block py-2.5 text-[15px] font-extrabold text-[#e5484d]">🔥 {t("shop.offers")}</Link>
              <div className="flex gap-2 py-1.5">{FOR_LINKS.map((f) => <Link key={f.key} href={`/products?audience=${f.value}`} onClick={() => setDrawer(false)} className="s-chip flex-1 justify-center">{t(`audience.${f.key}`)}</Link>)}</div>
              <div className="my-2 border-t border-[var(--s-line)]" />
              {tree.map((n) => <DrawerNode key={n.id} node={n} depth={0} close={() => setDrawer(false)} />)}
              <div className="my-2 border-t border-[var(--s-line)]" />
              <Link href="/branches" onClick={() => setDrawer(false)} className="block py-2.5 text-[15px] font-bold">{t("shop.branches")}</Link>
              <Link href="/contact" onClick={() => setDrawer(false)} className="block py-2.5 text-[15px] font-bold">{t("shop.contact")}</Link>
              <Link href="/track" onClick={() => setDrawer(false)} className="block py-2.5 text-[15px] font-bold">{t("shop.trackOrder")}</Link>
              <a href={`/api/lang?l=${locale === "ar" ? "en" : "ar"}`} className="block py-2.5 text-[15px] font-bold text-[var(--s-mute)]">{locale === "ar" ? "English" : "العربية"}</a>
            </div>
          </aside>
        </div>
      )}
    </>
  );
}
