"use client";

import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Suspense, useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { api } from "@/lib/client";
import { useApi, useMe } from "../hooks";
import { useI18n } from "../i18n-provider";
import { PoweredBy } from "../brand";
import { Icon, hasIcon } from "../icons";
import { Avatar, Loading } from "../ui";
import { SwRegister, forgetPrivateCopies, useOnline } from "../sw";
import { fileUrl } from "@/lib/media";
import { SubscriptionBar, useBillingStatus } from "./subscription-bar";
import { NavTitleContext } from "./section";
import { CommandPalette } from "./command";
import { ThemeToggle, setTheme, useTheme, useWorkspaceTheme } from "../theme-toggle";
import { Menu, Popover, Sheet, Tooltip } from "../overlay";
import { ToastProvider } from "../toast";
import { dateTime } from "@/lib/format";

/**
 * The menu items. `screen` is the access screen (hidden / read / write per employee); `perm` is for items outside that list; `write` also needs write access to `screen`.
 * `rail` is the icon-rail section the item lives in (one icon per section, its items in the panel next to it); `group` is a sub-heading inside that panel. `?tab=` opens
 * one part of a screen directly (the menu stays flat). `advanced` items are folded under «Advanced» in their section. `quick` items fill the phone's bottom bar.
 * `badge` polls a count to show next to the item (and on its rail icon): `{path, field}` of a JSON endpoint.
 */
export type NavItem = {
  href: string; label: string; perm?: string | string[]; screen?: string | string[]; write?: boolean; icon?: string; group?: string; advanced?: boolean; quick?: boolean;
  rail?: string; badge?: { path: string; field: string; tone?: "red" | "brand" };
};

/** One icon of the rail: a section of the menu. */
export type RailSection = { key: string; label: string; icon: string };

const pathOf = (n: NavItem) => n.href.split("?")[0];
const tabOf = (n: NavItem) => new URLSearchParams(n.href.split("?")[1] ?? "").get("tab");

function NavIcon({ icon, className }: { icon?: string; className?: string }) {
  return hasIcon(icon) ? <Icon name={icon} className={className ?? "h-[19px] w-[19px]"} /> : <span aria-hidden>{icon ?? "•"}</span>;
}

/** The business's own logo (or a monogram of its name) as a rounded tile. */
function TenantMark({ className = "h-10 w-10" }: { className?: string }) {
  const b = useApi<{ logoFileId?: string }>("tenant/branding");
  /* eslint-disable-next-line @next/next/no-img-element */
  return <img src={b.data?.logoFileId ? fileUrl(b.data.logoFileId, "thumb") : "/api/monogram"} alt="" className={`${className} shrink-0 rounded-[10px] object-cover`} />;
}

const PANEL_KEY = "dashPanelPinned";
const ADVANCED_KEY = "dashNavAdvanced";
const listeners = new Set<() => void>();
const readFlag = (key: string, dflt: boolean) => { try { const v = localStorage.getItem(key); return v == null ? dflt : v === "1"; } catch { return dflt; } };
const setFlag = (key: string, on: boolean) => { try { localStorage.setItem(key, on ? "1" : "0"); } catch { /* storage blocked: keeps the default */ } listeners.forEach((l) => l()); };
const subscribe = (cb: () => void) => { listeners.add(cb); return () => { listeners.delete(cb); }; };
const noopSub = () => () => {};

/** Counts polled for menu badges (each endpoint once, every 30 s, paused while the tab is hidden). */
function useBadges(items: NavItem[]) {
  // a string key, so the polling starts once and does not restart on every render (the item list is rebuilt each time)
  const key = Array.from(new Set(items.map((n) => n.badge?.path).filter(Boolean) as string[])).sort().join("|");
  const [data, setData] = useState<Record<string, Record<string, number>>>({});
  useEffect(() => {
    const paths = key ? key.split("|") : [];
    if (paths.length === 0) return;
    let stop = false;
    const load = () => {
      if (document.hidden) return;
      paths.forEach((p) => api<Record<string, number>>(p).then((d) => { if (!stop) setData((x) => ({ ...x, [p]: d })); }).catch(() => undefined));
    };
    load();
    const id = setInterval(load, 30_000);
    const vis = () => !document.hidden && load();
    document.addEventListener("visibilitychange", vis);
    return () => { stop = true; clearInterval(id); document.removeEventListener("visibilitychange", vis); };
  }, [key]);
  return (n: NavItem) => (n.badge ? Number(data[n.badge.path]?.[n.badge.field] ?? 0) : 0);
}

