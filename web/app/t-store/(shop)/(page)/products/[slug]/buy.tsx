"use client";

import { useRouter } from "next/navigation";
import { useMemo, useState, useSyncExternalStore } from "react";
import { api } from "@/lib/client";
import { addToCart } from "@/lib/cart";
import { money } from "@/lib/format";
import { mediaUrl } from "@/lib/media";
import { whatsappLink } from "@/lib/whatsapp";
import { useAction, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Gallery } from "@/components/shop/gallery";
import { ErrorText } from "@/components/ui";

export type ProductDetail = {
  id: string; name: string; slug: string; description: string; shortDescription?: string; brand?: string; currency: string; hasVariants: boolean; badge?: string;
  sizeGuide?: string; tags?: string[]; specs?: { k: string; v: string }[];
  options: { name: string; values: string[] }[];
  variants: { id: string; sku: string; priceMinor: number; compareAtPriceMinor?: number; comboKey: string; available: number }[];
  media: { url: string; altText?: string; mediaBase?: string | null; mediaExt?: string | null }[];
  reviews: { average: number; count: number; reviews: { rating: number; reviewText?: string; firstName: string }[] };
};

const BADGE_CLASS: Record<string, string> = { NEW: "s-badge-new", SALE: "s-badge-sale", BEST_SELLER: "s-badge-best", LIMITED: "s-badge-dark" };

