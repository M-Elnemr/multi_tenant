"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import { money } from "@/lib/format";
import { toggleWish, useWishlist } from "@/lib/open-client";
import { Icon } from "@/components/icons";

export function WishlistView() {
  const { t, locale } = useI18n();
  const items = useWishlist();
  const [mounted, setMounted] = useState(false);
  useEffect(() => { const id = setTimeout(() => setMounted(true), 0); return () => clearTimeout(id); }, []);
  return (
    <div className="o-container pt-6 md:pt-10">
      <h1 className="text-2xl font-black md:text-3xl">{t("open.wishlist")}</h1>
      {!mounted ? <div className="o-skel mt-5 h-48" /> : items.length === 0 ? (
        <div className="o-card mt-5 px-6 py-16 text-center"><p className="text-5xl">🤍</p><h2 className="o-title mt-3">{t("open.wishlist.empty")}</h2><p className="o-mute mt-1">{t("open.wishlist.hint")}</p><Link href="/" className="o-btn mt-5">{t("open.cart.startShopping")}</Link></div>
      ) : (
        <ul className="mt-5 grid grid-cols-2 gap-3 md:grid-cols-4 md:gap-4 lg:grid-cols-5">
          {items.map((w) => (
            <li key={w.id} className="o-pc">
              <Link href={`/p/${w.shopSlug}/${w.slug}`} className="o-pc-img block">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                {w.image ? <img src={w.image} alt={w.name} width={320} height={320} loading="lazy" /> : <span className="grid h-full place-items-center text-4xl opacity-30">🛍️</span>}
              </Link>
              <div className="flex flex-1 flex-col gap-1 p-3">
                <Link href={`/p/${w.shopSlug}/${w.slug}`} className="line-clamp-2 text-sm font-bold">{w.name}</Link>
                <p className="mt-auto font-extrabold">{money(w.priceMinor, w.currency, locale)}</p>
                <button type="button" onClick={() => toggleWish(w)} className="flex items-center gap-1 text-xs font-bold text-[var(--o-red)]"><Icon name="close" className="h-3.5 w-3.5" />{t("open.removeFromWishlist")}</button>
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
