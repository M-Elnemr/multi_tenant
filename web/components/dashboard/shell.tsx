"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { useMe } from "../hooks";
import { useI18n } from "../i18n-provider";
import { LogoutButton } from "../logout-button";
import { Loading } from "../ui";
import { useApi } from "../hooks";

export type NavItem = { href: string; label: string; perm?: string | string[]; icon?: string };

/** Sidebar dashboard used by store owners, doctors and staff. Items are hidden without the permission (the backend enforces it regardless). */
export function DashboardShell({ nav, children }: { nav: NavItem[]; children: React.ReactNode }) {
  const { t, siteName, locale } = useI18n();
  const path = usePathname();
  const router = useRouter();
  const { me, ready, can } = useMe();
  const unread = useApi<{ count: number }>("notifications/unread-count");
  const [open, setOpen] = useState(false);

  useEffect(() => { if (ready && !me) router.replace(`/login?next=${encodeURIComponent(path)}`); }, [ready, me, path, router]);
  if (!ready || !me) return <Loading />;
  const allowed = nav.filter((n) => !n.perm || (Array.isArray(n.perm) ? n.perm.some(can) : can(n.perm)));
  const link = (n: NavItem) => {
    const active = n.href === "/dashboard" ? path === n.href : path.startsWith(n.href);
    return (
      <Link key={n.href} href={n.href} onClick={() => setOpen(false)} className={`flex items-center gap-3 rounded-lg px-3 py-2 text-sm ${active ? "bg-brand text-white" : "text-slate-700 hover:bg-slate-100"}`}>
        <span aria-hidden>{n.icon ?? "•"}</span>{t(n.label)}
      </Link>
    );
  };
  return (
    <div className="min-h-screen bg-slate-50 lg:flex">
      <header className="flex items-center justify-between border-b bg-white px-4 py-3 lg:hidden">
        <button onClick={() => setOpen(!open)} aria-label="Menu" className="rounded p-2 hover:bg-slate-100">☰</button>
        <span className="font-semibold">{siteName}</span>
        <Link href="/dashboard/notifications" className="relative p-2">🔔{!!unread.data?.count && <span className="absolute end-0 top-0 rounded-full bg-red-600 px-1 text-[10px] text-white">{unread.data.count}</span>}</Link>
      </header>
      <aside className={`${open ? "block" : "hidden"} w-full border-e bg-white p-4 lg:sticky lg:top-0 lg:block lg:h-screen lg:w-64 lg:shrink-0 lg:overflow-y-auto`}>
        <div className="mb-5 hidden items-center justify-between lg:flex">
          <Link href="/" className="truncate text-lg font-bold text-brand">{siteName}</Link>
          <Link href="/dashboard/notifications" className="relative p-1" aria-label={t("nav.notifications")}>🔔{!!unread.data?.count && <span className="absolute -end-1 -top-1 rounded-full bg-red-600 px-1 text-[10px] text-white">{unread.data.count}</span>}</Link>
        </div>
        <nav className="space-y-1">{allowed.map(link)}</nav>
        <div className="mt-6 border-t pt-4 text-sm">
          <p className="truncate font-medium">{me.firstName} {me.lastName}</p>
          <p className="truncate text-xs text-slate-500">{me.roles.map((r) => t(`role.${r}`)).join(", ")}</p>
          <div className="mt-2 flex items-center justify-between">
            <LogoutButton />
            <a href={`/api/lang?l=${locale === "ar" ? "en" : "ar"}`} className="text-xs text-slate-500">{locale === "ar" ? "EN" : "عربي"}</a>
          </div>
        </div>
      </aside>
      <main className="min-w-0 flex-1 p-4 sm:p-6 lg:p-8">{children}</main>
    </div>
  );
}
