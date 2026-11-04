"use client";

import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState, useSyncExternalStore } from "react";
import { motion, useReducedMotion } from "motion/react";
import { useApi, useMe } from "../hooks";
import { useI18n } from "../i18n-provider";
import { LogoutButton } from "../logout-button";
import { PoweredBy } from "../brand";
import { Icon, hasIcon } from "../icons";
import { Avatar, Loading } from "../ui";
import { SwRegister, useOnline } from "../sw";
import { fileUrl } from "@/lib/media";
import { SubscriptionBar, useBillingStatus } from "./subscription-bar";
import { NavTitleContext } from "./section";
import { matches as textMatches, words } from "@/lib/pos/search";

/**
 * `screen` is the access screen (hidden / read / write per employee) that opens the item; `perm` is for items outside that list (staff); `write` also needs write access to
 * `screen`. `group` is an i18n key (navgrp.*) for the section heading the item sits under; consecutive items with the same group share one heading. An `href` may carry
 * `?tab=` to open one part of a screen directly (the menu stays flat: no tabs inside screens). `advanced` items live in the folded «Advanced» section at the end, for the
 * screens that need experience; `quick` items fill the phone's bottom bar.
 */
export type NavItem = { href: string; label: string; perm?: string | string[]; screen?: string | string[]; write?: boolean; icon?: string; group?: string; advanced?: boolean; quick?: boolean };

const pathOf = (n: NavItem) => n.href.split("?")[0];
const tabOf = (n: NavItem) => new URLSearchParams(n.href.split("?")[1] ?? "").get("tab");

function NavIcon({ icon, className }: { icon?: string; className?: string }) {
  return hasIcon(icon) ? <Icon name={icon} className={className ?? "h-[19px] w-[19px]"} /> : <span aria-hidden>{icon ?? "•"}</span>;
}

/** The clinic's / shop's own logo (or a monogram of its name) as a rounded tile. */
function TenantMark({ className = "h-10 w-10" }: { className?: string }) {
  const b = useApi<{ logoFileId?: string }>("tenant/branding");
  /* eslint-disable-next-line @next/next/no-img-element */
  return <img src={b.data?.logoFileId ? fileUrl(b.data.logoFileId, "thumb") : "/api/monogram"} alt="" className={`${className} shrink-0 rounded-xl object-cover shadow-sm ring-1 ring-black/5`} />;
}

const COLLAPSE_KEY = "dashSidebarMini";
const ADVANCED_KEY = "dashNavAdvanced";
const listeners = new Set<() => void>();
const readFlag = (key: string) => { try { return localStorage.getItem(key) === "1"; } catch { return false; } };   // storage can be blocked: the default is used
const setFlag = (key: string, on: boolean) => { try { localStorage.setItem(key, on ? "1" : "0"); } catch { /* ignore */ } listeners.forEach((l) => l()); };
const readCollapsed = () => readFlag(COLLAPSE_KEY);
const readAdvanced = () => readFlag(ADVANCED_KEY);
const subscribe = (cb: () => void) => { listeners.add(cb); return () => { listeners.delete(cb); }; };

/** Sidebar dashboard used by shop owners, doctors and staff. Items are hidden without the permission (the backend enforces it regardless). */
export function DashboardShell(props: { nav: NavItem[]; children: React.ReactNode }) {
  // the menu reads `?tab=` to light the right item, which needs a suspense boundary in the app router
  return <Suspense fallback={<div className="p-6"><Loading /></div>}><Shell {...props} /></Suspense>;
}

