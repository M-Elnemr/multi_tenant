"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState, useSyncExternalStore } from "react";
import { motion, useReducedMotion } from "motion/react";
import { useApi, useMe } from "../hooks";
import { useI18n } from "../i18n-provider";
import { LogoutButton } from "../logout-button";
import { PoweredBy } from "../brand";
import { Icon, hasIcon } from "../icons";
import { Avatar, Loading } from "../ui";
import { fileUrl } from "@/lib/media";

/** `group` is an i18n key (navgrp.*) for the section heading the item sits under; consecutive items with the same group share one heading. */
export type NavItem = { href: string; label: string; perm?: string | string[]; icon?: string; group?: string };

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
const listeners = new Set<() => void>();
const readCollapsed = () => { try { return localStorage.getItem(COLLAPSE_KEY) === "1"; } catch { return false; } };   // storage can be blocked: stay expanded
const subscribe = (cb: () => void) => { listeners.add(cb); return () => { listeners.delete(cb); }; };

/** Sidebar dashboard used by shop owners, doctors and staff. Items are hidden without the permission (the backend enforces it regardless). */
export function DashboardShell({ nav, children }: { nav: NavItem[]; children: React.ReactNode }) {
  const { t, siteName, locale } = useI18n();
  const path = usePathname();
  const router = useRouter();
  const { me, ready, can } = useMe();
  const unread = useApi<{ count: number }>("notifications/unread-count");
  const reduce = useReducedMotion();
  const [open, setOpen] = useState(false);
  const collapsed = useSyncExternalStore(subscribe, readCollapsed, () => false);
  const toggleCollapsed = () => { try { localStorage.setItem(COLLAPSE_KEY, collapsed ? "0" : "1"); } catch { /* ignore */ } listeners.forEach((l) => l()); };
  useEffect(() => { if (ready && !me) router.replace(`/login?next=${encodeURIComponent(path)}`); }, [ready, me, path, router]);
  useEffect(() => {
    if (!open) return;
    const h = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [open]);
  if (!ready || !me) return <div className="p-6"><Loading /></div>;

  const allowed = nav.filter((n) => !n.perm || (Array.isArray(n.perm) ? n.perm.some(can) : can(n.perm)));
  const isActive = (n: NavItem) => (n.href === "/dashboard" ? path === n.href : path.startsWith(n.href));
  const current = allowed.find(isActive);
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
      <Link key={n.href} href={n.href} onClick={() => setOpen(false)} title={mini ? t(n.label) : undefined} className={`group relative flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-semibold transition-colors duration-200 ${mini ? "lg:justify-center lg:px-0" : ""} ${active ? "text-white" : "text-slate-600 hover:bg-brand-soft hover:text-brand-dark"}`}>
        {active && (reduce ? <span className="absolute inset-0 rounded-xl bg-brand-gradient shadow-brand" /> : <motion.span layoutId="nav-active" transition={{ type: "spring", stiffness: 420, damping: 36 }} className="absolute inset-0 rounded-xl bg-brand-gradient shadow-brand" />)}
        <span className={`relative transition-transform duration-200 ${active ? "" : "group-hover:scale-110"}`}><NavIcon icon={n.icon} /></span>
        <span className={`relative truncate ${mini ? "lg:hidden" : ""}`}>{t(n.label)}</span>
      </Link>
    );
  };
  const items: React.ReactNode[] = [];
  let lastGroup: string | undefined;
  allowed.forEach((n) => {
    if (n.group && n.group !== lastGroup) items.push(mini ? <div key={`g-${n.group}-${n.href}`} className="mx-3 my-2 hidden border-t border-slate-200/70 lg:block" /> : null, <div key={`h-${n.group}-${n.href}`} className={`nav-section ${mini ? "lg:hidden" : ""}`}>{t(n.group)}</div>);
    lastGroup = n.group;
    items.push(link(n));
  });
  const quick = allowed.slice(0, 4);

  return (
    <div className="min-h-screen lg:flex print:block">
      {/* phone top bar */}
      <header className="glass sticky top-0 z-30 flex items-center justify-between border-b border-slate-200/70 px-3 py-2.5 print:hidden lg:hidden">
        <button onClick={() => setOpen(true)} aria-label="Menu" className="rounded-xl p-2 text-slate-700 transition hover:bg-brand-soft active:scale-95"><Icon name="menu" className="h-6 w-6" /></button>
        <span className="flex min-w-0 items-center gap-2 font-bold"><TenantMark className="h-7 w-7" /><span className="truncate text-ink">{current ? t(current.label) : siteName}</span></span>
        {bell("")}
      </header>

      <div onClick={() => setOpen(false)} className={`fixed inset-0 z-40 bg-slate-900/40 backdrop-blur-sm transition-opacity duration-300 lg:hidden ${open ? "opacity-100" : "pointer-events-none opacity-0"}`} aria-hidden />

      <aside className={`sidebar-bg fixed inset-y-0 start-0 z-50 flex w-72 flex-col border-e border-slate-200/70 p-4 shadow-2xl transition-[transform,width] duration-300 ease-out print:hidden lg:sticky lg:top-0 lg:z-20 lg:h-screen lg:shrink-0 lg:translate-x-0! lg:shadow-none ${mini ? "lg:w-[4.75rem] lg:px-3" : "lg:w-64"} ${open ? "translate-x-0" : "ltr:-translate-x-full rtl:translate-x-full"}`}>
        <div className={`mb-4 flex items-center justify-between gap-2 ${mini ? "lg:flex-col" : ""}`}>
          <Link href="/" className="flex min-w-0 items-center gap-3">
            <TenantMark />
            <span className={`min-w-0 ${mini ? "lg:hidden" : ""}`}><span className="block truncate text-[15px] font-bold leading-tight text-ink">{siteName}</span><span className="block truncate text-[11px] text-slate-500">{t("dash.controlPanel")}</span></span>
          </Link>
          <button onClick={() => setOpen(false)} aria-label="Close" className="rounded-xl p-2 text-slate-500 hover:bg-slate-100 lg:hidden"><Icon name="close" /></button>
        </div>
        <nav className="-mx-1 flex-1 space-y-0.5 overflow-y-auto px-1">{items}</nav>
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
          <div className={mini ? "lg:hidden" : ""}><PoweredBy label={t("brand.powered")} name={t("brand.name")} /></div>
        </div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        {/* desktop top bar */}
        <header className="glass sticky top-0 z-20 hidden items-center justify-between gap-4 border-b border-slate-200/60 px-8 py-3 print:hidden lg:flex">
          <p className="text-sm text-slate-500">{hour < 12 ? t("dash.morning") : t("dash.hello")}, <b className="font-semibold text-ink">{me.firstName}</b></p>
          <div className="flex items-center gap-1">{langLink}{bell("")}</div>
        </header>
        <motion.main key={path} initial={reduce ? false : { opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.35, ease: [0.22, 1, 0.36, 1] }} className="min-w-0 flex-1 p-4 pb-24 sm:p-6 lg:p-8 lg:pb-8 print:p-0">{children}</motion.main>
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