export function ProductBuy({ product, whatsapp, storeName, isOpen, returnDays }: { product: ProductDetail; whatsapp?: string; storeName: string; isOpen: boolean; returnDays: number }) {
  const { t, locale } = useI18n();
  const router = useRouter();
  const { me } = useMe();
  const [choice, setChoice] = useState<Record<string, string>>(() => Object.fromEntries(product.options.map((o) => [o.name, o.values[0]])));
  const [qty, setQty] = useState(1);
  const [added, setAdded] = useState(false);
  const [tab, setTab] = useState<"details" | "specs" | "size" | "delivery">("details");

  const combo = product.options.map((o) => `${o.name}=${choice[o.name]}`).join("|");
  const variant = useMemo(() => product.variants.find((v) => v.comboKey === combo), [product.variants, combo]);
  const inStock = (variant?.available ?? 0) > 0;
  const discount = variant?.compareAtPriceMinor && variant.compareAtPriceMinor > variant.priceMinor ? Math.round(((variant.compareAtPriceMinor - variant.priceMinor) * 100) / variant.compareAtPriceMinor) : 0;
  const badge = product.badge || (discount > 0 ? "SALE" : "");
  const label = product.options.map((o) => choice[o.name]).join(" / ");

  const wish = useAction(async () => {
    if (!me) { router.push(`/login?next=${encodeURIComponent(`/products/${product.slug}`)}`); return; }
    await api(`shop/wishlist/${product.id}`, { method: "PUT" });
  });

  const add = (go?: boolean) => {
    if (!variant || !isOpen) return;
    addToCart({ variantId: variant.id, productId: product.id, slug: product.slug, name: product.name, variantLabel: label, unitPriceMinor: variant.priceMinor, quantity: qty, imageUrl: mediaUrl(product.media[0], "thumb") });
    if (go) { router.push("/checkout"); return; }
    setAdded(true);
    setTimeout(() => setAdded(false), 2500);
  };

  // Read the address in a hydration-safe way: empty on the server, the real URL in the browser.
  const shareUrl = useSyncExternalStore(() => () => {}, () => window.location.href, () => "");
  const waMessage = `${storeName}\n${product.name}${label ? ` (${label})` : ""} × ${qty}\n${shareUrl}`;
  const tabs = [
    { key: "details" as const, label: t("product.details"), show: true },
    { key: "specs" as const, label: t("product.specs"), show: !!product.specs?.length },
    { key: "size" as const, label: t("product.sizeGuide"), show: !!product.sizeGuide },
    { key: "delivery" as const, label: t("product.deliveryReturns"), show: true },
  ].filter((x) => x.show);

  return (
    <>
      <div className="grid gap-8 lg:grid-cols-[1.05fr_1fr] lg:gap-14">
        <Gallery media={product.media} name={product.name} badge={badge ? <span className={`s-badge ${BADGE_CLASS[badge] ?? ""} !text-sm`}>{discount > 0 && badge === "SALE" ? `-${discount}%` : t(`badge.${badge}`)}</span> : null} />
        <div className="space-y-6">
          <div>
            {product.brand && <p className="s-eyebrow">{product.brand}</p>}
            <h1 className="mt-1 text-3xl font-extrabold leading-tight sm:text-4xl">{product.name}</h1>
            {product.reviews.count > 0 && <p className="mt-2 text-sm font-bold text-amber-600">{"★".repeat(Math.round(product.reviews.average))}<span className="text-slate-300">{"★".repeat(5 - Math.round(product.reviews.average))}</span> <span className="font-normal text-[var(--s-mute)]">{product.reviews.average} · {product.reviews.count} {t("shop.reviews")}</span></p>}
            {product.shortDescription && <p className="mt-3 text-[var(--s-mute)]">{product.shortDescription}</p>}
          </div>
          <div className="flex flex-wrap items-baseline gap-3">
            <span className="text-4xl font-extrabold" style={{ color: "var(--brand)" }}>{variant ? money(variant.priceMinor, product.currency, locale) : "-"}</span>
            {discount > 0 && variant?.compareAtPriceMinor && <><span className="text-xl text-[var(--s-mute)] line-through">{money(variant.compareAtPriceMinor, product.currency, locale)}</span><span className="rounded-full bg-red-50 px-2.5 py-0.5 text-sm font-extrabold text-[#e5484d]">{t("shop.save", { p: discount })}</span></>}
          </div>
          {product.options.map((o) => (
            <div key={o.name}>
              <p className="mb-2 text-sm font-extrabold">{o.name}: <span className="font-medium text-[var(--s-mute)]">{choice[o.name]}</span></p>
              <div className="flex flex-wrap gap-2">{o.values.map((v) => (
                <button key={v} onClick={() => { setChoice({ ...choice, [o.name]: v }); setQty(1); }} className="s-chip !px-5 !py-2.5 !text-sm" data-active={choice[o.name] === v}>{v}</button>
              ))}</div>
            </div>
          ))}
          <div className="flex flex-wrap items-center gap-3">
            <div className="flex items-center rounded-full border border-[var(--s-line)] bg-white">
              <button className="h-12 w-12 text-xl transition hover:text-[var(--brand)]" onClick={() => setQty(Math.max(1, qty - 1))} aria-label="-">−</button>
              <span className="w-8 text-center font-extrabold">{qty}</span>
              <button className="h-12 w-12 text-xl transition hover:text-[var(--brand)]" onClick={() => setQty(Math.min(Math.max(variant?.available ?? 1, 1), qty + 1))} aria-label="+">+</button>
            </div>
            <button onClick={() => add()} disabled={!inStock || !isOpen} className="s-btn min-w-48 flex-1 !py-3.5">{!isOpen ? t("shop.closedNow") : inStock ? `🛒 ${t("shop.addToCart")}` : t("shop.outOfStock")}</button>
            <button onClick={() => wish.run()} aria-label={t("shop.wishlist")} className="s-btn s-btn-ghost !h-12 !w-12 !p-0 text-lg">♡</button>
          </div>
          {inStock && isOpen && <button onClick={() => add(true)} className="s-btn s-btn-dark w-full !py-3.5">{t("shop.buyNow")}</button>}
          {variant && inStock && variant.available <= 5 && <p className="text-sm font-bold text-amber-700">🔥 {t("shop.onlyLeft", { n: variant.available })}</p>}
          {added && <div className="animate-pop rounded-2xl bg-emerald-50 px-4 py-3 text-sm font-bold text-emerald-800">✓ {t("shop.added")}</div>}
          <ErrorText error={wish.error} />
          {whatsapp && <a href={whatsappLink(whatsapp, waMessage)} target="_blank" rel="noopener noreferrer" className="flex items-center justify-center gap-2 rounded-full border-2 border-emerald-500 py-3 text-sm font-extrabold text-emerald-700 transition hover:bg-emerald-50">💬 {t("product.orderWhatsapp")}</a>}
          <ul className="grid gap-2 rounded-3xl bg-[var(--s-soft)] p-4 text-sm font-semibold sm:grid-cols-2">
            <li>💵 {t("trust.cod")}</li><li>🚚 {t("trust.deliveryText")}</li><li>↩️ {t("trust.returnsText", { n: returnDays })}</li><li>🔒 {t("product.secure")}</li>
          </ul>
        </div>
      </div>

      <section className="mt-12">
        <div className="flex gap-1 overflow-x-auto border-b border-[var(--s-line)]">
          {tabs.map((x) => <button key={x.key} onClick={() => setTab(x.key)} className={`relative whitespace-nowrap px-5 py-3 text-sm font-extrabold transition ${tab === x.key ? "" : "text-[var(--s-mute)] hover:text-[var(--s-ink)]"}`} style={tab === x.key ? { color: "var(--brand)" } : undefined}>{x.label}{tab === x.key && <span className="absolute inset-x-3 -bottom-px h-0.5 rounded-full" style={{ background: "var(--brand)" }} />}</button>)}
        </div>
        <div className="s-card mt-4 p-6 text-[15px] leading-relaxed">
          {tab === "details" && <p className="whitespace-pre-line">{product.description || t("product.noDescription")}</p>}
          {tab === "specs" && <dl className="grid gap-x-10 sm:grid-cols-2">{product.specs?.map((s, i) => <div key={i} className="flex justify-between gap-4 border-b border-[var(--s-line)] py-2.5"><dt className="text-[var(--s-mute)]">{s.k}</dt><dd className="font-bold">{s.v}</dd></div>)}</dl>}
          {tab === "size" && <p className="whitespace-pre-line">{product.sizeGuide}</p>}
          {tab === "delivery" && <div className="space-y-2"><p>🚚 {t("product.deliveryInfo")}</p><p>↩️ {t("product.returnInfo", { n: returnDays })}</p></div>}
        </div>
      </section>

      <div className="fixed inset-x-0 bottom-[3.9rem] z-30 border-t border-[var(--s-line)] bg-white/95 p-3 backdrop-blur-xl md:hidden">
        <div className="flex items-center gap-3">
          <div className="min-w-0 flex-1"><p className="truncate text-xs text-[var(--s-mute)]">{product.name}</p><p className="font-extrabold" style={{ color: "var(--brand)" }}>{variant ? money(variant.priceMinor, product.currency, locale) : "-"}</p></div>
          <button onClick={() => add()} disabled={!inStock || !isOpen} className="s-btn !px-7">{inStock ? t("shop.addToCart") : t("shop.outOfStock")}</button>
        </div>
      </div>
    </>
  );
}