function Shell({ nav, children }: { nav: NavItem[]; children: React.ReactNode }) {
  const { t, siteName, locale, whiteLabel } = useI18n();
  const path = usePathname();
  const tabNow = useSearchParams().get("tab");
  const router = useRouter();
  const { me, ready, can, canSee, canWrite } = useMe();
  const unread = useApi<{ count: number }>("notifications/unread-count");
  const billing = useBillingStatus(ready && !!me);
  const reduce = useReducedMotion();
  const [open, setOpen] = useState(false);
  const online = useOnline();
  const collapsed = useSyncExternalStore(subscribe, readCollapsed, () => false);
  const toggleCollapsed = () => setFlag(COLLAPSE_KEY, !collapsed);
  const advancedOpen = useSyncExternalStore(subscribe, readAdvanced, () => false);
  const [query, setQuery] = useState("");
  useEffect(() => { if (ready && !me) router.replace(`/dashboard/login?next=${encodeURIComponent(path)}`); }, [ready, me, path, router]);
  useEffect(() => {
    if (!open) return;
    const h = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [open]);
  // Patients and shop customers have no control panel: send them to their own area instead of an almost empty dashboard.
  const customerOnly = !!me && me.roles.length > 0 && me.roles.every((r) => r === "PATIENT" || r === "CUSTOMER");
  useEffect(() => { if (customerOnly) router.replace(me!.roles.includes("PATIENT") ? "/portal" : "/account"); }, [customerOnly, me, router]);
  // A disabled business (unpaid subscription) only has its pay screen: everything else sends the owner there.
  const disabled = billing.data?.state === "DISABLED";
  const onPay = path.startsWith("/dashboard/billing/pay");
  const canPay = ready && !!me && can("billing.manage");
  useEffect(() => { if (disabled && canPay && !onPay) router.replace("/dashboard/billing/pay"); }, [disabled, canPay, onPay, router]);
  if (!ready || !me || customerOnly) return <div className="p-6"><Loading /></div>;

  const open_ = (n: NavItem) => (!n.screen || canSee(n.screen)) && (!n.write || !n.screen || canWrite(n.screen)) && (!n.perm || (Array.isArray(n.perm) ? n.perm.some(can) : can(n.perm)));
  const allowed = nav.filter((n) => (!disabled || n.href.startsWith("/dashboard/billing")) && open_(n));
  // The items for the screen that is open: the longest matching path (so /products/labels is not /products), then the one whose ?tab= is the open part.
  const hitsIn = (list: NavItem[]) => {
    const hits = list.filter((n) => { const p = pathOf(n); return p === "/dashboard" ? path === p : path === p || path.startsWith(p + "/"); });
    const longest = Math.max(0, ...hits.map((n) => pathOf(n).length));
    return hits.filter((n) => pathOf(n).length === longest);
  };
  const pick = (same: NavItem[]) => same.find((n) => tabOf(n) === tabNow) ?? same.find((n) => !tabOf(n)) ?? same[0];
  // Typing the address of a screen the owner hid shows "no access" (the backend refuses the data regardless).
  const sameAll = hitsIn(nav);
  const exact = tabNow ? sameAll.find((n) => tabOf(n) === tabNow) : undefined;
  const blocked = sameAll.length > 0 && (exact ? !open_(exact) : !sameAll.some(open_));
  const current = pick(hitsIn(allowed));
  const isActive = (n: NavItem) => n === current;
  const count = unread.data?.count ?? 0;
  const fullName = `${me.firstName} ${me.lastName}`.trim();
  const hour = new Date().getHours();
  const bell = (cls: string) => (
    <Link href="/dashboard/notifications" className={`relative rounded-xl p-2.5 text-slate-600 transition hover:bg-brand-soft hover:text-brand ${cls}`} aria-label={t("nav.notifications")}>
      <Icon name="bell" />
      {count > 0 && <span className="absolute end-1 top-1 flex h-4 min-w-4 animate-pop items-center justify-center rounded-full bg-red-600 px-1 text-[10px] font-bold text-white ring-2 ring-white">{count}</span>}
    </Link>
  );
  const langLink = (
    <a href={`/api/lang?l=${locale === "ar" ? "en" : "ar"}`} className="inline-flex items-center gap-1.5 rounded-xl px-2.5 py-2 text-xs font-semibold text-slate-600 transition hover:bg-brand-soft hover:text-brand"><Icon name="globe" className="h-4 w-4" />{locale === "ar" ? "EN" : "عربي"}</a>
  );
  const mini = collapsed; // icon-only sidebar (desktop only)
  const link = (n: NavItem) => {
    const active = isActive(n);
    return (
      <Link key={n.href} href={n.href} onClick={() => { setOpen(false); setQuery(""); }} title={mini ? t(n.label) : undefined} className={`group relative flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-semibold transition-colors duration-200 ${mini ? "lg:justify-center lg:px-0" : ""} ${active ? "text-white" : "text-slate-600 hover:bg-brand-soft hover:text-brand-dark"}`}>
        {active && (reduce ? <span className="absolute inset-0 rounded-xl bg-brand-gradient shadow-brand" /> : <motion.span layoutId="nav-active" transition={{ type: "spring", stiffness: 420, damping: 36 }} className="absolute inset-0 rounded-xl bg-brand-gradient shadow-brand" />)}
        <span className={`relative transition-transform duration-200 ${active ? "" : "group-hover:scale-110"}`}><NavIcon icon={n.icon} /></span>
        <span className={`relative truncate ${mini ? "lg:hidden" : ""}`}>{t(n.label)}</span>
      </Link>
    );
  };
  const ws = words(query);
  const found = (n: NavItem) => ws.length === 0 || textMatches(ws, t(n.label), n.group ? t(n.group) : null);
  const list = (rows: NavItem[], sub: boolean) => {
    const out: React.ReactNode[] = [];
    let lastGroup: string | undefined;
    rows.forEach((n) => {
      if (n.group && n.group !== lastGroup) out.push(mini ? <div key={`g-${n.group}-${n.href}`} className="mx-3 my-2 hidden border-t border-slate-200/70 lg:block" /> : null, <div key={`h-${n.group}-${n.href}`} className={`${sub ? "nav-subsection" : "nav-section"} ${mini ? "lg:hidden" : ""}`}>{t(n.group)}</div>);
      lastGroup = n.group;
      out.push(link(n));
    });
    return out;
  };
  const main = allowed.filter((n) => !n.advanced && found(n));
  const expert = allowed.filter((n) => n.advanced && found(n));
  // the expert screens stay folded until asked for, the open screen is one of them, or a search found some
  const showExpert = advancedOpen || !!current?.advanced || ws.length > 0;
  const searchable = allowed.length > 12;
  const items: React.ReactNode[] = [...list(main, false)];
  if (expert.length > 0) {
    items.push(
      <button key="advanced" type="button" onClick={() => setFlag(ADVANCED_KEY, !advancedOpen)} aria-expanded={showExpert} title={mini ? t("navgrp.advanced") : undefined}
        className={`mt-3 flex w-full items-center gap-3 rounded-xl border border-dashed border-slate-300 px-3 py-2.5 text-start text-sm font-semibold text-slate-600 transition hover:border-brand/40 hover:bg-brand-soft hover:text-brand-dark ${mini ? "lg:justify-center lg:px-0" : ""}`}>
        <NavIcon icon="settings" />
        <span className={`min-w-0 flex-1 ${mini ? "lg:hidden" : ""}`}><span className="block truncate">{t("navgrp.advanced")}</span><span className="block truncate text-[11px] font-normal text-slate-500">{t("nav.advancedHint")}</span></span>
        <Icon name="arrow" className={`h-4 w-4 shrink-0 transition-transform ${mini ? "lg:hidden" : ""} ${showExpert ? "-rotate-90" : "rotate-90"}`} />
      </button>,
    );
    if (showExpert) items.push(<div key="advanced-list" className="mt-1 space-y-0.5 border-s-2 border-slate-200/80 ps-1">{list(expert, true)}</div>);
  }
  if (ws.length > 0 && main.length === 0 && expert.length === 0) items.push(<p key="none" className="px-3 py-4 text-sm text-slate-500">{t("nav.noMatch")}</p>);
  const quickOnes = allowed.filter((n) => n.quick);
  const quick = (quickOnes.length > 0 ? quickOnes : allowed).slice(0, 4);

  return (
    <div className="min-h-dvh lg:flex print:block">
      {/* phone top bar */}
      <header className="glass sticky top-0 z-30 flex items-center justify-between border-b border-slate-200/70 px-3 py-2.5 print:hidden lg:hidden">
        <button onClick={() => setOpen(true)} aria-label="Menu" className="rounded-xl p-2 text-slate-700 transition hover:bg-brand-soft active:scale-95"><Icon name="menu" className="h-6 w-6" /></button>
        <span className="flex min-w-0 items-center gap-2 font-bold"><TenantMark className="h-7 w-7" /><span className="truncate text-ink">{current ? t(current.label) : siteName}</span></span>
        {bell("")}
      </header>

      {/* the phone menu lives in a screen-sized clipping box: a closed drawer parked outside the screen must never make the page wider than the phone (it pushed the bottom bar's last tab off-screen) */}
      <div className={`fixed inset-0 z-40 overflow-hidden lg:pointer-events-auto lg:contents ${open ? "" : "pointer-events-none"}`}>
      <div onClick={() => setOpen(false)} className={`absolute inset-0 z-40 bg-slate-900/40 backdrop-blur-sm transition-opacity duration-300 lg:hidden ${open ? "opacity-100" : "pointer-events-none opacity-0"}`} aria-hidden />

      <aside className={`sidebar-bg absolute inset-y-0 start-0 z-50 flex w-72 flex-col border-e border-slate-200/70 p-4 shadow-2xl transition-[transform,width] duration-300 ease-out print:hidden lg:pointer-events-auto lg:sticky lg:top-0 lg:z-20 lg:h-screen lg:shrink-0 lg:translate-x-0! lg:shadow-none ${mini ? "lg:w-[4.75rem] lg:px-3" : "lg:w-64"} ${open ? "translate-x-0" : "ltr:-translate-x-full rtl:translate-x-full"}`}>
        <div className={`mb-4 flex items-center justify-between gap-2 ${mini ? "lg:flex-col" : ""}`}>
          <Link href="/" className="flex min-w-0 items-center gap-3">
            <TenantMark />
            <span className={`min-w-0 ${mini ? "lg:hidden" : ""}`}><span className="block truncate text-[15px] font-bold leading-tight text-ink">{siteName}</span><span className="block truncate text-[11px] text-slate-500">{t("dash.controlPanel")}</span></span>
          </Link>
          <button onClick={() => setOpen(false)} aria-label="Close" className="rounded-xl p-2 text-slate-500 hover:bg-slate-100 lg:hidden"><Icon name="close" /></button>
        </div>
        {searchable && (
          <div className={`relative mb-2 ${mini ? "lg:hidden" : ""}`}>
            <Icon name="search" className="pointer-events-none absolute start-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
            <input type="search" value={query} onChange={(e) => setQuery(e.target.value)} onKeyDown={(e) => { if (e.key === "Escape") setQuery(""); }} placeholder={t("nav.search")} aria-label={t("nav.search")}
              className="w-full rounded-xl border border-slate-200 bg-white/80 py-2 pe-3 ps-9 text-sm outline-none transition focus:border-brand focus:ring-2 focus:ring-brand/20" />
          </div>
        )}
        <nav className="-mx-1 flex-1 space-y-0.5 overflow-y-auto px-1 pb-2">{items}</nav>
        <div className="mt-3 space-y-3 border-t border-slate-200/70 pt-3">
          <div className={`flex items-center gap-3 ${mini ? "lg:justify-center" : ""}`}>
            <Avatar name={fullName} />
            <div className={`min-w-0 ${mini ? "lg:hidden" : ""}`}>
              <p className="truncate text-sm font-semibold">{fullName}</p>
              <p className="truncate text-xs text-slate-500">{me.roles.map((r) => t(`role.${r}`)).join(", ")}</p>
            </div>
          </div>
          <div className={`flex items-center justify-between ${mini ? "lg:flex-col lg:gap-2" : ""}`}>
            <LogoutButton />
            <button onClick={toggleCollapsed} aria-label={mini ? "Expand" : "Collapse"} className="hidden rounded-xl p-2 text-slate-500 transition hover:bg-brand-soft hover:text-brand lg:block"><Icon name="arrow" className={`h-4 w-4 ${(mini ? locale !== "ar" : locale === "ar") ? "" : "rotate-180"}`} /></button>
          </div>
          {!whiteLabel && <div className={mini ? "lg:hidden" : ""}><PoweredBy label={t("brand.powered")} name={t("brand.name")} /></div>}
        </div>
      </aside>
      </div>

      <div className="flex min-w-0 flex-1 flex-col">
        {can("billing.manage") && <SubscriptionBar status={billing.data} />}
        {/* desktop top bar */}
        <header className="glass sticky top-0 z-20 hidden items-center justify-between gap-4 border-b border-slate-200/60 px-8 py-3 print:hidden lg:flex">
          <p className="text-sm text-slate-500">{hour < 12 ? t("dash.morning") : t("dash.hello")}, <b className="font-semibold text-ink">{me.firstName}</b></p>
          <div className="flex items-center gap-1">{langLink}{bell("")}</div>
        </header>
        <motion.main key={path} initial={reduce ? false : { opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.35, ease: [0.22, 1, 0.36, 1] }} className="min-w-0 flex-1 overflow-x-clip p-4 pb-24 sm:p-6 lg:p-8 lg:pb-8 print:p-0"><SwRegister />{!online && <div className="mb-4 rounded-xl border border-amber-200 bg-amber-50 px-4 py-2 text-sm text-amber-900 print:hidden">📡 {t("offline.dashboard")}</div>}{disabled && !canPay ? <div className="mx-auto max-w-md rounded-2xl border border-red-200 bg-red-50 p-6 text-center"><h1 className="text-lg font-bold text-red-800">{t("sub.disabledTitle")}</h1><p className="mt-2 text-sm text-red-700">{t("sub.ownerOnly")}</p></div> : blocked ? <div className="mx-auto max-w-md rounded-2xl border border-slate-200 bg-white p-6 text-center"><h1 className="text-lg font-bold text-ink">{t("dash.noAccess")}</h1><p className="mt-2 text-sm text-slate-600">{t("dash.noAccessHint")}</p></div> : <NavTitleContext.Provider value={current ? t(current.label) : null}>{children}</NavTitleContext.Provider>}</motion.main>
      </div>

      {/* phone quick bar */}
      <nav className="glass fixed inset-x-0 bottom-0 z-30 flex justify-around border-t border-slate-200/70 px-1 pb-[max(0.35rem,env(safe-area-inset-bottom))] pt-1.5 print:hidden lg:hidden">
        {quick.map((n) => (
          <Link key={n.href} href={n.href} className={`flex min-w-0 flex-1 flex-col items-center gap-0.5 rounded-xl py-1 text-[11px] font-medium transition active:scale-95 ${isActive(n) ? "text-brand" : "text-slate-500"}`}>
            <span className={`rounded-full px-4 py-1 transition-colors ${isActive(n) ? "bg-brand-soft-2" : ""}`}><NavIcon icon={n.icon} className="h-5 w-5" /></span>
            <span className="max-w-full truncate">{t(n.label)}</span>
          </Link>
        ))}
        <button onClick={() => setOpen(true)} className="flex min-w-0 flex-1 flex-col items-center gap-0.5 py-1 text-[11px] font-medium text-slate-500 active:scale-95">
          <span className="px-4 py-1"><Icon name="menu" className="h-5 w-5" /></span>
          <span>{t("common.more")}</span>
        </button>
      </nav>
    </div>
  );
}
