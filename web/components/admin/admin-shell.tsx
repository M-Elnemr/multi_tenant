"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { useApi, useMe } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Icon, type IconName } from "../icons";
import { LogoutButton } from "../logout-button";
import { ThemeToggle, useWorkspaceTheme } from "../theme-toggle";
import { Avatar } from "../ui";
import { LogoMark } from "../brand";
import { localPhone } from "@/lib/phone";

type Item = { href: string; label: string; icon: IconName; badge?: number };
type Group = { key: string; label: string; items: Item[] };

/** The platform owner's console: its own full-screen layout (grouped sidebar on desktop, drawer + bottom bar on phones), not the marketing site's header. */
export function AdminShell({ children }: { children: React.ReactNode }) {
  useWorkspaceTheme();
  const { t, locale } = useI18n();
  const path = usePathname();
  const { me } = useMe();
  const [open, setOpen] = useState(false);
  const overview = useApi<{ newPayRequests: number }>("platform/overview");
  const unread = useApi<{ count: number }>("notifications/unread-count");
  const { reload: reloadOverview } = overview;
  const { reload: reloadUnread } = unread;
  useEffect(() => { const id = setInterval(() => { void reloadOverview(); void reloadUnread(); }, 30_000); return () => clearInterval(id); }, [reloadOverview, reloadUnread]);
  useEffect(() => {
    if (!open) return;
    const h = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [open]);

  const groups: Group[] = [
    { key: "operate", label: "admin.nav.operate", items: [
      { href: "/admin", label: "admin.overview", icon: "dashboard" },
      { href: "/admin/tenants", label: "admin.tenants", icon: "home" },
      { href: "/admin/pay-requests", label: "admin.payRequests", icon: "receipt", badge: overview.data?.newPayRequests },
      { href: "/admin/wallet", label: "wa.adminTitle", icon: "wallet" },
      { href: "/admin/domains", label: "admin.domains", icon: "globe" },
      { href: "/admin/notifications", label: "nav.notifications", icon: "bell", badge: unread.data?.count },
    ] },
    { key: "growth", label: "admin.nav.growth", items: [
      { href: "/admin/affiliate", label: "admin.affiliate", icon: "users" },
      { href: "/admin/visitors", label: "admin.visitors", icon: "trending" },
      { href: "/admin/taxonomy", label: "admin.taxonomy", icon: "tag" },
    ] },
    { key: "ai", label: "admin.nav.ai", items: [
      { href: "/admin/ai", label: "admin.ai", icon: "sparkles" },
      { href: "/admin/ai-config", label: "aic.title", icon: "sliders" },
      { href: "/admin/ai-usage", label: "aiu.title", icon: "chart" },
      { href: "/admin/ai-health", label: "aih.title", icon: "pulse" },
    ] },
    { key: "platform", label: "admin.nav.platform", items: [
      { href: "/admin/whatsapp", label: "pwa.title", icon: "link" },
      { href: "/admin/security", label: "sec.title", icon: "lock" },
      { href: "/admin/audit", label: "admin.audit", icon: "shield" },
    ] },
  ];
  const items = groups.flatMap((g) => g.items);
  const active = (i: Item) => (i.href === "/admin" ? path === "/admin" : path === i.href || path.startsWith(i.href + "/"));
  const current = items.find(active);
  const currentGroup = groups.find((g) => g.items.some(active));
  const name = me ? `${me.firstName} ${me.lastName}`.trim() : "";
  const pill = (n: number, on?: boolean) => <span className={`tnum rounded-full px-1.5 text-[11px] font-bold ${on ? "bg-surface text-ink" : "bg-red-600 text-white"}`}>{n > 99 ? "99+" : n}</span>;

  const link = (i: Item) => (
    <Link key={i.href} href={i.href} onClick={() => setOpen(false)} aria-current={active(i) ? "page" : undefined}
      className={`group relative flex items-center gap-2.5 rounded-[10px] px-2.5 py-2 text-[13px] font-semibold transition ${active(i) ? "bg-surface text-ink shadow-[0_0_0_1px_var(--line),0_1px_2px_rgb(16_18_24/.06)]" : "text-muted hover:bg-[color-mix(in_srgb,var(--ink)_5%,transparent)] hover:text-ink"}`}>
      {active(i) && <span className="absolute inset-y-2 -start-0.5 w-[3px] rounded-full bg-brand" aria-hidden />}
      <Icon name={i.icon} className={`h-[17px] w-[17px] shrink-0 ${active(i) ? "text-brand" : ""}`} />
      <span className="flex-1 truncate">{t(i.label)}</span>
      {!!i.badge && pill(i.badge)}
    </Link>
  );
  const nav = (
    <nav className="-me-1 flex-1 space-y-4 overflow-y-auto pe-1" aria-label={t("admin.console")}>
      {groups.map((g) => (
        <div key={g.key}>
          <p className="eyebrow mb-1 px-2.5">{t(g.label)}</p>
          <div className="space-y-0.5">{g.items.map(link)}</div>
        </div>
      ))}
    </nav>
  );

  const brand = (
    <div className="flex items-center gap-2.5">
      <LogoMark className="h-9 w-9" />
      <div className="min-w-0"><p className="truncate font-display text-[14.5px] font-semibold leading-tight text-ink">{t("brand.name")}</p><p className="text-[11px] font-semibold text-brand">{t("admin.console")}</p></div>
    </div>
  );
  const account = (
    <div className="space-y-2 border-t border-line pt-3">
      <div className="flex items-center gap-2.5"><Avatar name={name || "A"} className="h-8 w-8 text-xs" /><div className="min-w-0"><p className="truncate text-[13px] font-semibold text-ink">{name}</p><p className="truncate text-[11.5px] text-muted" dir="ltr">{localPhone(me?.phone)}</p></div></div>
      <div className="flex items-center justify-between">
        <LogoutButton />
        <div className="flex items-center gap-1">
          <ThemeToggle />
          <a href={`/api/lang?l=${locale === "ar" ? "en" : "ar"}`} className="inline-flex h-9 items-center gap-1.5 rounded-[10px] px-2.5 text-xs font-semibold text-muted transition hover:bg-[color-mix(in_srgb,var(--ink)_6%,transparent)] hover:text-ink"><Icon name="globe" className="h-4 w-4" />{locale === "ar" ? "EN" : "عربي"}</a>
        </div>
      </div>
    </div>
  );
  const quick = items.slice(0, 3);

  return (
    <div className="fixed inset-0 z-[60] flex overflow-hidden bg-canvas text-ink">
      {/* desktop sidebar */}
      <aside className="sidebar-bg hidden w-60 shrink-0 flex-col gap-4 border-e border-line p-3.5 lg:flex">
        <div className="px-1 pt-1">{brand}</div>
        {nav}
        {account}
      </aside>

      {/* phone drawer */}
      <div onClick={() => setOpen(false)} className={`absolute inset-0 z-10 bg-[rgb(10_12_16/.45)] backdrop-blur-[2px] transition-opacity lg:hidden ${open ? "opacity-100" : "pointer-events-none opacity-0"}`} aria-hidden />
      <aside inert={!open} className={`sidebar-bg absolute inset-y-0 start-0 z-20 flex w-[19rem] max-w-[86vw] flex-col gap-4 p-3.5 shadow-2xl transition-transform duration-300 lg:hidden ${open ? "translate-x-0" : "ltr:-translate-x-full rtl:translate-x-full"}`}>
        <div className="flex items-center justify-between px-1 pt-1">{brand}<button onClick={() => setOpen(false)} aria-label={t("common.close")} className="grid h-10 w-10 place-items-center rounded-[10px] text-muted hover:bg-surface-2"><Icon name="close" /></button></div>
        {nav}
        {account}
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        {/* top bar: section / page on desktop, menu + title on phones */}
        <header className="glass z-10 flex h-12 items-center justify-between gap-3 border-b border-line px-3 sm:px-5 lg:px-8">
          <div className="flex min-w-0 items-center gap-2">
            <button onClick={() => setOpen(true)} aria-label={t("common.more")} className="grid h-10 w-10 place-items-center rounded-[10px] text-ink hover:bg-surface-2 lg:hidden"><Icon name="menu" className="h-5 w-5" /></button>
            <div className="flex min-w-0 items-center gap-1.5 text-[13px]">
              {currentGroup && <span className="hidden truncate text-muted sm:inline">{t(currentGroup.label)}</span>}
              {currentGroup && <Icon name="chevron" className="hidden h-3.5 w-3.5 text-faint flip-rtl sm:block" />}
              <span className="truncate font-semibold text-ink">{current ? t(current.label) : t("admin.console")}</span>
            </div>
          </div>
          <div className="flex items-center gap-1">
            <span className="lg:hidden"><ThemeToggle /></span>
            <Link href="/admin/notifications" className="relative grid h-9 w-9 place-items-center rounded-[10px] text-muted transition hover:bg-surface-2 hover:text-ink" aria-label={t("nav.notifications")}>
              <Icon name="bell" className="h-[18px] w-[18px]" />
              {!!unread.data?.count && <span className="tnum absolute end-0 top-0 flex h-4 min-w-4 items-center justify-center rounded-full bg-red-600 px-1 text-[10px] font-bold text-white">{unread.data.count}</span>}
            </Link>
          </div>
        </header>
        <main className="flex-1 overflow-y-auto p-3.5 pb-24 sm:p-5 sm:pb-24 lg:p-8 lg:pb-8">
          <div className="mx-auto max-w-[1360px]">{children}</div>
        </main>
        {/* phone bottom bar */}
        <nav className="glass flex justify-around border-t border-line px-1 pb-[max(0.35rem,env(safe-area-inset-bottom))] pt-1.5 lg:hidden">
          {quick.map((i) => (
            <Link key={i.href} href={i.href} aria-current={active(i) ? "page" : undefined} className={`relative flex min-h-12 min-w-0 flex-1 flex-col items-center justify-center gap-0.5 rounded-[10px] py-1 text-[11px] font-semibold transition active:scale-95 ${active(i) ? "text-brand" : "text-muted"}`}>
              <span className="relative"><Icon name={i.icon} className="h-5 w-5" />{!!i.badge && <span className="tnum absolute -end-2 -top-1.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-red-600 px-1 text-[10px] font-bold text-white">{i.badge}</span>}</span>
              <span className="truncate">{t(i.label)}</span>
            </Link>
          ))}
          <button onClick={() => setOpen(true)} className="relative flex min-h-12 min-w-0 flex-1 flex-col items-center justify-center gap-0.5 rounded-[10px] py-1 text-[11px] font-semibold text-muted transition active:scale-95"><Icon name="menu" className="h-5 w-5" /><span className="truncate">{t("common.more")}</span></button>
        </nav>
      </div>
    </div>
  );
}
