"use client";

import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import { api } from "@/lib/client";
import { addToCart } from "@/lib/cart";
import { money } from "@/lib/format";
import { useAction, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Button, ErrorText } from "@/components/ui";

export type ProductDetail = {
  id: string; name: string; slug: string; description: string; shortDescription?: string; brand?: string; currency: string; hasVariants: boolean;
  options: { name: string; values: string[] }[];
  variants: { id: string; sku: string; priceMinor: number; compareAtPriceMinor?: number; comboKey: string; available: number }[];
  media: { url: string; altText?: string }[];
  reviews: { average: number; count: number; reviews: { rating: number; reviewText?: string; firstName: string }[] };
};

const img = (u: string) => (u.startsWith("/api/v1/") ? u.replace("/api/v1/", "/api/bff/") : u);

export function ProductBuy({ product }: { product: ProductDetail }) {
  const { t, locale } = useI18n();
  const router = useRouter();
  const { me } = useMe();
  const [choice, setChoice] = useState<Record<string, string>>(() => Object.fromEntries(product.options.map((o) => [o.name, o.values[0]])));
  const [qty, setQty] = useState(1);
  const [photo, setPhoto] = useState(0);
  const [added, setAdded] = useState(false);

  const combo = product.options.map((o) => `${o.name}=${choice[o.name]}`).join("|");
  const variant = useMemo(() => product.variants.find((v) => v.comboKey === combo), [product.variants, combo]);
  const inStock = (variant?.available ?? 0) > 0;

  const wish = useAction(async () => {
    if (!me) { router.push(`/login?next=${encodeURIComponent(`/products/${product.slug}`)}`); return; }
    await api(`shop/wishlist/${product.id}`, { method: "PUT" });
  });

  const add = () => {
    if (!variant) return;
    addToCart({ variantId: variant.id, productId: product.id, slug: product.slug, name: product.name, variantLabel: product.options.map((o) => choice[o.name]).join(" / "), unitPriceMinor: variant.priceMinor, quantity: qty, imageUrl: product.media[0]?.url ? img(product.media[0].url) : null });
    setAdded(true);
    setTimeout(() => setAdded(false), 2500);
  };

  return (
    <div className="grid gap-8 md:grid-cols-2">
      <div>
        <div className="aspect-square overflow-hidden rounded-2xl bg-slate-100">
          {product.media[photo] ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={img(product.media[photo].url)} alt={product.media[photo].altText ?? product.name} className="h-full w-full object-cover" />
          ) : <div className="flex h-full items-center justify-center text-6xl text-slate-300">🛍️</div>}
        </div>
        {product.media.length > 1 && (
          <div className="mt-3 flex gap-2">{product.media.map((m, i) => (
            // eslint-disable-next-line @next/next/no-img-element
            <button key={i} onClick={() => setPhoto(i)} className={`h-16 w-16 overflow-hidden rounded-lg border-2 ${i === photo ? "border-brand" : "border-transparent"}`}><img src={img(m.url)} alt="" className="h-full w-full object-cover" /></button>
          ))}</div>
        )}
      </div>
      <div className="space-y-5">
        <div>
          {product.brand && <p className="text-sm text-slate-500">{product.brand}</p>}
          <h1 className="text-2xl font-semibold">{product.name}</h1>
          {product.reviews.count > 0 && <p className="mt-1 text-sm text-amber-600">★ {product.reviews.average} ({product.reviews.count})</p>}
        </div>
        <p className="text-2xl font-bold text-brand">
          {variant ? money(variant.priceMinor, product.currency, locale) : "-"}
          {variant?.compareAtPriceMinor && <span className="ms-3 text-base font-normal text-slate-400 line-through">{money(variant.compareAtPriceMinor, product.currency, locale)}</span>}
        </p>
        {product.options.map((o) => (
          <div key={o.name}>
            <p className="mb-1.5 text-sm font-medium">{o.name}</p>
            <div className="flex flex-wrap gap-2">{o.values.map((v) => (
              <button key={v} onClick={() => setChoice({ ...choice, [o.name]: v })} className={`rounded-lg border px-3.5 py-1.5 text-sm ${choice[o.name] === v ? "border-brand bg-brand text-white" : "border-slate-300 bg-white hover:border-brand"}`}>{v}</button>
            ))}</div>
          </div>
        ))}
        <div className="flex items-center gap-3">
          <div className="flex items-center rounded-lg border border-slate-300 bg-white">
            <button className="px-3 py-2" onClick={() => setQty(Math.max(1, qty - 1))} aria-label="-">−</button>
            <span className="w-8 text-center text-sm">{qty}</span>
            <button className="px-3 py-2" onClick={() => setQty(Math.min(variant?.available ?? 1, qty + 1))} aria-label="+">+</button>
          </div>
          <Button onClick={add} disabled={!inStock} className="flex-1">{inStock ? t("shop.addToCart") : t("shop.outOfStock")}</Button>
          <Button variant="secondary" onClick={() => wish.run()} aria-label={t("shop.wishlist")}>♡</Button>
        </div>
        {variant && inStock && variant.available <= 5 && <p className="text-sm text-amber-700">{t("shop.onlyLeft", { n: variant.available })}</p>}
        {added && <Alert tone="green">{t("shop.added")}</Alert>}
        <ErrorText error={wish.error} />
        <p className="whitespace-pre-line text-sm leading-relaxed text-slate-700">{product.description}</p>
      </div>
    </div>
  );
}
