"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { picturesFor } from "@/lib/color-media";
import { useOpenChosen } from "./gallery";
import { money } from "@/lib/format";
import { addToCart, replaceCart, type OpenCartLine } from "@/lib/open-client";
import type { OpenShopRef } from "@/lib/open-types";
import { Icon } from "../icons";
import { useI18n } from "../i18n-provider";

export type OpenDetail = {
  id: string; shopId: string; name: string; slug: string; currency: string; badge?: string | null;
  options: { name: string; values: string[]; attribute?: string; labelAr?: string; labelEn?: string; valueMeta?: { value: string; nameAr: string; nameEn: string; hex: string }[] }[];
  variants: { id: string; sku: string; priceMinor: number; compareAtPriceMinor?: number | null; comboKey: string; available: number }[];
  images: { thumb: string; medium: string; original: string; optionValue?: string | null }[];
  shop: OpenShopRef & { isOpen?: boolean; closedMessage?: string };
};

/** Option pickers, quantity, and the Add to cart / Buy now buttons (sticky on phones). The shown price is the picked variant's; the server re-prices at checkout. */
export function OpenBuyBox({ p }: { p: OpenDetail }) {
  const { t, locale } = useI18n();
  const router = useRouter();
  const [choice, setChoice] = useState<Record<string, string>>(() => {
    // start on the first variant that is in stock, so the shopper never lands on "sold out" when something is available
    const first = p.variants.find((v) => v.available > 0) ?? p.variants[0];
    const parts = Object.fromEntries((first?.comboKey ?? "").split("|").filter(Boolean).map((x) => x.split("=") as [string, string]));
    return Object.fromEntries(p.options.map((o) => [o.name, parts[o.name] ?? o.values[0]]));
  });
  const [qty, setQty] = useState(1);
  const [added, setAdded] = useState(false);
  const [pending, setPending] = useState<{ line: OpenCartLine; go: boolean; other: string } | null>(null);
  const combo = p.options.map((o) => `${o.name}=${choice[o.name]}`).join("|");
  const variant = useMemo(() => p.variants.find((v) => v.comboKey === combo), [p.variants, combo]);
  const available = variant?.available ?? 0;
  const open = p.shop.isOpen !== false;
  const canBuy = !!variant && available > 0 && open;
  const compare = variant?.compareAtPriceMinor && variant.priceMinor < variant.compareAtPriceMinor ? variant.compareAtPriceMinor : null;
  const off = compare && variant ? Math.round(((compare - variant.priceMinor) * 100) / compare) : 0;
  const optLabel = (o: OpenDetail["options"][number]) => (locale === "ar" ? o.labelAr : o.labelEn) ?? o.name;
  const valLabel = (o: OpenDetail["options"][number], v: string) => { const m = o.valueMeta?.find((x) => x.value === v); return m ? (locale === "ar" ? m.nameAr : m.nameEn) : v; };
  const label = p.options.map((o) => valLabel(o, choice[o.name])).join(" / ");
  // the gallery next to this box shows the pictures of the chosen colour
  const { set: setChosen } = useOpenChosen();
  const chosenKey = Object.values(choice).join("\u0001");
  useEffect(() => { setChosen(chosenKey ? chosenKey.split("\u0001") : []); }, [chosenKey, setChosen]);
  // a value is "sold out" when no variant that has it (with the other current choices) has stock
  const soldOut = (o: OpenDetail["options"][number], v: string) => {
    const key = p.options.map((x) => `${x.name}=${x.name === o.name ? v : choice[x.name]}`).join("|");
    const match = p.variants.find((x) => x.comboKey === key);
    return !match || match.available <= 0;
  };

  const finish = (go: boolean) => {
    if (go) { router.push("/cart"); return; }
    setAdded(true);
    setTimeout(() => setAdded(false), 2200);
  };

  const add = (go: boolean) => {
    if (!variant || !canBuy) return;
    const line: OpenCartLine = { variantId: variant.id, productId: p.id, shopId: p.shopId, shopSlug: p.shop.slug, shopName: p.shop.name, slug: p.slug, name: p.name, variantLabel: label, unitPriceMinor: variant.priceMinor, currency: p.currency, quantity: qty, image: picturesFor(p.images, Object.values(choice))[0]?.thumb ?? null };
    const clash = addToCart(line);
    if (clash) { setPending({ line, go, other: clash.conflict }); return; }   // the cart belongs to another shop: ask first
    finish(go);
  };

  return (
    <div>
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <span className="text-3xl font-black md:text-4xl">{variant ? money(variant.priceMinor * qty, p.currency, locale) : "—"}</span>
        {compare && variant && <span className="text-lg text-[var(--o-mute)] line-through">{money(compare * qty, p.currency, locale)}</span>}
        {off > 0 && <span className="o-badge o-badge-sale !text-sm">-{off}%</span>}
      </div>
      {qty > 1 && variant && <p className="o-mute text-xs">{money(variant.priceMinor, p.currency, locale)} × {qty}</p>}

      <div className="mt-5 grid gap-4">
        {p.options.map((o) => (
          <div key={o.name}>
            <p className="mb-2 text-sm font-extrabold">{optLabel(o)}: <span className="font-semibold text-[var(--o-mute)]">{valLabel(o, choice[o.name])}</span></p>
            <div className="flex flex-wrap gap-2">
              {o.values.map((v) => {
                const meta = o.valueMeta?.find((x) => x.value === v);
                const on = choice[o.name] === v;
                const out = soldOut(o, v);
                return meta?.hex ? (
                  <button key={v} type="button" title={valLabel(o, v)} aria-label={valLabel(o, v)} aria-pressed={on} onClick={() => setChoice((c) => ({ ...c, [o.name]: v }))}
                    className={`relative h-10 w-10 rounded-full border-2 transition hover:scale-110 ${on ? "border-[var(--o-accent)] ring-2 ring-[var(--o-accent)]/30" : "border-[var(--o-line)]"} ${out ? "opacity-40" : ""}`} style={{ background: meta.hex }}>
                    {on && <Icon name="check" className="absolute inset-0 m-auto h-4 w-4 text-white mix-blend-difference" />}
                  </button>
                ) : (
                  <button key={v} type="button" aria-pressed={on} data-active={on} onClick={() => setChoice((c) => ({ ...c, [o.name]: v }))} className={`o-chip !min-h-10 !px-4 ${out ? "opacity-45 line-through" : ""}`}>{valLabel(o, v)}</button>
                );
              })}
            </div>
          </div>
        ))}
      </div>

      <p className="mt-4 flex items-center gap-2 text-sm font-bold">
        {!open ? <span className="text-[var(--o-red)]">{p.shop.closedMessage || t("open.shopClosed")}</span>
          : available <= 0 ? <span className="text-[var(--o-red)]">{t("open.outOfStock")}</span>
          : available <= 5 ? <span className="text-[#b54708]">{t("open.onlyLeft", { n: available })}</span>
          : <span className="text-[#067647]">✓ {t("open.inStock")}</span>}
      </p>

      <div className="mt-4 flex items-center gap-3">
        <span className="text-sm font-extrabold">{t("open.quantity")}</span>
        <div className="flex h-11 items-center rounded-full border border-[var(--o-line)] bg-white">
          <button type="button" onClick={() => setQty((q) => Math.max(1, q - 1))} aria-label="−" className="grid h-11 w-11 place-items-center text-xl font-bold">−</button>
          <span className="w-8 text-center font-extrabold" aria-live="polite">{qty}</span>
          <button type="button" onClick={() => setQty((q) => Math.min(Math.min(available || 1, 20), q + 1))} aria-label="+" className="grid h-11 w-11 place-items-center text-xl font-bold">+</button>
        </div>
      </div>
      <div data-buybar className="fixed inset-x-0 z-30 flex items-center gap-3 border-t border-[var(--o-line)] bg-white/95 px-4 py-2.5 backdrop-blur-xl md:static md:mt-5 md:border-0 md:bg-transparent md:p-0 md:backdrop-blur-none" style={{ bottom: "calc(var(--tabbar-h) + var(--safe-b))" }}>
        <button type="button" disabled={!canBuy} onClick={() => add(false)} className="o-btn o-btn-dark flex-1 !min-h-12">
          <Icon name={added ? "check" : "cart"} className="h-5 w-5" />{added ? t("open.added") : t("open.addToCart")}
        </button>
        <button type="button" disabled={!canBuy} onClick={() => add(true)} className="o-btn flex-1 !min-h-12">{t("open.buyNow")}</button>
      </div>
      {pending && (
        <div role="alertdialog" aria-label={t("open.cart.otherShopTitle")} className="o-pop mt-3 rounded-2xl border border-[#fedf89] bg-[#fffaeb] p-4 text-sm">
          <p className="font-extrabold">{t("open.cart.otherShopTitle")}</p>
          <p className="mt-1 text-[var(--o-mute)]">{t("open.cart.otherShopBody", { other: pending.other, shop: p.shop.name })}</p>
          <div className="mt-3 flex flex-wrap gap-2">
            <button type="button" className="o-btn o-btn-sm" onClick={() => { replaceCart(pending.line); const go = pending.go; setPending(null); finish(go); }}>{t("open.cart.startNew")}</button>
            <button type="button" className="o-btn o-btn-ghost o-btn-sm" onClick={() => setPending(null)}>{t("open.cart.keepCurrent")}</button>
            <button type="button" className="o-link ms-auto self-center" onClick={() => router.push("/cart")}>{t("open.viewCart")}</button>
          </div>
        </div>
      )}
      {added && (
        <p className="o-pop mt-3 flex items-center justify-between rounded-2xl bg-[#e8f8f0] px-4 py-2.5 text-sm font-bold text-[#067647]">
          {t("open.addedToCart")}<Link href="/cart" className="underline">{t("open.viewCart")}</Link>
        </p>
      )}
    </div>
  );
}
