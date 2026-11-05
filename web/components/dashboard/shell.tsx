"use client";

import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState, useSyncExternalStore } from "react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
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
import { CommandPalette } from "./command";
import { ThemeToggle, useWorkspaceTheme } from "../theme-toggle";

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
  return <img src={b.data?.logoFileId ? fileUrl(b.data.logoFileId, "thumb") : "/api/monogram"} alt="" className={`${className} shrink-0 rounded-[9px] object-cover ring-1 ring-line`} />;
}

const COLLAPSE_KEY = "dashSidebarMini";
const ADVANCED_KEY = "dashNavAdvanced";
const listeners = new Set<() => void>();
const readFlag = (key: string) => { try { return localStorage.getItem(key) === "1"; } catch { return false; } };   // storage can be blocked: the default is used
const setFlag = (key: string, on: boolean) => { try { localStorage.setItem(key, on ? "1" : "0"); } catch { /* ignore */ } listeners.forEach((l) => l()); };
const readCollapsed = () => readFlag(COLLAPSE_KEY);
const readAdvanced = () => readFlag(ADVANCED_KEY);
const noopSub = () => () => {};
const subscribe = (cb: () => void) => { listeners.add(cb); return () => { listeners.delete(cb); }; };

/** Sidebar dashboard used by shop owners, doctors and staff. Items are hidden without the permission (the backend enforces it regardless). */
export function DashboardShell(props: { nav: NavItem[]; children: React.ReactNode }) {
  // the menu reads `?tab=` to light the right item, which needs a suspense boundary in the app router
  return <Suspense fallback={<div className="p-6"><Loading /></div>}><Shell {...props} /></Suspense>;
}

