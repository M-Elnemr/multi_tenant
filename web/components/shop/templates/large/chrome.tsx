"use client";

/** Small client pieces every large header/menu needs (cart count, account link, language link, nested category menu). Styling comes from the template. */
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { useCart } from "@/lib/cart";
import { Icon, type IconName } from "@/components/icons";
import { useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import type { CategoryNode } from "@/components/shop/types";

export function CartLink({ className = "", render }: { className?: string; render?: (n: number) => React.ReactNode }) {
  const { t } = useI18n();
  const n = useCart().reduce((s, l) => s + l.quantity, 0);
  return (
    <Link href="/cart" aria-label={`${t("nav.cart")} (${n})`} className={`lg-cart ${className}`}>
      {render ? render(n) : <><Icon name="cart" className="h-[22px] w-[22px]" />{n > 0 && <span className="lg-count" key={n}>{n}</span>}</>}
    </Link>
  );
}

export function AccountLink({ className = "", withName }: { className?: string; withName?: boolean }) {
  const { t } = useI18n();
  const { me, ready } = useMe();
  const staff = !!me && me.roles.some((r) => r !== "CUSTOMER");
  return (
    <>
      {ready && staff && <Link href="/dashboard" className="lg-staff">{t("nav.dashboard")}</Link>}
      <Link href={me ? "/account" : "/login"} aria-label={me ? t("nav.account") : t("nav.login")} className={`lg-account ${className}`}>
        <Icon name="user" className="h-[22px] w-[22px]" />
        {withName && <span className="lg-account-name">{ready ? (me ? me.firstName || t("nav.account") : t("nav.login")) : ""}</span>}
      </Link>
    </>
  );
}

export function LangLink({ className = "" }: { className?: string }) {
  const { locale } = useI18n();
  return <a href={`/api/lang?l=${locale === "ar" ? "en" : "ar"}`} className={`lg-lang ${className}`} lang={locale === "ar" ? "en" : "ar"}>{locale === "ar" ? "EN" : "عربي"}</a>;
}

/** Nested category list for menus: one level open at a time per branch. */
export function NavTree({ nodes, close, depth = 0 }: { nodes: CategoryNode[]; close: () => void; depth?: number }) {
  return (
    <ul className="lg-navtree" data-depth={depth}>
      {nodes.map((n) => (
        <li key={n.id}>
          {n.children.length === 0
            ? <Link href={`/c/${n.slug}`} onClick={close} className="lg-navlink">{n.name}</Link>
            : <details><summary className="lg-navlink"><span>{n.name}</span><Icon name="chevron" className="lg-chev h-4 w-4" /></summary>
              <Link href={`/c/${n.slug}`} onClick={close} className="lg-navlink lg-navall"><NavAll slug={n.slug} /></Link>
              <NavTree nodes={n.children} close={close} depth={depth + 1} /></details>}
        </li>
      ))}
    </ul>
  );
}
function NavAll({ slug }: { slug: string }) { void slug; const { t } = useI18n(); return <>{t("shop.viewAll")}</>; }

/** true once the page has scrolled past `y` px (for headers that condense) */
export function useScrolled(y = 40) {
  const [s, setS] = useState(false);
  useEffect(() => {
    const on = () => setS(window.scrollY > y);
    on();
    window.addEventListener("scroll", on, { passive: true });
    return () => window.removeEventListener("scroll", on);
  }, [y]);
  return s;
}

/** Closes a panel when the route changes or Escape is pressed, and locks page scroll while it is open. */
export function usePanel(open: boolean, close: () => void) {
  const path = usePathname();
  useEffect(() => { close(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [path]);
  useEffect(() => {
    if (!open) return;
    const key = (e: KeyboardEvent) => { if (e.key === "Escape") close(); };
    document.addEventListener("keydown", key);
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => { document.removeEventListener("keydown", key); document.body.style.overflow = prev; };
  }, [open, close]);
}

/** Bottom tab bar for phones (home, shop, offers, bag, account). Templates that want it style `.lg-tabs`. */
export function TabBar() {
  const { t } = useI18n();
  const path = usePathname();
  const n = useCart().reduce((s, l) => s + l.quantity, 0);
  const items: { href: string; icon: IconName; label: string; badge?: number }[] = [
    { href: "/", icon: "home", label: t("shop.home") }, { href: "/products", icon: "grid", label: t("shop.allProducts") }, { href: "/products?onSale=true", icon: "tag", label: t("shop.offers") },
    { href: "/cart", icon: "cart", label: t("nav.cart"), badge: n }, { href: "/account", icon: "user", label: t("nav.account") },
  ];
  return (
    <nav className="lg-tabs" aria-label="Primary">
      {items.map((i) => {
        const base = i.href.split("?")[0];
        const active = i.href.includes("?") ? false : base === "/" ? path === "/" : path.startsWith(base);
        return <Link key={i.href} href={i.href} data-active={active || undefined}><Icon name={i.icon} className="h-[22px] w-[22px]" /><span>{i.label}</span>{i.badge ? <b>{i.badge}</b> : null}</Link>;
      })}
    </nav>
  );
}
