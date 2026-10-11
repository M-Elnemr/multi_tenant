"use client";

import Link from "next/link";
import { useMe } from "./hooks";
import { useT } from "./i18n-provider";
import { LogoutButton } from "./logout-button";

const staff = (roles: string[]) => roles.some((r) => r === "PLATFORM_OWNER" || r === "PLATFORM_ADMIN");

/** Header buttons of the platform site: Log in / Start free when signed out, "My places" (or Admin) and Log out when signed in. */
export function PlatformAccountLinks() {
  const t = useT();
  const { me, ready } = useMe();
  if (!ready) return <span className="h-9 w-40" aria-hidden />;   // no flash of "Log in" for someone who is signed in
  if (!me) return (
    <>
      <Link href="/login" className="rounded-lg px-3 py-2 font-medium text-slate-600 transition hover:bg-slate-100 hover:text-slate-900">{t("nav.login")}</Link>
      <Link href="/register" className="rounded-xl bg-brand-gradient px-4 py-2 font-semibold text-white shadow-brand transition hover:-translate-y-px hover:brightness-110 active:scale-[.97]">{t("nav.startFree")}</Link>
    </>
  );
  return (
    <>
      <Link href={staff(me.roles) ? "/admin" : "/portal"} className="rounded-xl bg-slate-900 px-4 py-2 font-semibold text-white transition hover:bg-slate-700 active:scale-[.97]">{staff(me.roles) ? t("nav.admin") : t("nav.myPlaces")}</Link>
      <LogoutButton />
    </>
  );
}

/** Footer variant: only the links that make sense for the current visitor. */
export function PlatformFooterLinks({ className }: { className: string }) {
  const t = useT();
  const { me, ready } = useMe();
  if (!ready) return null;
  return me
    ? <Link href={staff(me.roles) ? "/admin" : "/portal"} className={className}>{staff(me.roles) ? t("nav.admin") : t("nav.myPlaces")}</Link>
    : <><Link href="/login" className={className}>{t("nav.login")}</Link><Link href="/register" className={className}>{t("nav.startFree")}</Link></>;
}
