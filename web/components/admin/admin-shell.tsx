"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { useApi, useMe } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Icon, type IconName } from "../icons";
import { LogoutButton } from "../logout-button";
import { Avatar } from "../ui";
import { LogoMark } from "../brand";
import { localPhone } from "@/lib/phone";

type Item = { href: string; label: string; icon: IconName; badge?: number };

/** The platform owner's console: its own full-screen layout (sidebar on desktop, drawer + bottom bar on phones), not the marketing site's header. */
export function AdminShell({ children }: { children: React.ReactNode }) {
  const { t, locale } = useI18n();
  const path = usePathname();
  const { me } = useMe();
  const [open, setOpen] = useState(false);
  const overview = useApi<{ newPayRequests: number }>("platform/overview");
  const unread = useApi<{ count: number }>("notifications/unread-count");
  const { reload: reloadOverview } = overview;
  const { reload: reloadUnread } = unread;
  useEffect(() => { const id = setInterval(() => { void reloadOverview(); void reloadUnread(); }, 30_000); return () => clearInterval(id); }, [reloadOverview, reloadUnread]);

  const items: Item[] = [
    { href: "/admin", label: "admin.overview", icon: "dashboard" },
    { href: "/admin/tenants", label: "admin.tenants", icon: "home" },
    { href: "/admin/pay-requests", label: "admin.payRequests", icon: "wallet", badge: overview.data?.newPayRequests },
    { href: "/admin/notifications", label: "nav.notifications", icon: "bell", badge: unread.data?.count },
    { href: "/admin/taxonomy", label: "admin.taxonomy", icon: "tag" },
    { href: "/admin/visitors", label: "admin.visitors", icon: "users" },
    { href: "/admin/ai", label: "admin.ai", icon: "sparkles" },
    { href: "/admin/wallet", label: "wa.adminTitle", icon: "wallet" },
    { href: "/admin/audit", label: "admin.audit", icon: "shield" },
  ];
  const active = (i: Item) => (i.href === "/admin" ? path === "/admin" : path.startsWith(i.href));
  const current = items.find(active);
  const name = me ? `${me.firstName} ${me.lastName}`.trim() : "";

  const link = (i: Item, quick = false) => (
    <Link key={i.href} href={i.href} onClick={() => setOpen(false)} className={quick
      ? `relative flex min-w-0 flex-1 flex-col items-center gap-0.5 min-h-12 justify-center rounded-xl py-1.5 text-[11px] font-semibold transition active:scale-95 ${active(i) ? "text-brand" : "text-slate-500"}`
      : `group relative flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-semibold transition ${active(i) ? "bg-brand-gradient text-white shadow-brand" : "text-slate-600 hover:bg-brand-soft hover:text-brand-dark"}`}>
      <span className="relative"><Icon name={i.icon} className={quick ? "h-5 w-5" : "h-[19px] w-[19px]"} />
        {quick && !!i.badge && <span className="absolute -end-2 -top-1.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-red-600 px-1 text-[10px] font-bold text-white">{i.badge}</span>}
      </span>
      <span className={quick ? "truncate" : "flex-1 truncate"}>{t(i.label)}</span>
      {!quick && !!i.badge && <span className={`rounded-full px-2 py-0.5 text-[11px] font-bold ${active(i) ? "bg-white/25 text-white" : "bg-red-600 text-white"}`}>{i.badge > 99 ? "99+" : i.badge}</span>}
    </Link>
  );

  const brand = (
    <div className="flex items-center gap-3">
      <LogoMark className="h-10 w-10" />
      <div className="min-w-0"><p className="truncate text-[15px] font-extrabold leading-tight text-ink">{t("brand.name")}</p><p className="text-[11px] font-semibold text-brand">{t("admin.console")}</p></div>
    </div>
  );
  const account = (
    <div className="space-y-3 border-t border-slate-200/70 pt-3">
      <div className="flex items-center gap-3"><Avatar name={name || "A"} /><div className="min-w-0"><p className="truncate text-sm font-semibold">{name}</p><p className="truncate text-xs text-slate-500" dir="ltr">{localPhone(me?.phone)}</p></div></div>
      <div className="flex items-center justify-between">
        <LogoutButton />
        <a href={`/api/lang?l=${locale === "ar" ? "en" : "ar"}`} className="inline-flex items-center gap-1.5 rounded-xl px-2.5 py-2 text-xs font-semibold text-slate-600 transition hover:bg-brand-soft hover:text-brand"><Icon name="globe" className="h-4 w-4" />{locale === "ar" ? "EN" : "عربي"}</a>
      </div>
    </div>
  );

  return (
    <div className="fixed inset-0 z-[60] flex overflow-hidden bg-[#f4f7fb]">
      {/* desktop sidebar */}
      <aside className="sidebar-bg hidden w-64 shrink-0 flex-col border-e border-slate-200/70 p-4 lg:flex">
        <div className="mb-5">{brand}</div>
        <nav className="flex-1 space-y-1 overflow-y-auto">{items.map((i) => link(i))}</nav>
        {account}
      </aside>

      {/* phone drawer */}
      <div onClick={() => setOpen(false)} className={`fixed inset-0 z-10 bg-slate-900/40 backdrop-blur-sm transition-opacity lg:hidden ${open ? "opacity-100" : "pointer-events-none opacity-0"}`} aria-hidden />
      <aside className={`sidebar-bg fixed inset-y-0 start-0 z-20 flex w-72 flex-col p-4 shadow-2xl transition-transform duration-300 lg:hidden ${open ? "translate-x-0" : "ltr:-translate-x-full rtl:translate-x-full"}`}>
        <div className="mb-5 flex items-center justify-between">{brand}<button onClick={() => setOpen(false)} aria-label="Close" className="rounded-xl p-2 text-slate-500 hover:bg-slate-100"><Icon name="close" /></button></div>
        <nav className="flex-1 space-y-1 overflow-y-auto">{items.map((i) => link(i))}</nav>
        {account}
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        {/* phone top bar */}
        <header className="glass z-10 flex items-center justify-between border-b border-slate-200/70 px-3 py-2.5 lg:hidden">
          <button onClick={() => setOpen(true)} aria-label="Menu" className="rounded-xl p-2 text-slate-700 hover:bg-brand-soft"><Icon name="menu" className="h-6 w-6" /></button>
          <span className="truncate font-bold text-ink">{current ? t(current.label) : t("admin.console")}</span>
          <Link href="/admin/notifications" className="relative rounded-xl p-2 text-slate-700" aria-label={t("nav.notifications")}>
            <Icon name="bell" />
            {!!unread.data?.count && <span className="absolute end-0.5 top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-red-600 px-1 text-[10px] font-bold text-white">{unread.data.count}</span>}
          </Link>
        </header>
        <main className="flex-1 overflow-y-auto p-4 pb-24 sm:p-6 lg:p-8 lg:pb-8">
          <div className="mx-auto max-w-6xl">{children}</div>
        </main>
        {/* phone bottom bar */}
        <nav className="glass flex justify-around border-t border-slate-200/70 px-1 pb-[max(0.35rem,env(safe-area-inset-bottom))] pt-1.5 lg:hidden">{items.slice(0, 4).map((i) => link(i, true))}
          {items.length > 4 && <button onClick={() => setOpen(true)} className="relative flex min-w-0 flex-1 flex-col items-center gap-0.5 rounded-xl py-1.5 text-[11px] font-semibold text-slate-500 transition active:scale-95"><Icon name="menu" className="h-5 w-5" /><span className="truncate">{t("common.more")}</span></button>}
        </nav>
      </div>
    </div>
  );
}
