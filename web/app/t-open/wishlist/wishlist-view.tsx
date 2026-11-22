"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import { money } from "@/lib/format";
import { toggleWish, useWishlist } from "@/lib/open-client";
import { Icon } from "@/components/icons";
import { OEmpty } from "@/components/open/empty";
import { Monogram } from "@/components/open/media";

export function WishlistView() {
  const { t, locale } = useI18n();
  const items = useWishlist();
  const [mounted, setMounted] = useState(false);
  useEffect(() => { const id = setTimeout(() => setMounted(true), 0); return () => clearTimeout(id); }, []);
  return (
    <div className="o-container pt-6 md:pt-10">
      <h1 className="o-h1">{t("open.wishlist")}</h1>
      {!mounted ? <div className="o-skel mt-5 h-48" /> : items.length === 0 ? (
        <div className="mt-5"><OEmpty icon="heart" title={t("open.wishlist.empty")} body={t("open.wishlist.hint")} href="/" cta={t("open.cart.startShopping")} /></div>
      ) : (
        <ul className="mt-5 grid grid-cols-2 gap-3 md:grid-cols-4 md:gap-4 lg:grid-cols-5">
          {items.map((w) => (
            <li key={w.id} className="o-pc">
              <Link href={`/p/${w.shopSlug}/${w.slug}`} className="o-pc-img block">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                {w.image ? <img src={w.image} alt={w.name} width={320} height={320} loading="lazy" /> : <Monogram name={w.name} />}
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
