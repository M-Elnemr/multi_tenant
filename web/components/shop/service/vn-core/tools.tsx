"use client";

import Link from "next/link";
import { useEffect, useState, type ReactNode } from "react";
import { useCart } from "@/lib/cart";
import { fileUrl, type Variant } from "@/lib/media";
import { useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Icon } from "@/components/icons";

/** Cart link with its live count; the look comes from `className`. Shown only where the site sells products. */
export function CartLink({ className = "", badgeClass = "" }: { className?: string; badgeClass?: string }) {
  const { t } = useI18n();
  const count = useCart().reduce((n, l) => n + l.quantity, 0);
  return (
    <Link href="/cart" aria-label={t("nav.cart")} className={`relative ${className}`}>
      <Icon name="cart" className="h-5 w-5" />
      {count > 0 && <span className={badgeClass}>{count}</span>}
    </Link>
  );
}

/** Account / login link (customers can still see their own orders). Staff get the dashboard link. */
export function AccountLink({ className = "", withLabel = false }: { className?: string; withLabel?: boolean }) {
  const { t } = useI18n();
  const { me, ready } = useMe();
  if (!ready) return <span className="inline-block h-10 w-10" aria-hidden />;
  return (
    <Link href={me ? "/account" : "/login"} aria-label={me ? t("nav.account") : t("nav.login")} className={className}>
      <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" aria-hidden><circle cx="12" cy="8" r="4" /><path d="M4 21a8 8 0 0 1 16 0" /></svg>
      {withLabel && <span>{me ? me.firstName || t("nav.account") : t("nav.login")}</span>}
    </Link>
  );
}

export function LangLink({ className = "" }: { className?: string }) {
  const { locale } = useI18n();
  return <a href={`/api/lang?l=${locale === "ar" ? "en" : "ar"}`} className={className} lang={locale === "ar" ? "en" : "ar"}>{locale === "ar" ? "EN" : "عربي"}</a>;
}

/** Open/close state of a mobile menu: Escape closes it and the page behind it does not scroll while it is open. */
export function useMenu() {
  const [open, setOpen] = useState(false);
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") setOpen(false); };
    document.addEventListener("keydown", onKey);
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => { document.removeEventListener("keydown", onKey); document.body.style.overflow = prev; };
  }, [open]);
  return { open, setOpen };
}

/** True once the page has scrolled past `px` (for headers that turn solid). */
export function useScrolled(px = 24) {
  const [s, setS] = useState(false);
  useEffect(() => {
    const on = () => setS(window.scrollY > px);
    on();
    window.addEventListener("scroll", on, { passive: true });
    return () => window.removeEventListener("scroll", on);
  }, [px]);
  return s;
}

/** A photo from the shop's files; when there is none (or it fails to load) the `fallback` is shown instead. Photos are never recoloured. */
export function Pic({ id, variant = "medium", alt = "", className = "", fallback }: { id?: string | null; variant?: Variant; alt?: string; className?: string; fallback?: ReactNode }) {
  const [bad, setBad] = useState(false);
  if (!id || bad) return <>{fallback ?? null}</>;
  // eslint-disable-next-line @next/next/no-img-element
  return <img src={fileUrl(id, variant)} alt={alt} loading="lazy" decoding="async" onError={() => setBad(true)} className={className} />;
}
