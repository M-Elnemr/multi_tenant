"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { useApi, useMe } from "../hooks";
import { useI18n } from "../i18n-provider";
import { LogoutButton } from "../logout-button";
import { LogoMark, PoweredBy } from "../brand";
import { Icon, hasIcon } from "../icons";
import { Avatar, Loading } from "../ui";

export type NavItem = { href: string; label: string; perm?: string | string[]; icon?: string };

function NavIcon({ icon, className }: { icon?: string; className?: string }) {
  return hasIcon(icon) ? <Icon name={icon} className={className ?? "h-[18px] w-[18px]"} /> : <span aria-hidden>{icon ?? "•"}</span>;
}

/** Sidebar dashboard used by store owners, doctors and staff. Items are hidden without the permission (the backend enforces it regardless). */
export function DashboardShell({ nav, children }: { nav: NavItem[]; children: React.ReactNode }) {
  const { t, siteName, locale } = useI18n();
  const path = usePathname();
  const router = useRouter();
  const { me, ready, can } = useMe();
  const unread = useApi<{ count: number }>("notifications/unread-count");
  const [open, setOpen] = useState(false);

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
  const bell = (cls: string) => (
    <Link href="/dashboard/notifications" className={`relative rounded-xl p-2 text-slate-600 transition hover:bg-brand-soft hover:text-brand ${cls}`} aria-label={t("nav.notifications")}>
      <Icon name="bell" />
      {count > 0 && <span className="absolute end-0.5 top-0.5 flex h-4 min-w-4 animate-pop items-center justify-center rounded-full bg-red-600 px-1 text-[10px] font-bold text-white">{count}</span>}
    </Link>
  );
  const link = (n: NavItem, i: number) => {
    const active = isActive(n);
    return (
      <Link key={n.href} href={n.href} onClick={() => setOpen(false)} style={{ animationDelay: `${i * 25}ms` }} className={`group flex animate-fade-up items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition duration-200 ${active ? "bg-brand-gradient text-white shadow-brand" : "text-slate-600 hover:bg-brand-soft hover:text-brand-dark"}`}>
        <span className={`transition-transform duration-200 ${active ? "" : "group-hover:scale-110"}`}><NavIcon icon={n.icon} /></span>
        <span className="truncate">{t(n.label)}</span>
      </Link>
    );
  };
  const quick = allowed.slice(0, 4);

  return (
    <div className="min-h-screen lg:flex">
      {/* mobile top bar */}
      <header className="glass sticky top-0 z-30 flex items-center justify-between border-b border-slate-200/70 px-3 py-2.5 lg:hidden">
        <button onClick={() => setOpen(true)} aria-label="Menu" className="rounded-xl p-2 text-slate-700 transition hover:bg-brand-soft active:scale-95"><Icon name="menu" className="h-6 w-6" /></button>
        <span className="flex min-w-0 items-center gap-2 font-bold"><LogoMark className="h-7 w-7" /><span className="truncate text-gradient">{current ? t(current.label) : siteName}</span></span>
        {bell("")}
      </header>

      {/* backdrop (phones) */}
      <div onClick={() => setOpen(false)} className={`fixed inset-0 z-40 bg-slate-900/40 backdrop-blur-sm transition-opacity duration-300 lg:hidden ${open ? "opacity-100" : "pointer-events-none opacity-0"}`} aria-hidden />

      <aside className={`fixed inset-y-0 start-0 z-50 flex w-72 flex-col border-e border-slate-200/70 bg-white/95 p-4 shadow-2xl backdrop-blur transition-transform duration-300 ease-out lg:sticky lg:top-0 lg:z-20 lg:h-screen lg:w-64 lg:shrink-0 lg:translate-x-0! lg:shadow-none ${open ? "translate-x-0" : "ltr:-translate-x-full rtl:translate-x-full"}`}>
        <div className="mb-5 flex items-center justify-between gap-2">
          <Link href="/" className="flex min-w-0 items-center gap-2.5">
            <LogoMark className="h-10 w-10" />
            <span className="truncate text-lg font-bold text-ink">{siteName}</span>
          </Link>
          <div className="flex items-center">
            {bell("hidden lg:block")}
            <button onClick={() => setOpen(false)} aria-label="Close" className="rounded-xl p-2 text-slate-500 hover:bg-slate-100 lg:hidden"><Icon name="close" /></button>
          </div>
        </div>
        <nav className="flex-1 space-y-1 overflow-y-auto pe-1">{allowed.map(link)}</nav>
        <div className="mt-4 space-y-3 border-t border-slate-200/70 pt-4">
          <div className="flex items-center gap-3">
            <Avatar name={fullName} />
            <div className="min-w-0">
              <p className="truncate text-sm font-semibold">{fullName}</p>
              <p className="truncate text-xs text-slate-500">{me.roles.map((r) => t(`role.${r}`)).join(", ")}</p>
            </div>
          </div>
          <div className="flex items-center justify-between">
            <LogoutButton />
            <a href={`/api/lang?l=${locale === "ar" ? "en" : "ar"}`} className="inline-flex items-center gap-1 rounded-lg px-2 py-1 text-xs font-medium text-slate-500 transition hover:bg-slate-100 hover:text-slate-800"><Icon name="globe" className="h-4 w-4" />{locale === "ar" ? "EN" : "عربي"}</a>
          </div>
          <PoweredBy label={t("brand.powered")} name={t("brand.name")} />
        </div>
      </aside>

      <main className="min-w-0 flex-1 p-4 pb-24 sm:p-6 lg:p-8 lg:pb-8"><div key={path} className="animate-fade-up">{children}</div></main>

      {/* phone quick bar */}
      <nav className="glass fixed inset-x-0 bottom-0 z-30 flex justify-around border-t border-slate-200/70 px-1 pb-[max(0.35rem,env(safe-area-inset-bottom))] pt-1.5 lg:hidden">
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
