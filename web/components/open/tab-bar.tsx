"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { motion, useReducedMotion } from "motion/react";
import { useOpenCart } from "@/lib/open-client";
import { Icon, type IconName } from "../icons";
import { useI18n } from "../i18n-provider";

/** Thumb-reach navigation on phones. A small bar slides over the active tab. Offsets for floating elements come from the shared --tabbar-h variables. */
export function OpenTabBar() {
  const { t } = useI18n();
  const path = usePathname();
  const reduce = useReducedMotion();
  const count = useOpenCart().reduce((n, l) => n + l.quantity, 0);
  const items: { href: string; match: (p: string) => boolean; icon: IconName; label: string; badge?: number }[] = [
    { href: "/", match: (p) => p === "/", icon: "home", label: t("shop.home") },
    { href: "/search", match: (p) => p.startsWith("/search") || p.startsWith("/c/"), icon: "search", label: t("open.browse") },
    { href: "/shops", match: (p) => p.startsWith("/shop"), icon: "bag", label: t("open.shopsTab") },
    { href: "/cart", match: (p) => p.startsWith("/cart") || p.startsWith("/checkout"), icon: "cart", label: t("nav.cart"), badge: count },
    { href: "/account", match: (p) => p.startsWith("/account") || p.startsWith("/login") || p.startsWith("/orders") || p.startsWith("/order/") || p.startsWith("/wishlist"), icon: "users", label: t("nav.account") },
  ];
  return (
    <nav className="fixed inset-x-0 bottom-0 z-40 border-t border-[var(--o-line)] bg-[var(--o-paper)]/95 pb-[var(--safe-b)] backdrop-blur-xl md:hidden" aria-label={t("open.nav.primary")}>
      <ul className="grid h-[var(--tabbar-h)] grid-cols-5">
        {items.map((i) => {
          const on = i.match(path);
          return (
            <li key={i.href} className="h-full">
              <Link href={i.href} aria-current={on ? "page" : undefined} className={`relative flex h-full flex-col items-center justify-center gap-1 text-[11px] font-semibold transition-colors ${on ? "text-[var(--o-ink)]" : "text-[var(--o-mute)]"}`}>
                {on && <motion.span layoutId="o-tab-bar" transition={reduce ? { duration: 0 } : { type: "spring", stiffness: 500, damping: 38 }} className="absolute inset-x-5 top-0 h-[3px] rounded-b-full bg-[var(--o-accent)]" />}
                <Icon name={i.icon} className="h-[22px] w-[22px]" />
                {i.label}
                {i.badge ? <span className="o-num absolute end-[22%] top-1.5 grid h-4 min-w-4 place-items-center rounded-full bg-[var(--o-accent)] px-1 text-[10px] font-extrabold text-white">{i.badge}</span> : null}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
