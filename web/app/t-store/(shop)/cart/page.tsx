"use client";

import Link from "next/link";
import { clearCart, removeLine, setQuantity, useCart } from "@/lib/cart";
import { money } from "@/lib/format";
import { useI18n } from "@/components/i18n-provider";
import { Button, Card, Empty, PageHeader } from "@/components/ui";

export default function Cart() {
  const { t, locale, currency } = useI18n();
  const lines = useCart();
  const total = lines.reduce((n, l) => n + l.unitPriceMinor * l.quantity, 0);
  if (lines.length === 0) return <Empty>{t("cart.empty")} <Link href="/products" className="font-medium text-brand underline">{t("shop.browse")}</Link></Empty>;
  return (
    <>
      <PageHeader title={t("nav.cart")} actions={<Button variant="ghost" size="sm" onClick={clearCart}>{t("cart.clear")}</Button>} />
      <div className="grid gap-6 lg:grid-cols-3">
        <div className="space-y-3 lg:col-span-2">
          {lines.map((l) => (
            <Card key={l.variantId} className="flex gap-4">
              <div className="h-20 w-20 shrink-0 overflow-hidden rounded-lg bg-slate-100">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                {l.imageUrl ? <img src={l.imageUrl} alt="" className="h-full w-full object-cover" /> : <div className="flex h-full items-center justify-center text-2xl text-slate-300">🛍️</div>}
              </div>
              <div className="min-w-0 flex-1">
                <Link href={`/products/${l.slug}`} className="font-medium hover:text-brand">{l.name}</Link>
                {l.variantLabel && <p className="text-sm text-slate-500">{l.variantLabel}</p>}
                <p className="mt-1 text-sm font-semibold">{money(l.unitPriceMinor, currency, locale)}</p>
                <div className="mt-2 flex items-center gap-3">
                  <div className="flex items-center rounded-lg border border-slate-300">
                    <button className="px-2.5 py-1" onClick={() => setQuantity(l.variantId, l.quantity - 1)} aria-label="-">−</button>
                    <span className="w-7 text-center text-sm">{l.quantity}</span>
                    <button className="px-2.5 py-1" onClick={() => setQuantity(l.variantId, l.quantity + 1)} aria-label="+">+</button>
                  </div>
                  <button className="text-sm text-red-600" onClick={() => removeLine(l.variantId)}>{t("common.delete")}</button>
                </div>
              </div>
              <p className="font-semibold">{money(l.unitPriceMinor * l.quantity, currency, locale)}</p>
            </Card>
          ))}
        </div>
        <Card className="h-fit space-y-3">
          <div className="flex justify-between text-sm"><span>{t("checkout.subtotal")}</span><b>{money(total, currency, locale)}</b></div>
          <p className="text-xs text-slate-500">{t("cart.shippingNote")}</p>
          <Link href="/checkout" className="block rounded-lg bg-brand py-3 text-center text-sm font-medium text-white">{t("cart.checkout")}</Link>
        </Card>
      </div>
    </>
  );
}