/** The dashboard of shop owners, doctors and lawyers: an icon rail with one icon per section, the section's items next to it, a header, and a phone tab bar. */
export function DashboardShell(props: { nav: NavItem[]; rails: RailSection[]; fullBleed?: string[]; children: React.ReactNode }) {
  // the menu reads `?tab=` to light the right item, which needs a suspense boundary in the app router
  return <Suspense fallback={<div className="p-6"><Loading /></div>}><ToastProvider><Shell {...props} /></ToastProvider></Suspense>;
}

function Shell({ nav, rails, fullBleed = [], children }: { nav: NavItem[]; rails: RailSection[]; fullBleed?: string[]; children: React.ReactNode }) {
  const { t, siteName, locale, whiteLabel, tenantType } = useI18n();
  const path = usePathname();
  const tabNow = useSearchParams().get("tab");
  const router = useRouter();
  const { me, ready, can, canSee, canWrite } = useMe();
  const billing = useBillingStatus(ready && !!me);
  const reduce = useReducedMotion();
  const online = useOnline();
  const pinned = useSyncExternalStore(subscribe, () => readFlag(PANEL_KEY, true), () => true);
  const advancedOpen = useSyncExternalStore(subscribe, () => readFlag(ADVANCED_KEY, false), () => false);
  const [cmd, setCmd] = useState(false);
  const [more, setMore] = useState(false);
  const [flyout, setFlyout] = useState<string | null>(null);
  const flyTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const isMac = useSyncExternalStore(noopSub, () => /Mac|iPhone|iPad/.test(navigator.platform), () => false);
  useWorkspaceTheme();
  useEffect(() => {
    const h = (e: KeyboardEvent) => { if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") { e.preventDefault(); setCmd((v) => !v); } };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, []);
  useEffect(() => { if (ready && !me) router.replace(`/dashboard/login?next=${encodeURIComponent(path)}`); }, [ready, me, path, router]);
  // Patients and shop customers have no control panel: send them to their own area instead of an almost empty dashboard.
  const customerOnly = !!me && me.roles.length > 0 && me.roles.every((r) => r === "PATIENT" || r === "CUSTOMER");
  useEffect(() => { if (customerOnly) router.replace(me!.roles.includes("PATIENT") ? "/portal" : "/account"); }, [customerOnly, me, router]);
  // A disabled business (unpaid subscription) only has its pay screen: everything else sends the owner there.
  const disabled = billing.data?.state === "DISABLED";
  const onPay = path.startsWith("/dashboard/billing/pay");
  const canPay = ready && !!me && can("billing.manage");
  useEffect(() => { if (disabled && canPay && !onPay) router.replace("/dashboard/billing/pay"); }, [disabled, canPay, onPay, router]);

  const open_ = useCallback((n: NavItem) => (!n.screen || canSee(n.screen)) && (!n.write || !n.screen || canWrite(n.screen)) && (!n.perm || (Array.isArray(n.perm) ? n.perm.some(can) : can(n.perm))), [can, canSee, canWrite]);
  const allowed = useMemo(() => (me ? nav.filter((n) => (!disabled || n.href.startsWith("/dashboard/billing")) && open_(n)) : []), [nav, disabled, open_, me]);
  const badge = useBadges(allowed);

  if (!ready || !me || customerOnly) return <div className="mx-auto max-w-6xl p-6 lg:p-10"><Loading /></div>;
  // The first-run setup (/dashboard/welcome) is a focused full-screen journey: signed in, but without the menu around it.
  if (path === "/dashboard/welcome" && !disabled) return <>{children}</>;

  // The item of the screen that is open: the longest matching path (so /products/labels is not /products), then the one whose ?tab= is the open part.
  const hitsIn = (list: NavItem[]) => {
    const hits = list.filter((n) => { const p = pathOf(n); return p === "/dashboard" ? path === p : path === p || path.startsWith(p + "/"); });
    const longest = Math.max(0, ...hits.map((n) => pathOf(n).length));
    return hits.filter((n) => pathOf(n).length === longest);
  };
  const pick = (same: NavItem[]) => same.find((n) => tabOf(n) === tabNow) ?? same.find((n) => !tabOf(n)) ?? same[0];
  const sameAll = hitsIn(nav);
  const exact = tabNow ? sameAll.find((n) => tabOf(n) === tabNow) : undefined;
  const blocked = sameAll.length > 0 && (exact ? !open_(exact) : !sameAll.some(open_));
  const current = pick(hitsIn(allowed));
  const railOf = (n?: NavItem) => n?.rail ?? rails[0]?.key;
  const activeRail = railOf(current);
  const sections = rails.filter((r) => allowed.some((n) => railOf(n) === r.key));
  const itemsOf = (key: string) => allowed.filter((n) => railOf(n) === key);
  const sectionBadge = (key: string) => itemsOf(key).reduce((s, n) => s + badge(n), 0);
  const bleed = fullBleed.some((p) => path === p || (path.startsWith(p + "/") && !path.startsWith(p + "/followups")));
  const showPanel = pinned && !bleed && itemsOf(activeRail ?? "").length > 1;   // a section with one screen (home) needs no panel
  const panelKey = flyout ?? activeRail;
  const fullName = `${me.firstName} ${me.lastName}`.trim();
  const role = tenantType === "CLINIC" ? "clinic" : tenantType === "LAWYER" ? "lawyer" : "store";
  const commandItems = allowed.map((n) => ({ href: n.href, label: t(n.label), group: rails.find((r) => r.key === railOf(n)) ? t(rails.find((r) => r.key === railOf(n))!.label) : undefined, icon: n.icon }));
  const quickOnes = allowed.filter((n) => n.quick);
  const quick = (quickOnes.length > 0 ? quickOnes : allowed).slice(0, 4);
  const openFly = (key: string) => { if (showPanel) return; if (flyTimer.current) clearTimeout(flyTimer.current); setFlyout(key); };
  const closeFly = () => { if (flyTimer.current) clearTimeout(flyTimer.current); flyTimer.current = setTimeout(() => setFlyout(null), 180); };
  const holdFly = () => { if (flyTimer.current) clearTimeout(flyTimer.current); };
  const goSection = (key: string) => {
    setFlyout(null);
    const first = itemsOf(key).find((n) => !n.advanced) ?? itemsOf(key)[0];
    if (first) router.push(first.href);
  };
  const logout = async () => { await api("auth/logout", { body: {} }).catch(() => undefined); forgetPrivateCopies(); router.replace("/"); router.refresh(); };

  // ---- the panel of one section (pinned next to the rail, or as a flyout) ----
  const panel = (key: string, asFly: boolean) => {
    const sec = rails.find((r) => r.key === key);
    const rows = itemsOf(key);
    const main = rows.filter((n) => !n.advanced), expert = rows.filter((n) => n.advanced);
    const showExpert = advancedOpen || !!(current?.advanced && railOf(current) === key);
    const link = (n: NavItem) => {
      const active = n === current, count = badge(n);
      return (
        <Link key={n.href} href={n.href} aria-current={active ? "page" : undefined} onClick={() => setFlyout(null)}
          className={`group relative flex items-center gap-2.5 rounded-[10px] px-2.5 py-[7px] text-[13.5px] font-medium transition-colors duration-150 ${active ? "text-ink" : "text-muted hover:bg-[color-mix(in_srgb,var(--ink)_5%,transparent)] hover:text-ink"}`}>
          {active && (
            <motion.span layoutId={asFly ? "fly-active" : "nav-active"} transition={reduce ? { duration: 0 } : { type: "spring", stiffness: 520, damping: 40 }}
              className="absolute inset-0 rounded-[10px] bg-[var(--panel-active)] shadow-[0_0_0_1px_var(--line),0_1px_3px_rgb(16_18_24/.06)]">
              <span className="absolute inset-y-2 start-0 w-[3px] rounded-full bg-[var(--rail-accent)]" />
            </motion.span>
          )}
          <span className={`relative transition-colors ${active ? "text-[var(--rail-accent-ink)]" : "text-faint group-hover:text-ink-2"}`}><NavIcon icon={n.icon} className="h-[18px] w-[18px]" /></span>
          <span className={`relative min-w-0 flex-1 truncate ${active ? "font-semibold" : ""}`}>{t(n.label)}</span>
          {count > 0 && <span className={`relative min-w-[1.25rem] rounded-full px-1.5 py-px text-center text-[10.5px] font-bold tnum ${n.badge?.tone === "brand" ? "bg-brand-soft text-brand" : "bg-red-500 text-white"}`}>{count > 99 ? "99+" : count}</span>}
        </Link>
      );
    };
    const list = (items: NavItem[], sub: boolean) => {
      const out: React.ReactNode[] = [];
      let last: string | undefined;
      items.forEach((n) => {
        if (n.group && n.group !== last) out.push(<div key={`h-${n.group}-${n.href}`} className={sub ? "nav-subsection" : "nav-section"}>{t(n.group)}</div>);
        last = n.group;
        out.push(link(n));
      });
      return out;
    };
    return (
      <div className="flex h-full flex-col">
        <div className="px-3 pb-2 pt-4">
          <p className="px-1 font-display text-[15px] font-semibold text-ink">{sec ? t(sec.label) : siteName}</p>
        </div>
        <nav className="flex-1 space-y-px overflow-y-auto px-2 pb-3 [scrollbar-width:thin]">
          {list(main, false)}
          {expert.length > 0 && (
            <>
              <button type="button" onClick={() => setFlag(ADVANCED_KEY, !advancedOpen)} aria-expanded={showExpert}
                className="mt-3 flex w-full items-center gap-2.5 rounded-[10px] px-2.5 py-2 text-start text-[12.5px] font-semibold text-faint transition hover:text-ink">
                <NavIcon icon="sliders" className="h-4 w-4" /><span className="flex-1">{t("navgrp.advanced")}</span>
                <Icon name="chevron" className={`h-3.5 w-3.5 transition-transform duration-200 ${showExpert ? "rotate-90" : "flip-rtl"}`} />
              </button>
              <AnimatePresence initial={false}>
                {showExpert && (
                  <motion.div initial={{ height: 0, opacity: 0 }} animate={{ height: "auto", opacity: 1 }} exit={{ height: 0, opacity: 0 }} transition={{ duration: reduce ? 0 : 0.22, ease: [0.2, 0.8, 0.2, 1] }} className="overflow-hidden">
                    <div className="space-y-px border-s border-line ps-1.5">{list(expert, true)}</div>
                  </motion.div>
                )}
              </AnimatePresence>
            </>
          )}
        </nav>
      </div>
    );
  };

  const railBtn = (r: RailSection) => {
    const on = r.key === activeRail, count = sectionBadge(r.key);
    return (
      <Tooltip key={r.key} label={t(r.label)} disabled={showPanel ? false : flyout === r.key}>
        <button type="button" onClick={() => goSection(r.key)} onMouseEnter={() => openFly(r.key)} onMouseLeave={closeFly} onFocus={() => openFly(r.key)}
          aria-label={t(r.label)} aria-current={on ? "true" : undefined}
          className={`relative grid h-11 w-11 place-items-center rounded-[13px] transition-colors duration-150 ${on ? "text-[var(--rail-on-ink)]" : "text-[var(--rail-fg)] hover:bg-white/[.07] hover:text-[var(--rail-fg-hover)]"}`}>
          {on && <motion.span layoutId="rail-active" transition={reduce ? { duration: 0 } : { type: "spring", stiffness: 480, damping: 36 }} className="absolute inset-0 rounded-[13px]" style={{ background: "var(--rail-on)" }} />}
          <span className="relative"><NavIcon icon={r.icon} className="h-[20px] w-[20px]" /></span>
          {count > 0 && <span className="absolute -end-0.5 -top-0.5 flex h-[17px] min-w-[17px] items-center justify-center rounded-full bg-red-500 px-1 text-[9.5px] font-bold text-white ring-2 ring-[var(--rail-bg)]">{count > 99 ? "99+" : count}</span>}
        </button>
      </Tooltip>
    );
  };

  return (
    <div data-role={role} className={`bg-canvas print:block ${bleed ? "flex h-dvh flex-col overflow-hidden lg:flex-row" : "min-h-dvh lg:flex"}`}>
      <CommandPalette open={cmd} onClose={() => setCmd(false)} items={commandItems} />

      {/* ---------- desktop: icon rail + section panel ---------- */}
      <aside className="sticky top-0 z-30 hidden h-screen shrink-0 print:hidden lg:flex">
        <div className="flex w-[4.25rem] flex-col items-center gap-1 py-3" style={{ background: "var(--rail-bg)" }}>
          <Tooltip label={siteName}>
            <Link href="/dashboard" aria-label={siteName} className="mb-3 grid place-items-center rounded-[12px] p-0.5 ring-1 ring-white/10 transition hover:ring-white/30"><TenantMark className="h-10 w-10" /></Link>
          </Tooltip>
          <nav className="flex flex-1 flex-col items-center gap-1 overflow-y-auto [scrollbar-width:none]">{sections.map(railBtn)}</nav>
          <div className="flex flex-col items-center gap-1 pt-2">
            <Tooltip label={<span>{t("nav.search")} <span className="opacity-60">{isMac ? "⌘" : "Ctrl"} K</span></span>}>
              <button type="button" onClick={() => setCmd(true)} aria-label={t("nav.search")} className="grid h-11 w-11 place-items-center rounded-[13px] text-[var(--rail-fg)] transition hover:bg-white/[.07] hover:text-[var(--rail-fg-hover)]"><Icon name="search" className="h-[19px] w-[19px]" /></button>
            </Tooltip>
            <Bell dark />
            {!bleed && (
              <Tooltip label={pinned ? t("nav.collapse") : t("nav.expand")}>
                <button type="button" onClick={() => setFlag(PANEL_KEY, !pinned)} aria-label={pinned ? t("nav.collapse") : t("nav.expand")} className="grid h-11 w-11 place-items-center rounded-[13px] text-[var(--rail-fg)] transition hover:bg-white/[.07] hover:text-[var(--rail-fg-hover)]">
                  <Icon name="layout" className={`h-[18px] w-[18px] ${(pinned ? locale === "ar" : locale !== "ar") ? "-scale-x-100" : ""}`} />
                </button>
              </Tooltip>
            )}
            <AccountMenu name={fullName} roles={me.roles.map((r) => t(`role.${r}`)).join(", ")} onLogout={logout} />
          </div>
        </div>

        <AnimatePresence initial={false}>
          {showPanel && (
            <motion.div key="panel" initial={{ width: 0, opacity: 0 }} animate={{ width: 248, opacity: 1 }} exit={{ width: 0, opacity: 0 }} transition={{ duration: reduce ? 0 : 0.26, ease: [0.2, 0.8, 0.2, 1] }}
              className="flex h-full flex-col overflow-hidden border-e border-line bg-[var(--panel-bg)]">
              <div className="w-[248px] shrink-0 px-3 pt-3"><WorkspaceCard role={role} siteName={siteName} /></div>
              <div className="min-h-0 w-[248px] flex-1">{panel(activeRail ?? "", false)}</div>
              {!whiteLabel && <div className="w-[248px] shrink-0 border-t border-line px-4 py-2.5"><PoweredBy label={t("brand.powered")} name={t("brand.name")} /></div>}
            </motion.div>
          )}
        </AnimatePresence>

        {/* rail-only mode: a section's items open next to the rail on hover / focus */}
        <AnimatePresence>
          {!showPanel && flyout && (
            <motion.div key={flyout} onMouseEnter={holdFly} onMouseLeave={closeFly} initial={reduce ? { opacity: 0 } : { opacity: 0, x: locale === "ar" ? 8 : -8 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0 }}
              transition={{ duration: 0.16 }} className="absolute inset-y-0 start-[4.25rem] z-40 w-[248px] border-e border-line bg-[var(--panel-bg)] shadow-[var(--shadow-float)]">
              {panel(panelKey ?? "", true)}
            </motion.div>
          )}
        </AnimatePresence>
      </aside>

      {/* ---------- phone top bar ---------- */}
      <header className="glass sticky top-0 z-30 flex items-center justify-between gap-2 px-2.5 py-2 hairline-b print:hidden lg:hidden">
        <Link href="/dashboard" className="flex min-w-0 items-center gap-2 ps-1"><TenantMark className="h-8 w-8" /><span className="truncate font-display text-[15px] font-semibold text-ink">{current ? t(current.label) : siteName}</span></Link>
        <span className="flex items-center">
          <button onClick={() => setCmd(true)} aria-label={t("nav.search")} className="grid h-11 w-11 place-items-center rounded-[10px] text-muted"><Icon name="search" className="h-[19px] w-[19px]" /></button>
          <Bell />
        </span>
      </header>

      <div className={`flex min-w-0 flex-1 flex-col ${bleed ? "min-h-0 overflow-hidden" : ""}`}>
        {can("billing.manage") && <SubscriptionBar status={billing.data} />}
        {/* ---------- desktop header ---------- */}
        {!bleed && (
          <header className="glass sticky top-0 z-20 hidden h-14 items-center justify-between gap-4 px-8 hairline-b print:hidden lg:flex">
            <div className="flex min-w-0 items-center gap-2 text-[13px]">
              {activeRail && rails.find((r) => r.key === activeRail) && current && pathOf(current) !== "/dashboard" && <><span className="text-faint">{t(rails.find((r) => r.key === activeRail)!.label)}</span><Icon name="chevron" className="h-3.5 w-3.5 text-faint flip-rtl" /></>}
              <span className="truncate font-semibold text-ink">{current ? t(current.label) : siteName}</span>
            </div>
            <div className="flex items-center gap-1">
              <button type="button" onClick={() => setCmd(true)} className="me-2 flex h-9 w-60 items-center gap-2 rounded-[10px] bg-surface px-3 text-[13px] text-faint shadow-[0_0_0_1px_var(--line-strong)] transition hover:text-muted">
                <Icon name="search" className="h-4 w-4" /><span className="min-w-0 flex-1 truncate text-start">{t("nav.search")}</span><span className="kbd">{isMac ? "⌘" : "Ctrl"} K</span>
              </button>
              <a href={`/api/lang?l=${locale === "ar" ? "en" : "ar"}`} className="inline-flex h-9 items-center gap-1.5 rounded-[10px] px-2.5 text-xs font-semibold text-muted transition hover:bg-[color-mix(in_srgb,var(--ink)_6%,transparent)] hover:text-ink"><Icon name="globe" className="h-4 w-4" />{locale === "ar" ? "EN" : "عربي"}</a>
              <ThemeToggle />
            </div>
          </header>
        )}
        <motion.main key={bleed ? "bleed" : path} initial={reduce || bleed ? false : { opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.26, ease: [0.2, 0.8, 0.2, 1] }}
          className={bleed ? "relative flex min-h-0 w-full min-w-0 flex-1 flex-col overflow-hidden pb-[var(--tabbar-h)] lg:pb-0" : "mx-auto w-full min-w-0 max-w-[90rem] flex-1 overflow-x-clip p-4 pb-24 sm:p-6 lg:px-10 lg:py-8 print:p-0"}>
          <SwRegister />
          {!online && <div className="mb-4 flex shrink-0 items-center gap-2 rounded-[12px] bg-amber-50 px-4 py-2.5 text-sm text-amber-900 ring-1 ring-inset ring-amber-600/20 print:hidden"><Icon name="alert" className="h-4 w-4" />{t("offline.dashboard")}</div>}
          {disabled && !canPay
            ? <div className="mx-auto mt-10 max-w-md rounded-[16px] bg-surface p-8 text-center shadow-card"><span className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-[14px] bg-red-50 text-red-600"><Icon name="alert" /></span><h1 className="font-display text-lg font-semibold text-ink">{t("sub.disabledTitle")}</h1><p className="mt-2 text-sm text-muted">{t("sub.ownerOnly")}</p></div>
            : blocked
              ? <div className="mx-auto mt-10 max-w-md rounded-[16px] bg-surface p-8 text-center shadow-card"><span className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-[14px] bg-slate-100 text-slate-600"><Icon name="shield" /></span><h1 className="font-display text-lg font-semibold text-ink">{t("dash.noAccess")}</h1><p className="mt-2 text-sm text-muted">{t("dash.noAccessHint")}</p></div>
              : <NavTitleContext.Provider value={current ? t(current.label) : null}>{children}</NavTitleContext.Provider>}
        </motion.main>
      </div>

      {/* ---------- phone tab bar ---------- */}
      <nav className="glass fixed inset-x-0 bottom-0 z-30 flex justify-around px-1 pb-[max(0.35rem,env(safe-area-inset-bottom))] pt-1 print:hidden lg:hidden" style={{ boxShadow: "inset 0 1px 0 var(--line)" }}>
        {quick.map((n) => {
          const on = n === current, count = badge(n) || (n.rail ? sectionBadge(n.rail) : 0);
          return (
            <Link key={n.href} href={n.href} className={`relative flex min-w-0 flex-1 flex-col items-center gap-0.5 rounded-xl py-1 text-[10.5px] font-medium transition active:scale-95 ${on ? "text-ink" : "text-faint"}`}>
              <span className="relative grid h-8 w-14 place-items-center">
                {on && <motion.span layoutId="quick-active" transition={reduce ? { duration: 0 } : { type: "spring", stiffness: 500, damping: 38 }} className="absolute inset-0 rounded-full bg-brand-soft-2" />}
                <span className={`relative ${on ? "text-brand" : ""}`}><NavIcon icon={n.icon} className="h-5 w-5" /></span>
                {count > 0 && <span className="absolute end-2 top-0 h-2 w-2 rounded-full bg-red-500 ring-2 ring-surface" />}
              </span>
              <span className="max-w-full truncate">{t(n.label)}</span>
            </Link>
          );
        })}
        <button onClick={() => setMore(true)} className="flex min-w-0 flex-1 flex-col items-center gap-0.5 py-1 text-[10.5px] font-medium text-faint active:scale-95">
          <span className="grid h-8 w-14 place-items-center"><Icon name="menu" className="h-5 w-5" /></span>
          <span>{t("common.more")}</span>
        </button>
      </nav>

      {/* ---------- phone: the whole menu ---------- */}
      <Sheet open={more} onClose={() => setMore(false)} title={siteName} label={t("common.more")}>
        <div className="-mt-1 mb-4"><WorkspaceCard role={role} siteName={siteName} /></div>
        <button type="button" onClick={() => { setMore(false); setCmd(true); }} className="mb-5 flex h-11 w-full items-center gap-2 rounded-[12px] bg-canvas px-3.5 text-[14px] text-faint ring-1 ring-line"><Icon name="search" className="h-4 w-4" />{t("nav.search")}</button>
        <div className="space-y-5">
          {sections.map((r) => (
            <section key={r.key}>
              <p className="mb-2 flex items-center gap-2 px-1 text-[12px] font-semibold uppercase tracking-wide text-faint"><Icon name={r.icon} className="h-3.5 w-3.5" />{t(r.label)}</p>
              <div className="grid grid-cols-2 gap-1.5">
                {itemsOf(r.key).map((n) => {
                  const on = n === current, count = badge(n);
                  return (
                    <Link key={n.href} href={n.href} onClick={() => setMore(false)} className={`relative flex items-center gap-2 rounded-[12px] px-3 py-2.5 text-[13.5px] font-medium ring-1 transition active:scale-[.98] ${on ? "bg-brand-soft text-brand ring-brand/20" : "bg-surface text-ink-2 ring-line"}`}>
                      <NavIcon icon={n.icon} className="h-4 w-4 shrink-0" /><span className="min-w-0 flex-1 truncate">{t(n.label)}</span>
                      {count > 0 && <span className="rounded-full bg-red-500 px-1.5 text-[10px] font-bold text-white">{count}</span>}
                    </Link>
                  );
                })}
              </div>
            </section>
          ))}
        </div>
        <div className="mt-6 flex items-center justify-between gap-2 border-t border-line pt-4">
          <span className="flex min-w-0 items-center gap-2.5"><Avatar name={fullName} className="h-9 w-9 text-xs" /><span className="min-w-0"><span className="block truncate text-[13.5px] font-semibold text-ink">{fullName}</span><span className="block truncate text-[11.5px] text-faint">{me.roles.map((r) => t(`role.${r}`)).join(", ")}</span></span></span>
          <span className="flex items-center gap-1">
            <a href={`/api/lang?l=${locale === "ar" ? "en" : "ar"}`} className="inline-flex h-9 items-center gap-1 rounded-[10px] px-2.5 text-xs font-semibold text-muted ring-1 ring-line">{locale === "ar" ? "EN" : "عربي"}</a>
            <ThemeToggle />
            <button type="button" onClick={logout} className="grid h-9 w-9 place-items-center rounded-[10px] text-muted ring-1 ring-line" aria-label={t("nav.logout")}><Icon name="logout" className="h-4 w-4 flip-rtl" /></button>
          </span>
        </div>
      </Sheet>
    </div>
  );
}

/** The business at the top of the panel: name, «view site», and the live status of the website and (for shops) the AI seller. */
function WorkspaceCard({ role, siteName }: { role: string; siteName: string }) {
  const { t } = useI18n();
  const { canSee } = useMe();
  const ai = useApi<{ enabled: boolean; pointsMinor?: number }>(role === "store" && canSee("ai") ? "store/ai/settings" : null);
  const aiOn = !!ai.data?.enabled && (ai.data.pointsMinor ?? 1) >= 100;
  return (
    <div className="rounded-[14px] bg-surface p-3 ring-1 ring-line">
      <div className="flex items-center gap-2.5">
        <TenantMark className="h-9 w-9" />
        <div className="min-w-0 flex-1">
          <p className="truncate text-[13.5px] font-semibold text-ink">{siteName}</p>
          <a href="/" target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 text-[11.5px] text-faint transition hover:text-brand">{t("dash.viewSite")}<Icon name="external" className="h-3 w-3" /></a>
        </div>
      </div>
      <div className="mt-2.5 flex flex-wrap gap-1.5">
        <span className="inline-flex items-center gap-1.5 rounded-full bg-emerald-50 px-2 py-0.5 text-[11px] font-semibold text-emerald-700"><span className="live-dot h-1.5 w-1.5 rounded-full bg-emerald-500" />{t("dash.siteLive")}</span>
        {role === "store" && ai.data && (
          <Link href="/dashboard/ai?tab=settings" className={`inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-[11px] font-semibold ${aiOn ? "bg-violet-50 text-violet-700" : "bg-slate-100 text-slate-600"}`}>
            <Icon name="sparkles" className="h-3 w-3" />{aiOn ? t("dash.aiOn") : t("dash.aiOff")}
          </Link>
        )}
      </div>
    </div>
  );
}

/** Notifications: the bell with the unread count; a popover with the latest five and «mark all read». */
function Bell({ dark }: { dark?: boolean }) {
  const { t, locale } = useI18n();
  const ref = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false);
  const unread = useApi<{ count: number }>("notifications/unread-count");
  const latest = useApi<{ data: { id: string; title: string; body?: string; readAt?: string; createdAt: string }[] }>(open ? "notifications?pageSize=6" : null);
  useEffect(() => {
    const id = setInterval(() => { if (!document.hidden) void unread.reload(); }, 45_000);
    return () => clearInterval(id);
  }, [unread]);
  const count = unread.data?.count ?? 0;
  const close = useCallback(() => setOpen(false), []);
  const btn = (
    <button ref={ref} type="button" onClick={() => setOpen((v) => !v)} aria-label={t("nav.notifications")}
      className={dark ? "relative grid h-11 w-11 place-items-center rounded-[13px] text-[var(--rail-fg)] transition hover:bg-white/[.07] hover:text-[var(--rail-fg-hover)]" : "relative grid h-11 w-11 place-items-center rounded-[10px] text-muted"}>
      <Icon name="bell" className="h-[19px] w-[19px]" />
      {count > 0 && <span className={`absolute end-1.5 top-1.5 flex h-4 min-w-4 animate-pop items-center justify-center rounded-full bg-red-500 px-1 text-[9.5px] font-bold text-white ring-2 ${dark ? "ring-[var(--rail-bg)]" : "ring-surface"}`}>{count > 99 ? "99+" : count}</span>}
    </button>
  );
  return (
    <>
      {dark ? <Tooltip label={t("nav.notifications")}>{btn}</Tooltip> : btn}
      <Popover open={open} onClose={close} anchor={ref} placement={dark ? "right" : "bottom-end"} width={340} label={t("nav.notifications")}>
        <div className="flex items-center justify-between px-2.5 pb-2 pt-1.5">
          <p className="text-[13.5px] font-semibold">{t("nav.notifications")}</p>
          {count > 0 && <button type="button" onClick={async () => { await api("notifications/read-all", { body: {} }).catch(() => undefined); await unread.reload(); await latest.reload(); }} className="text-[12px] font-semibold text-brand">{t("notif.readAll")}</button>}
        </div>
        <div className="max-h-[22rem] overflow-y-auto">
          {latest.data?.data.length === 0 && <p className="px-3 py-6 text-center text-[13px] text-faint">{t("notif.none")}</p>}
          {!latest.data && <div className="space-y-2 p-2">{[0, 1, 2].map((i) => <div key={i} className="skeleton h-12 rounded-[10px]" />)}</div>}
          {latest.data?.data.map((n) => (
            <div key={n.id} className="flex gap-2.5 rounded-[10px] px-2.5 py-2 hover:bg-[color-mix(in_srgb,var(--ink)_4%,transparent)]">
              <span className={`mt-1.5 h-2 w-2 shrink-0 rounded-full ${n.readAt ? "bg-transparent" : "bg-brand"}`} />
              <span className="min-w-0 flex-1"><span className="block truncate text-[13px] font-semibold text-ink">{n.title}</span>{n.body && <span className="line-clamp-2 block text-[12px] text-muted">{n.body}</span>}<span className="text-[11px] text-faint">{dateTime(n.createdAt, locale)}</span></span>
            </div>
          ))}
        </div>
        <Link href="/dashboard/notifications" onClick={close} className="mt-1 block rounded-[10px] px-2.5 py-2 text-center text-[12.5px] font-semibold text-muted hover:bg-[color-mix(in_srgb,var(--ink)_4%,transparent)] hover:text-ink">{t("notif.all")}</Link>
      </Popover>
    </>
  );
}

/** The avatar at the bottom of the rail: who is signed in, theme, language and sign out. */
function AccountMenu({ name, roles, onLogout }: { name: string; roles: string; onLogout: () => void }) {
  const { t, locale } = useI18n();
  const theme = useTheme();
  return (
    <Menu placement="right" label={name} items={[
      { label: t("theme.light"), icon: "sun", checked: theme === "light", onSelect: () => setTheme("light") },
      { label: t("theme.dark"), icon: "moon", checked: theme === "dark", onSelect: () => setTheme("dark") },
      { label: t("theme.system"), icon: "monitor", checked: theme === "system", onSelect: () => setTheme("system") },
      "divider",
      { label: locale === "ar" ? "English" : "العربية", icon: "globe", onSelect: () => { window.location.href = `/api/lang?l=${locale === "ar" ? "en" : "ar"}`; } },
      { label: t("nav.notifications"), icon: "bell", onSelect: () => { window.location.href = "/dashboard/notifications"; } },
      "divider",
      { label: t("nav.logout"), icon: "logout", danger: true, onSelect: onLogout },
    ]} trigger={({ toggle }) => (
      <button type="button" onClick={toggle} aria-label={name} title={`${name} · ${roles}`} className="mt-1 rounded-full ring-2 ring-white/15 transition hover:ring-white/40">
        <Avatar name={name} className="h-9 w-9 text-xs" />
      </button>
    )} />
  );
}
