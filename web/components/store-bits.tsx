"use client";

import Link from "next/link";
import { useCart } from "@/lib/cart";
import { useMe } from "./hooks";
import { Icon } from "./icons";
import { useT } from "./i18n-provider";

export function CartLink() {
  const t = useT();
  const count = useCart().reduce((n, l) => n + l.quantity, 0);
  return (
    <Link href="/cart" className="relative inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm font-medium transition hover:bg-brand-soft hover:text-brand" aria-label={t("nav.cart")}>
      <Icon name="cart" className="h-[18px] w-[18px]" /><span className="hidden sm:inline">{t("nav.cart")}</span>{count > 0 && <span key={count} className="absolute -end-1 -top-1 animate-pop rounded-full bg-brand-gradient px-1.5 text-[10px] font-bold text-white">{count}</span>}
    </Link>
  );
}

export function AccountLink() {
  const t = useT();
  const { me, ready, can } = useMe();
  if (!ready) return <span className="w-16" />;
  if (!me) return <Link href="/login" className="rounded-lg px-3 py-1.5 text-sm font-medium transition hover:bg-brand-soft hover:text-brand">{t("nav.login")}</Link>;
  const staff = me.roles.some((r) => r !== "CUSTOMER");
  const client = !staff;
  return (
    <div className="flex items-center gap-1 text-sm">
      {staff && can("settings.manage") !== undefined && <Link href="/dashboard" className="rounded-lg bg-slate-900 px-3 py-1.5 font-medium text-white transition hover:bg-slate-700">{t("nav.dashboard")}</Link>}
      <Link href="/account" className="rounded-lg px-2 py-1.5 hover:bg-slate-100">{me.firstName || (client ? "👤" : "")}</Link>
    </div>
  );
}
