"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/client";
import { useMe } from "./hooks";
import { useI18n } from "./i18n-provider";
import { Icon, type IconName } from "./icons";
import { Avatar } from "./ui";

const ITEMS: { href: string; key: string; icon: IconName; exact?: boolean }[] = [
  { href: "/portal", key: "portal.appointments", icon: "calendar", exact: true },
  { href: "/portal/record", key: "portal.record", icon: "file" },
  { href: "/", key: "portal.clinicDetails", icon: "stethoscope", exact: true },
  { href: "/portal/account", key: "account.title", icon: "users" },
];

/** The patient portal's header: clinic name and logo, pill navigation (a bottom bar on phones) and an account menu. */
export function PortalNav({ name, logo }: { name: string; logo: string | null }) {
  const { t, locale } = useI18n();
  const path = usePathname();
  const router = useRouter();
  const { me } = useMe();
  const [open, setOpen] = useState(false);
  const menu = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const h = (e: MouseEvent) => { if (menu.current && !menu.current.contains(e.target as Node)) setOpen(false); };
    document.addEventListener("mousedown", h);
    return () => document.removeEventListener("mousedown", h);
  }, []);
  const on = (i: (typeof ITEMS)[number]) => (i.exact ? path === i.href : path.startsWith(i.href));
  const full = me ? `${me.firstName} ${me.lastName}`.trim() : "";
  const signOut = async () => { await api("auth/logout", { body: {} }).catch(() => undefined); router.replace("/"); router.refresh(); };
  return (
    <>
      <header className="sticky top-0 z-30 border-b border-slate-200/70 bg-white/80 backdrop-blur-xl">
        <div className="mx-auto flex max-w-5xl items-center justify-between gap-3 px-4 py-2.5">
          <Link href="/" className="flex min-w-0 items-center gap-2.5">
            {logo ? (
              // eslint-disable-next-line @next/next/no-img-element
              <img src={logo} alt="" className="h-9 w-9 shrink-0 rounded-xl object-cover shadow-sm ring-1 ring-slate-200" />
            ) : <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-brand-gradient text-white shadow-brand"><Icon name="stethoscope" className="h-5 w-5" /></span>}
            <span className="truncate text-base font-extrabold tracking-tight text-ink">{name}</span>
          </Link>
          <nav className="hidden items-center gap-1 rounded-full bg-slate-100/80 p-1 md:flex" aria-label="Portal">
            {ITEMS.map((i) => (
              <Link key={i.href} href={i.href} className={`flex items-center gap-2 rounded-full px-5 py-2.5 text-[15px] font-bold transition ${on(i) ? "bg-white text-brand shadow-sm" : "text-slate-600 hover:bg-white/60 hover:text-brand"}`}>
                <Icon name={i.icon} className="h-[18px] w-[18px]" />{t(i.key)}
              </Link>
            ))}
          </nav>
          <div className="flex items-center gap-2">
          <Link href="/book" className="hidden items-center gap-1.5 rounded-full bg-brand-gradient px-4 py-2.5 text-sm font-bold text-white shadow-brand transition hover:brightness-110 active:scale-95 lg:flex"><Icon name="plus" className="h-4 w-4" />{t("clinic.book")}</Link>
          <div className="relative" ref={menu}>
            <button onClick={() => setOpen(!open)} className="flex items-center gap-2 rounded-full p-1 pe-3 ring-1 ring-slate-200 transition hover:bg-slate-50 active:scale-95" aria-haspopup="menu" aria-expanded={open}>
              <Avatar name={full || "?"} className="h-8 w-8 text-xs" />
              <span className="hidden max-w-[8rem] truncate text-sm font-semibold sm:block">{me?.firstName}</span>
              <Icon name="arrow" className={`h-3.5 w-3.5 text-slate-400 transition ${open ? "-rotate-90" : "rotate-90"}`} />
            </button>
            {open && (
              <div role="menu" className="absolute end-0 mt-2 w-60 animate-scale-in overflow-hidden rounded-2xl border border-slate-200 bg-white p-1.5 shadow-2xl">
                <div className="px-3 py-2"><p className="truncate font-bold">{full}</p><p className="truncate text-xs text-slate-500" dir="ltr">{me?.phone}</p></div>
                <Link href="/portal/account" onClick={() => setOpen(false)} role="menuitem" className="flex items-center gap-2.5 rounded-xl px-3 py-2.5 text-sm font-medium hover:bg-slate-50"><Icon name="users" className="h-4 w-4 text-slate-400" />{t("account.title")}</Link>
                <a href={`/api/lang?l=${locale === "ar" ? "en" : "ar"}`} role="menuitem" className="flex items-center gap-2.5 rounded-xl px-3 py-2.5 text-sm font-medium hover:bg-slate-50"><Icon name="language" className="h-4 w-4 text-slate-400" />{locale === "ar" ? "English" : "العربية"}</a>
                <button onClick={signOut} role="menuitem" className="flex w-full items-center gap-2.5 rounded-xl px-3 py-2.5 text-start text-sm font-medium text-rose-600 hover:bg-rose-50"><Icon name="logout" className="h-4 w-4 flip-rtl" />{t("nav.logout")}</button>
              </div>
            )}
          </div>
          </div>
        </div>
      </header>
      <nav className="fixed inset-x-0 bottom-0 z-30 border-t border-slate-200/80 bg-white/90 pb-[env(safe-area-inset-bottom)] backdrop-blur-xl md:hidden" aria-label="Portal">
        <div className="mx-auto grid max-w-md grid-cols-4">
          {ITEMS.map((i) => (
            <Link key={i.href} href={i.href} className={`flex flex-col items-center gap-1 py-2.5 text-xs font-bold transition active:scale-95 ${on(i) ? "text-brand" : "text-slate-500"}`}>
              <span className={`flex h-9 w-16 items-center justify-center rounded-full transition ${on(i) ? "bg-brand-soft" : ""}`}><Icon name={i.icon} className="h-6 w-6" /></span>{t(i.key)}
            </Link>
          ))}
        </div>
      </nav>
    </>
  );
}
