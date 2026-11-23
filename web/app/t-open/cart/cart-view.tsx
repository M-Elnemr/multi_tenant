"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { Icon } from "@/components/icons";
import { useI18n } from "@/components/i18n-provider";
import { money } from "@/lib/format";
import { errMsg, loadCoupon, oapi, removeLine, saveCoupon, setQuantity, clearCart, useOpenCart, type CartQuote } from "@/lib/open-client";

/** The cart: products of one shop (delivery and cash collection are per shop), with that shop's coupon. */
export function CartView() {
  const { t, locale } = useI18n();
  const router = useRouter();
  const lines = useOpenCart();
  const [mounted, setMounted] = useState(false);
  const [coupon, setCoupon] = useState("");
  const [draft, setDraft] = useState("");
  const [quote, setQuote] = useState<CartQuote | null>(null);
  const [problem, setProblem] = useState("");
  useEffect(() => { const id = setTimeout(() => { setMounted(true); setCoupon(loadCoupon()); }, 0); return () => clearTimeout(id); }, []);

  const sig = useMemo(() => JSON.stringify([lines.map((l) => [l.variantId, l.quantity]), coupon]), [lines, coupon]);
  useEffect(() => {
    if (!mounted || lines.length === 0) { const id = setTimeout(() => setQuote(null), 0); return () => clearTimeout(id); }
    let alive = true;
    const id = setTimeout(() => {
      oapi<CartQuote>("cart/quote", { body: { items: lines.map((l) => ({ variantId: l.variantId, quantity: l.quantity })), couponCode: coupon || undefined } })
        .then((q) => { if (alive) { setQuote(q); setProblem(""); } })
        .catch((e) => {
          if (!alive) return;
          // a coupon the shop rejects is dropped with a message, so the cart never sits on a coupon that is not applied
          if ((e as { code?: string }).code === "COUPON_INVALID" && coupon) { setCoupon(""); saveCoupon(""); setProblem(errMsg(t, e)); return; }
          setQuote(null); setProblem(errMsg(t, e));
        });
    }, 150);
    return () => { alive = false; clearTimeout(id); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sig, mounted]);

  const apply = () => { const c = draft.trim(); if (!c) return; setProblem(""); setCoupon(c); saveCoupon(c); setDraft(""); };
  const unapply = () => { setCoupon(""); saveCoupon(""); };

  if (!mounted) return <div className="o-container pt-8"><div className="o-skel h-8 w-40" /><div className="o-skel mt-5 h-48" /></div>;
  if (lines.length === 0) {
    return (
      <div className="o-container pt-16 text-center">
        <p className="text-6xl">🛒</p>
        <h1 className="mt-4 text-2xl font-black">{t("open.cart.empty")}</h1>
        <p className="o-mute mt-1">{t("open.cart.emptyHint")}</p>
        <Link href="/" className="o-btn mt-6">{t("open.cart.startShopping")}</Link>
      </div>
    );
  }
  const shop = lines[0];
  const currency = shop.currency;
  const subtotal = quote ? quote.subtotalMinor : lines.reduce((n, l) => n + l.unitPriceMinor * l.quantity, 0);
  const discount = quote?.discountMinor ?? 0;
  const total = subtotal - discount;
  const count = lines.reduce((n, l) => n + l.quantity, 0);
  const priceOf = (variantId: string, fallback: number) => quote?.lines.find((x) => x.variantId === variantId)?.unitPriceMinor ?? fallback;
  const blocked = !quote && !!problem;

  return (
    <div className="o-container pt-5 md:pt-8">
      <h1 className="text-2xl font-black md:text-3xl">{t("nav.cart")} <span className="text-base font-bold text-[var(--o-mute)]">({count})</span></h1>
      <div className="mt-5 grid items-start gap-6 lg:grid-cols-[1fr_22rem]">
        <section className="o-card overflow-hidden o-reveal">
          <header className="flex items-center justify-between gap-3 border-b border-[var(--o-line)] bg-[var(--o-soft)] px-4 py-3">
            <Link href={`/shop/${shop.shopSlug}`} className="flex min-w-0 items-center gap-2 font-extrabold hover:text-[var(--o-accent)]"><Icon name="bag" className="h-4 w-4 shrink-0" /><span className="truncate">{shop.shopName}</span></Link>
            <button type="button" onClick={clearCart} className="shrink-0 text-xs font-bold text-[var(--o-mute)] hover:text-[var(--o-red)]">{t("open.cart.clear")}</button>
          </header>
          {problem && <p role="alert" className="mx-4 mt-3 rounded-xl bg-[#fef3f2] px-3.5 py-2.5 text-sm font-bold text-[#b42318]">{problem}</p>}
          <ul className="divide-y divide-[var(--o-line)]">
            {lines.map((l) => (
              <li key={l.variantId} className="flex gap-3 p-4">
                <Link href={`/p/${l.shopSlug}/${l.slug}`} className="h-20 w-20 shrink-0 overflow-hidden rounded-2xl bg-[var(--o-soft)] md:h-24 md:w-24">
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  {l.image ? <img src={l.image} alt="" width={96} height={96} className="h-full w-full object-cover" /> : <span className="grid h-full place-items-center text-3xl opacity-40">🛍️</span>}
                </Link>
                <div className="flex min-w-0 flex-1 flex-col">
                  <Link href={`/p/${l.shopSlug}/${l.slug}`} className="line-clamp-2 text-sm font-bold leading-snug hover:text-[var(--o-accent)] md:text-[15px]">{l.name}</Link>
                  {l.variantLabel && <p className="o-mute mt-0.5 text-xs">{l.variantLabel}</p>}
                  <div className="mt-auto flex flex-wrap items-center justify-between gap-2 pt-2">
                    <div className="flex h-9 items-center rounded-full border border-[var(--o-line)]">
                      <button type="button" aria-label="−" onClick={() => (l.quantity > 1 ? setQuantity(l.variantId, l.quantity - 1) : removeLine(l.variantId))} className="grid h-9 w-9 place-items-center font-bold">{l.quantity > 1 ? "−" : <Icon name="close" className="h-3.5 w-3.5" />}</button>
                      <span className="w-7 text-center text-sm font-extrabold">{l.quantity}</span>
                      <button type="button" aria-label="+" onClick={() => setQuantity(l.variantId, l.quantity + 1)} className="grid h-9 w-9 place-items-center font-bold">+</button>
                    </div>
                    <p className="text-base font-extrabold">{money(priceOf(l.variantId, l.unitPriceMinor) * l.quantity, l.currency, locale)}</p>
                  </div>
                </div>
              </li>
            ))}
          </ul>
          <div className="border-t border-[var(--o-line)] p-4">
            {coupon ? (
              <p className="flex items-center justify-between rounded-xl bg-[#e8f8f0] px-3.5 py-2.5 text-sm font-bold text-[#067647]">
                <span>🏷️ {coupon}{discount > 0 ? ` · −${money(discount, currency, locale)}` : ""}</span>
                <button type="button" onClick={unapply} className="text-xs underline">{t("open.cart.removeCoupon")}</button>
              </p>
            ) : (
              <form onSubmit={(e) => { e.preventDefault(); apply(); }} className="flex gap-2">
                <input value={draft} onChange={(e) => setDraft(e.target.value)} placeholder={t("open.cart.couponPlaceholder")} aria-label={t("open.cart.couponPlaceholder")} className="o-input !min-h-10 !py-2 uppercase" />
                <button type="submit" className="o-btn o-btn-dark o-btn-sm shrink-0">{t("open.cart.applyCoupon")}</button>
              </form>
            )}
          </div>
        </section>

        <aside className="o-card sticky top-36 p-5">
          <h2 className="text-lg font-extrabold">{t("open.cart.summary")}</h2>
          <dl className="mt-4 grid gap-2.5 text-sm">
            <div className="flex justify-between"><dt className="o-mute">{t("open.cart.items", { n: count })}</dt><dd className="font-bold">{money(subtotal, currency, locale)}</dd></div>
            {discount > 0 && <div className="flex justify-between text-[#067647]"><dt>{t("open.cart.discount")}</dt><dd className="font-bold">−{money(discount, currency, locale)}</dd></div>}
            <div className="flex justify-between"><dt className="o-mute">{t("open.cart.shipping")}</dt><dd className="font-semibold">{t("open.cart.atCheckout")}</dd></div>
          </dl>
          <div className="mt-4 flex items-baseline justify-between border-t border-[var(--o-line)] pt-4"><span className="font-extrabold">{t("open.cart.estimatedTotal")}</span><span className="text-2xl font-black">{money(total, currency, locale)}</span></div>
          <button type="button" disabled={blocked} onClick={() => router.push("/checkout")} className="o-btn o-btn-block mt-4 !min-h-12">{t("open.cart.checkout")}<Icon name="arrow" className="h-4 w-4 rtl:rotate-180" /></button>
          <p className="o-mute mt-3 flex items-center gap-1.5 text-xs"><Icon name="wallet" className="h-4 w-4" />{t("open.trust.codSub")}</p>
          <p className="o-mute mt-1.5 text-xs">{t("open.cart.oneShopNote", { shop: shop.shopName })}</p>
        </aside>
      </div>

      <div data-buybar className="fixed inset-x-0 z-30 flex items-center justify-between gap-3 border-t border-[var(--o-line)] bg-white/95 px-4 py-2.5 backdrop-blur-xl md:hidden" style={{ bottom: "calc(var(--tabbar-h) + var(--safe-b))" }}>
        <div><p className="o-mute text-xs">{t("open.cart.estimatedTotal")}</p><p className="text-lg font-black">{money(total, currency, locale)}</p></div>
        <button type="button" disabled={blocked} onClick={() => router.push("/checkout")} className="o-btn !min-h-12 max-w-[14rem] flex-1">{t("open.cart.checkout")}</button>
      </div>
    </div>
  );
}
