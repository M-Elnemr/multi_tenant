"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useCart } from "@/lib/cart";
import { Icon, type IconName } from "../icons";
import { useI18n } from "../i18n-provider";

/** Thumb-reach navigation on phones: home, all products, offers, cart, account. */
export function MobileBar() {
  const { t } = useI18n();
  const path = usePathname();
  const count = useCart().reduce((n, l) => n + l.quantity, 0);
  const items: { href: string; icon: IconName; label: string; badge?: number }[] = [
    { href: "/", icon: "home", label: t("shop.home") },
    { href: "/products", icon: "bag", label: t("shop.allProducts") },
    { href: "/products?onSale=true", icon: "tag", label: t("shop.offers") },
    { href: "/cart", icon: "cart", label: t("nav.cart"), badge: count },
    { href: "/account", icon: "heart", label: t("nav.account") },
  ];
  return (
    <nav className="fixed inset-x-0 bottom-0 z-40 border-t border-[var(--s-line)] bg-white/95 pb-[env(safe-area-inset-bottom)] backdrop-blur-xl md:hidden" aria-label="Primary">
      <ul className="grid grid-cols-5">
        {items.map((i) => {
          const active = i.href === "/" ? path === "/" : path.startsWith(i.href.split("?")[0]) && !i.href.includes("?");
          return (
            <li key={i.href}>
              <Link href={i.href} className={`relative flex flex-col items-center gap-0.5 py-2 text-[10.5px] font-bold transition ${active ? "" : "text-[var(--s-mute)]"}`} style={active ? { color: "var(--brand)" } : undefined}>
                <Icon name={i.icon} className="h-[22px] w-[22px]" />
                {i.label}
                {i.badge ? <span className="absolute end-[22%] top-1 grid h-4 min-w-4 place-items-center rounded-full px-1 text-[10px] font-extrabold text-white" style={{ background: "var(--brand)" }}>{i.badge}</span> : null}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