function Shell({ nav, children }: { nav: NavItem[]; children: React.ReactNode }) {
  const { t, siteName, locale, whiteLabel, tenantType } = useI18n();
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
  const [cmd, setCmd] = useState(false);
  const isMac = useSyncExternalStore(noopSub, () => /Mac|iPhone|iPad/.test(navigator.platform), () => false);
  useWorkspaceTheme();
  useEffect(() => {
    const h = (e: KeyboardEvent) => { if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") { e.preventDefault(); setCmd((v) => !v); } };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, []);
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
  if (!ready || !me || customerOnly) return <div className="mx-auto max-w-6xl p-6 lg:p-10"><Loading /></div>;
  // The first-run setup (/dashboard/welcome) is a focused full-screen journey: signed in, but without the menu around it.
  if (path === "/dashboard/welcome" && !disabled) return <>{children}</>;

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
    <Link href="/dashboard/notifications" className={`relative grid h-9 w-9 place-items-center rounded-[10px] text-muted transition hover:bg-[color-mix(in_srgb,var(--ink)_6%,transparent)] hover:text-ink pointer-coarse:h-11 pointer-coarse:w-11 ${cls}`} aria-label={t("nav.notifications")}>
      <Icon name="bell" className="h-[18px] w-[18px]" />
      {count > 0 && <span className="absolute -end-0.5 -top-0.5 flex h-4 min-w-4 animate-pop items-center justify-center rounded-full bg-red-500 px-1 text-[10px] font-bold text-white ring-2 ring-surface">{count > 99 ? "99+" : count}</span>}
    </Link>
  );
  const langLink = (
    <a href={`/api/lang?l=${locale === "ar" ? "en" : "ar"}`} className="inline-flex h-9 items-center gap-1.5 rounded-[10px] px-2.5 text-xs font-semibold text-muted transition hover:bg-[color-mix(in_srgb,var(--ink)_6%,transparent)] hover:text-ink"><Icon name="globe" className="h-4 w-4" />{locale === "ar" ? "EN" : "عربي"}</a>
  );
  const mini = collapsed; // icon-only sidebar (desktop only)
  const link = (n: NavItem) => {
    const active = isActive(n);
    return (
      <Link key={n.href} href={n.href} onClick={() => setOpen(false)} title={mini ? t(n.label) : undefined} aria-current={active ? "page" : undefined}
        className={`group relative flex items-center gap-2.5 rounded-[9px] px-2.5 py-[7px] text-[13.5px] font-medium transition-colors duration-150 ${mini ? "lg:justify-center lg:px-0 lg:py-2" : ""} ${active ? "text-ink" : "text-muted hover:bg-[color-mix(in_srgb,var(--ink)_5%,transparent)] hover:text-ink"}`}>
        {active && (
          <motion.span layoutId="nav-active" transition={reduce ? { duration: 0 } : { type: "spring", stiffness: 520, damping: 40 }} className="absolute inset-0 rounded-[9px] bg-surface shadow-[0_0_0_1px_var(--line),0_1px_3px_rgb(16_18_24/.07)]">
            <span className="absolute inset-y-2 start-0 w-[3px] rounded-full bg-brand" />
          </motion.span>
        )}
        <span className={`relative ${active ? "text-brand" : "text-faint group-hover:text-ink-2"} transition-colors`}><NavIcon icon={n.icon} className="h-[18px] w-[18px]" /></span>
        <span className={`relative truncate ${active ? "font-semibold" : ""} ${mini ? "lg:hidden" : ""}`}>{t(n.label)}</span>
      </Link>
    );
  };
  const list = (rows: NavItem[], sub: boolean) => {
    const out: React.ReactNode[] = [];
    let lastGroup: string | undefined;
    rows.forEach((n) => {
      if (n.group && n.group !== lastGroup) out.push(mini ? <div key={`g-${n.group}-${n.href}`} className="mx-3 my-2.5 hidden border-t border-line lg:block" /> : null, <div key={`h-${n.group}-${n.href}`} className={`${sub ? "nav-subsection" : "nav-section"} ${mini ? "lg:hidden" : ""}`}>{t(n.group)}</div>);
      lastGroup = n.group;
      out.push(link(n));
    });
    return out;
  };
  const main = allowed.filter((n) => !n.advanced);
  const expert = allowed.filter((n) => n.advanced);
  // the expert screens stay folded until asked for, or the open screen is one of them
  const showExpert = advancedOpen || !!current?.advanced;
  const items: React.ReactNode[] = [...list(main, false)];
  if (expert.length > 0) {
    items.push(
      <button key="advanced" type="button" onClick={() => setFlag(ADVANCED_KEY, !advancedOpen)} aria-expanded={showExpert} title={mini ? t("navgrp.advanced") : undefined}
        className={`mt-4 flex w-full items-center gap-2.5 rounded-[10px] border border-dashed border-line-strong px-2.5 py-2 text-start text-[13px] font-semibold text-muted transition hover:border-brand/40 hover:text-ink ${mini ? "lg:justify-center lg:px-0" : ""}`}>
        <NavIcon icon="sliders" className="h-[18px] w-[18px]" />
        <span className={`min-w-0 flex-1 ${mini ? "lg:hidden" : ""}`}><span className="block truncate">{t("navgrp.advanced")}</span><span className="block truncate text-[11px] font-normal text-faint">{t("nav.advancedHint")}</span></span>
        <Icon name="chevron" className={`h-4 w-4 shrink-0 transition-transform duration-200 ${mini ? "lg:hidden" : ""} ${showExpert ? "rotate-90" : "flip-rtl"}`} />
      </button>,
    );
    items.push(
      <AnimatePresence key="advanced-list" initial={false}>
        {showExpert && (
          <motion.div initial={{ height: 0, opacity: 0 }} animate={{ height: "auto", opacity: 1 }} exit={{ height: 0, opacity: 0 }} transition={{ duration: reduce ? 0 : 0.22, ease: [0.2, 0.8, 0.2, 1] }} className="overflow-hidden">
            <div className="mt-1 space-y-px border-s border-line ps-1.5">{list(expert, true)}</div>
          </motion.div>
        )}
      </AnimatePresence>,
    );
  }
  const quickOnes = allowed.filter((n) => n.quick);
  const quick = (quickOnes.length > 0 ? quickOnes : allowed).slice(0, 4);
  const commandItems = allowed.map((n) => ({ href: n.href, label: t(n.label), group: n.group ? t(n.group) : n.advanced ? t("navgrp.advanced") : undefined, icon: n.icon }));
  const role = tenantType === "CLINIC" ? "clinic" : tenantType === "LAWYER" ? "lawyer" : "store";
  const searchBtn = (cls = "") => (
    <button type="button" onClick={() => setCmd(true)} className={`group flex h-9 items-center gap-2 rounded-[10px] bg-surface px-3 text-[13px] text-faint shadow-[0_0_0_1px_var(--line-strong)] transition hover:text-muted ${cls}`}>
      <Icon name="search" className="h-4 w-4" /><span className="min-w-0 flex-1 truncate text-start">{t("nav.search")}</span><span className="kbd hidden sm:inline-flex">{isMac ? "⌘" : "Ctrl"} K</span>
    </button>
  );

  return (
    <div data-role={role} className="min-h-dvh bg-canvas lg:flex print:block">
      <CommandPalette open={cmd} onClose={() => setCmd(false)} items={commandItems} />
      {/* phone top bar */}
      <header className="glass sticky top-0 z-30 flex items-center justify-between gap-2 px-2.5 py-2 hairline-b print:hidden lg:hidden">
        <button onClick={() => setOpen(true)} aria-label="Menu" className="grid h-11 w-11 place-items-center rounded-[10px] text-ink-2 transition active:scale-95"><Icon name="menu" className="h-[22px] w-[22px]" /></button>
        <span className="flex min-w-0 items-center gap-2"><TenantMark className="h-7 w-7" /><span className="truncate font-display text-[15px] font-semibold text-ink">{current ? t(current.label) : siteName}</span></span>
        <span className="flex items-center"><button onClick={() => setCmd(true)} aria-label={t("nav.search")} className="grid h-11 w-11 place-items-center rounded-[10px] text-muted"><Icon name="search" className="h-[19px] w-[19px]" /></button>{bell("")}</span>
      </header>

      {/* the phone menu lives in a screen-sized clipping box: a closed drawer parked outside the screen must never make the page wider than the phone */}
      <div className={`fixed inset-0 z-40 overflow-hidden lg:pointer-events-auto lg:contents ${open ? "" : "pointer-events-none"}`}>
      <div onClick={() => setOpen(false)} className={`absolute inset-0 z-40 bg-[rgb(10_12_16/.4)] backdrop-blur-[2px] transition-opacity duration-300 lg:hidden ${open ? "opacity-100" : "pointer-events-none opacity-0"}`} aria-hidden />

      <aside className={`sidebar-bg absolute inset-y-0 start-0 z-50 flex w-[17rem] flex-col border-e border-line px-3 pb-3 pt-3.5 shadow-[var(--shadow-float)] transition-[transform,width] duration-300 ease-[cubic-bezier(.2,.8,.2,1)] print:hidden lg:pointer-events-auto lg:sticky lg:top-0 lg:z-20 lg:h-screen lg:shrink-0 lg:translate-x-0! lg:shadow-none ${mini ? "lg:w-[4.25rem] lg:px-2" : "lg:w-[15.5rem]"} ${open ? "translate-x-0" : "ltr:-translate-x-full rtl:translate-x-full"}`}>
        <div className={`mb-3 flex items-center justify-between gap-2 px-1 ${mini ? "lg:flex-col lg:px-0" : ""}`}>
          <Link href="/" className="group flex min-w-0 items-center gap-2.5 rounded-[10px] p-1 transition hover:bg-[color-mix(in_srgb,var(--ink)_5%,transparent)]">
            <TenantMark className="h-8 w-8" />
            <span className={`min-w-0 ${mini ? "lg:hidden" : ""}`}><span className="block truncate text-[14px] font-semibold leading-tight text-ink">{siteName}</span><span className="flex items-center gap-1 truncate text-[11px] text-faint">{t("dash.controlPanel")}<Icon name="external" className="h-3 w-3 opacity-0 transition group-hover:opacity-100" /></span></span>
          </Link>
          <button onClick={() => setOpen(false)} aria-label="Close" className="grid h-10 w-10 place-items-center rounded-[10px] text-muted hover:bg-surface lg:hidden"><Icon name="close" className="h-5 w-5" /></button>
        </div>
        <div className={`mb-2 px-0.5 ${mini ? "lg:hidden" : ""}`}>{searchBtn("w-full")}</div>
        {mini && <button onClick={() => setCmd(true)} aria-label={t("nav.search")} className="mx-auto mb-2 hidden h-9 w-9 place-items-center rounded-[10px] text-muted hover:bg-surface lg:grid"><Icon name="search" className="h-[18px] w-[18px]" /></button>}
        <nav className="-mx-1 flex-1 space-y-px overflow-y-auto px-1 pb-2 [scrollbar-width:none]">{items}</nav>
        <div className="mt-2 space-y-2 border-t border-line pt-3">
          <div className={`flex items-center gap-2.5 rounded-[10px] px-1.5 ${mini ? "lg:justify-center lg:px-0" : ""}`}>
            <Avatar name={fullName} className="h-8 w-8 text-xs" />
            <div className={`min-w-0 flex-1 ${mini ? "lg:hidden" : ""}`}>
              <p className="truncate text-[13px] font-semibold text-ink">{fullName}</p>
              <p className="truncate text-[11px] text-faint">{me.roles.map((r) => t(`role.${r}`)).join(", ")}</p>
            </div>
          </div>
          <div className={`flex items-center justify-between gap-1 ${mini ? "lg:flex-col" : ""}`}>
            <LogoutButton />
            <div className={`flex items-center ${mini ? "lg:flex-col" : ""}`}>
              <ThemeToggle className="lg:hidden" />
              <button onClick={toggleCollapsed} aria-label={mini ? "Expand" : "Collapse"} className="hidden h-9 w-9 place-items-center rounded-[10px] text-muted transition hover:bg-surface hover:text-ink lg:grid"><Icon name="layout" className={`h-4 w-4 ${(mini ? locale !== "ar" : locale === "ar") ? "" : "-scale-x-100"}`} /></button>
            </div>
          </div>
          {!whiteLabel && <div className={`px-1 ${mini ? "lg:hidden" : ""}`}><PoweredBy label={t("brand.powered")} name={t("brand.name")} /></div>}
        </div>
      </aside>
      </div>

      <div className="flex min-w-0 flex-1 flex-col">
        {can("billing.manage") && <SubscriptionBar status={billing.data} />}
        {/* desktop top bar */}
        <header className="glass sticky top-0 z-20 hidden h-14 items-center justify-between gap-4 px-8 hairline-b print:hidden lg:flex">
          <div className="flex min-w-0 items-center gap-2 text-[13px]">
            <span className="text-faint">{hour < 12 ? t("dash.morning") : t("dash.hello")}, <span className="font-medium text-ink-2">{me.firstName}</span></span>
            {current && <><Icon name="chevron" className="h-3.5 w-3.5 text-faint flip-rtl" /><span className="truncate font-semibold text-ink">{t(current.label)}</span></>}
          </div>
          <div className="flex items-center gap-1">{searchBtn("w-56 me-2")}{langLink}<ThemeToggle />{bell("")}</div>
        </header>
        <motion.main key={path} initial={reduce ? false : { opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.28, ease: [0.2, 0.8, 0.2, 1] }} className="mx-auto w-full min-w-0 max-w-[90rem] flex-1 overflow-x-clip p-4 pb-24 sm:p-6 lg:px-10 lg:py-8 print:p-0">
          <SwRegister />
          {!online && <div className="mb-4 flex items-center gap-2 rounded-[12px] bg-amber-50 px-4 py-2.5 text-sm text-amber-900 ring-1 ring-inset ring-amber-600/20 print:hidden"><Icon name="alert" className="h-4 w-4" />{t("offline.dashboard")}</div>}
          {disabled && !canPay
            ? <div className="mx-auto mt-10 max-w-md rounded-[16px] bg-surface p-8 text-center shadow-card"><span className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-[14px] bg-red-50 text-red-600"><Icon name="alert" /></span><h1 className="font-display text-lg font-semibold text-ink">{t("sub.disabledTitle")}</h1><p className="mt-2 text-sm text-muted">{t("sub.ownerOnly")}</p></div>
            : blocked
              ? <div className="mx-auto mt-10 max-w-md rounded-[16px] bg-surface p-8 text-center shadow-card"><span className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-[14px] bg-slate-100 text-slate-600"><Icon name="shield" /></span><h1 className="font-display text-lg font-semibold text-ink">{t("dash.noAccess")}</h1><p className="mt-2 text-sm text-muted">{t("dash.noAccessHint")}</p></div>
              : <NavTitleContext.Provider value={current ? t(current.label) : null}>{children}</NavTitleContext.Provider>}
        </motion.main>
      </div>

      {/* phone quick bar */}
      <nav className="glass fixed inset-x-0 bottom-0 z-30 flex justify-around px-1 pb-[max(0.35rem,env(safe-area-inset-bottom))] pt-1 print:hidden lg:hidden" style={{ boxShadow: "inset 0 1px 0 var(--line)" }}>
        {quick.map((n) => {
          const on = isActive(n);
          return (
            <Link key={n.href} href={n.href} className={`relative flex min-w-0 flex-1 flex-col items-center gap-0.5 rounded-xl py-1 text-[10.5px] font-medium transition active:scale-95 ${on ? "text-ink" : "text-faint"}`}>
              <span className="relative grid h-8 w-14 place-items-center">
                {on && <motion.span layoutId="quick-active" transition={reduce ? { duration: 0 } : { type: "spring", stiffness: 500, damping: 38 }} className="absolute inset-0 rounded-full bg-brand-soft-2" />}
                <span className={`relative ${on ? "text-brand" : ""}`}><NavIcon icon={n.icon} className="h-5 w-5" /></span>
              </span>
              <span className="max-w-full truncate">{t(n.label)}</span>
            </Link>
          );
        })}
        <button onClick={() => setOpen(true)} className="flex min-w-0 flex-1 flex-col items-center gap-0.5 py-1 text-[10.5px] font-medium text-faint active:scale-95">
          <span className="grid h-8 w-14 place-items-center"><Icon name="menu" className="h-5 w-5" /></span>
          <span>{t("common.more")}</span>
        </button>
      </nav>
    </div>
  );
}
