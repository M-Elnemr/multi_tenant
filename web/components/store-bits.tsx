"use client";

import Link from "next/link";
import { useCart } from "@/lib/cart";
import { useMe } from "./hooks";
import { useT } from "./i18n-provider";

export function CartLink() {
  const t = useT();
  const count = useCart().reduce((n, l) => n + l.quantity, 0);
  return (
    <Link href="/cart" className="relative rounded-lg px-2 py-1.5 text-sm hover:bg-slate-100" aria-label={t("nav.cart")}>
      🛒 {t("nav.cart")}{count > 0 && <span className="absolute -end-1 -top-1 rounded-full bg-brand px-1.5 text-[10px] font-bold text-white">{count}</span>}
    </Link>
  );
}

export function AccountLink() {
  const t = useT();
  const { me, ready, can } = useMe();
  if (!ready) return <span className="w-16" />;
  if (!me) return <Link href="/login" className="rounded-lg px-2 py-1.5 text-sm hover:bg-slate-100">{t("nav.login")}</Link>;
  const staff = me.roles.some((r) => r !== "CUSTOMER");
  return (
    <div className="flex items-center gap-1 text-sm">
      {staff && can("settings.manage") !== undefined && <Link href="/dashboard" className="rounded-lg bg-slate-900 px-2.5 py-1.5 text-white">{t("nav.dashboard")}</Link>}
      <Link href="/account" className="rounded-lg px-2 py-1.5 hover:bg-slate-100">{me.firstName}</Link>
    </div>
  );
}
