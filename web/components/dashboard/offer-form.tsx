"use client";

import { useEffect, useState, type ReactNode } from "react";
import { api } from "@/lib/client";
import { fromMinor, money, toMinor } from "@/lib/format";
import { useFieldErrors } from "@/lib/use-field-errors";
import { useAction, useApi } from "../hooks";
import { useI18n } from "../i18n-provider";
import { Icon } from "../icons";
import { Button, ErrorText, Field, Input, Select, Textarea } from "../ui";
import { TaxonomyPicker, type TaxonomyNode } from "./taxonomy-picker";

export const OFFER_TYPES = ["PERCENT_OFF", "FIXED_OFF", "FLASH_SALE", "BUY_X_GET_Y", "BUNDLE_PRICE", "TIERED_QTY", "ORDER_THRESHOLD", "FREE_SHIPPING", "GIFT_WITH_PURCHASE", "FIRST_ORDER", "COUPON"] as const;
export type OfferType = (typeof OFFER_TYPES)[number];

/** Look of each type: emoji, gradient, and whether it works on items (sale price) or on the cart. */
export const TYPE_LOOK: Record<OfferType, { emoji: string; from: string; to: string; scope: "item" | "cart" | "code" }> = {
  PERCENT_OFF: { emoji: "🏷️", from: "#f43f5e", to: "#fb923c", scope: "item" },
  FIXED_OFF: { emoji: "💸", from: "#10b981", to: "#14b8a6", scope: "item" },
  FLASH_SALE: { emoji: "⚡", from: "#f59e0b", to: "#ef4444", scope: "item" },
  BUY_X_GET_Y: { emoji: "🎁", from: "#8b5cf6", to: "#ec4899", scope: "cart" },
  BUNDLE_PRICE: { emoji: "📦", from: "#0ea5e9", to: "#6366f1", scope: "cart" },
  TIERED_QTY: { emoji: "📈", from: "#22c55e", to: "#84cc16", scope: "cart" },
  ORDER_THRESHOLD: { emoji: "🛒", from: "#06b6d4", to: "#3b82f6", scope: "cart" },
  FREE_SHIPPING: { emoji: "🚚", from: "#0d9488", to: "#22d3ee", scope: "cart" },
  GIFT_WITH_PURCHASE: { emoji: "🎀", from: "#ec4899", to: "#f472b6", scope: "cart" },
  FIRST_ORDER: { emoji: "👋", from: "#6366f1", to: "#a855f7", scope: "cart" },
  COUPON: { emoji: "🎟️", from: "#475569", to: "#0f172a", scope: "code" },
};

export type Offer = {
  id: string; kind: "OFFER" | "COUPON"; type: OfferType; name: string; code?: string; active: boolean; draft: boolean; state: string; startsAt?: string | null; endsAt?: string | null;
  stackable?: boolean; appliesTo?: string; productIds?: string[]; categoryIds?: number[]; products?: { id: string; name: string }[]; badgeText?: string; description?: string; showBanner?: boolean;
  config: Record<string, unknown>; maxUses?: number | null; usageCount: number; discountGivenMinor?: number; priority?: number;
};

type Product = { id: string; name: string; minPriceMinor?: number };
type Cat = { id: number; name: string };

/** <input type="datetime-local"> value in the visitor's own clock, from an ISO instant (and back). */
const toLocal = (iso?: string | null) => { if (!iso) return ""; const d = new Date(iso); const p = (n: number) => String(n).padStart(2, "0"); return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`; };
const fromLocal = (v: string) => (v ? new Date(v).toISOString() : null);
const num = (v: unknown) => (v === undefined || v === null || v === "" ? "" : String(v));

export { offerSummary } from "@/lib/offers";
import { offerSummary } from "@/lib/offers";

/** The type gallery: one big tappable card per kind of offer. */
export function TypePicker({ onPick }: { onPick: (t: OfferType) => void }) {
  const { t } = useI18n();
  const groups: { title: string; types: OfferType[] }[] = [
    { title: t("offer.grp.item"), types: ["PERCENT_OFF", "FIXED_OFF", "FLASH_SALE"] },
    { title: t("offer.grp.cart"), types: ["BUY_X_GET_Y", "BUNDLE_PRICE", "TIERED_QTY", "ORDER_THRESHOLD"] },
    { title: t("offer.grp.extra"), types: ["FREE_SHIPPING", "GIFT_WITH_PURCHASE", "FIRST_ORDER", "COUPON"] },
  ];
  return (
    <div className="space-y-7">
      {groups.map((g) => (
        <div key={g.title}>
          <h3 className="mb-3 text-sm font-bold uppercase tracking-wider text-slate-500">{g.title}</h3>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            {g.types.map((ty, i) => {
              const l = TYPE_LOOK[ty];
              return (
                <button key={ty} type="button" onClick={() => onPick(ty)} style={{ animationDelay: `${i * 50}ms` }}
                  className="group relative animate-fade-up overflow-hidden rounded-2xl bg-white p-5 text-start shadow-card ring-1 ring-slate-100 transition hover:-translate-y-1 hover:shadow-lift hover:ring-brand/30">
                  <span className="pointer-events-none absolute -end-8 -top-8 h-28 w-28 rounded-full opacity-15 blur-xl transition group-hover:opacity-30" style={{ background: `linear-gradient(135deg, ${l.from}, ${l.to})` }} />
                  <span className="mb-3 grid h-12 w-12 place-items-center rounded-2xl text-2xl text-white shadow-lg" style={{ background: `linear-gradient(135deg, ${l.from}, ${l.to})` }}>{l.emoji}</span>
                  <p className="font-bold text-ink">{t(`offer.type.${ty}`)}</p>
                  <p className="mt-1 text-sm leading-relaxed text-slate-500">{t(`offer.desc.${ty}`)}</p>
                  <p className="mt-3 inline-flex rounded-full bg-slate-100 px-2.5 py-1 text-xs font-semibold text-slate-600">{t(`offer.ex.${ty}`)}</p>
                </button>
              );
            })}
          </div>
        </div>
      ))}
    </div>
  );
}

/** Search the shop's products and pick several. */
function ProductPicker({ value, onChange, products }: { value: string[]; onChange: (ids: string[], picked: Product[]) => void; products: Product[] }) {
  const { t } = useI18n();
  const [q, setQ] = useState("");
  const res = useApi<{ data: Product[] }>(q.trim().length >= 1 ? `store/products?q=${encodeURIComponent(q.trim())}&pageSize=8` : "store/products?pageSize=8");
  const known = new Map(products.map((p) => [p.id, p]));
  return (
    <div className="space-y-2">
      <div className="flex flex-wrap gap-1.5">
        {value.map((id) => (
          <span key={id} className="inline-flex items-center gap-1 rounded-full bg-brand-soft py-1 pe-1 ps-3 text-xs font-semibold text-brand-dark">
            {known.get(id)?.name ?? "…"}
            <button type="button" className="grid h-5 w-5 place-items-center rounded-full hover:bg-brand/20" onClick={() => onChange(value.filter((x) => x !== id), products.filter((p) => p.id !== id))}><Icon name="close" className="h-3 w-3" /></button>
          </span>
        ))}
      </div>
      <Input placeholder={t("offer.searchProducts")} value={q} onChange={(e) => setQ(e.target.value)} />
      <div className="max-h-52 overflow-y-auto rounded-xl border border-slate-100">
        {res.data?.data.map((p) => {
          const on = value.includes(p.id);
          return (
            <button key={p.id} type="button" onClick={() => onChange(on ? value.filter((x) => x !== p.id) : [...value, p.id], on ? products.filter((x) => x.id !== p.id) : [...products, p])}
              className={`flex w-full items-center justify-between gap-2 border-b border-slate-50 px-3 py-2.5 text-start text-sm last:border-0 ${on ? "bg-brand-soft font-semibold text-brand-dark" : "hover:bg-slate-50"}`}>
              <span className="truncate">{p.name}</span>{on && <Icon name="check" className="h-4 w-4" />}
            </button>
          );
        })}
      </div>
    </div>
  );
}

/** Live storefront preview of the offer as shoppers will see it. */
function Preview({ type, name, badge, config, endsAt, fmt }: { type: OfferType; name: string; badge: string; config: Record<string, unknown>; endsAt: string; fmt: (m: number) => string }) {
  const { t } = useI18n();
  const l = TYPE_LOOK[type];
  const price = 50000;
  const sale = type === "PERCENT_OFF" || type === "FLASH_SALE" ? Math.round(price * (1 - Number(config.percent || 0) / 100)) : type === "FIXED_OFF" ? Math.max(0, price - Number(config.amountMinor || 0)) : null;
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => { const id = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(id); }, []);
  const left = endsAt ? Math.max(0, new Date(endsAt).getTime() - now) : 0;
  const hh = Math.floor(left / 3600000), mm = Math.floor((left % 3600000) / 60000), ss = Math.floor((left % 60000) / 1000);
  const p2 = (n: number) => String(n).padStart(2, "0");
  return (
    <div className="space-y-3">
      <p className="text-xs font-bold uppercase tracking-wider text-slate-500">{t("offer.preview")}</p>
      {(type === "FLASH_SALE" || l.scope === "cart" || type === "FREE_SHIPPING") && (
        <div className="overflow-hidden rounded-2xl p-4 text-white shadow-lg" style={{ background: `linear-gradient(120deg, ${l.from}, ${l.to})` }}>
          <p className="text-xs font-semibold opacity-90">{l.emoji} {name || t(`offer.type.${type}`)}</p>
          <p className="mt-1 text-lg font-extrabold leading-tight">{offerSummary({ type, config }, t, fmt) || t(`offer.type.${type}`)}</p>
          {type === "FLASH_SALE" && endsAt && <div className="mt-3 flex gap-1.5 font-mono text-lg font-bold" dir="ltr">{[hh, mm, ss].map((n, i) => <span key={i} className="rounded-lg bg-black/25 px-2 py-1">{p2(n)}</span>)}</div>}
        </div>
      )}
      {l.scope !== "code" && (
        <div className="mx-auto w-52 overflow-hidden rounded-2xl bg-white shadow-card ring-1 ring-slate-100">
          <div className="relative grid h-36 place-items-center bg-gradient-to-br from-slate-100 to-slate-200 text-5xl">👕
            {(badge || sale !== null) && <span className="absolute start-2 top-2 rounded-full px-2.5 py-1 text-xs font-extrabold text-white shadow" style={{ background: `linear-gradient(135deg, ${l.from}, ${l.to})` }}>{badge || (config.percent ? `-${config.percent}%` : t("offer.badgeSale"))}</span>}
          </div>
          <div className="p-3">
            <p className="text-sm font-semibold text-slate-800">{t("offer.sampleProduct")}</p>
            {sale !== null ? (
              <p className="mt-1 flex items-baseline gap-2"><span className="text-base font-extrabold text-rose-600">{fmt(sale)}</span><span className="text-xs text-slate-400 line-through">{fmt(price)}</span></p>
            ) : <p className="mt-1 text-base font-extrabold text-ink">{fmt(price)}</p>}
            {l.scope === "cart" && <p className="mt-1.5 rounded-lg bg-violet-50 px-2 py-1 text-[11px] font-semibold text-violet-700">{offerSummary({ type, config }, t, fmt)}</p>}
          </div>
        </div>
      )}
      {type === "COUPON" && (
        <div className="relative mx-auto flex w-64 items-center gap-3 rounded-2xl border-2 border-dashed border-slate-300 bg-white p-4">
          <span className="text-3xl">🎟️</span>
          <div><p className="font-mono text-lg font-extrabold tracking-widest text-ink" dir="ltr">{String(config.code || "SAVE10").toUpperCase()}</p><p className="text-xs text-slate-500">{offerSummary({ type, config }, t, fmt)}</p></div>
        </div>
      )}
    </div>
  );
}

/** Create or edit one offer. A coupon is saved through the coupons API. */
export function OfferForm({ type, initial, onSaved, onBack }: { type: OfferType; initial?: Offer; onSaved: () => void; onBack?: () => void }) {
  const { t, locale, currency } = useI18n();
  const fmt = (m: number) => money(m, currency, locale);
  const fe = useFieldErrors();
  const c0 = (initial?.config ?? {}) as Record<string, unknown>;
  const [f, setF] = useState({
    name: initial?.name ?? "", description: initial?.description ?? "", badgeText: initial?.badgeText ?? "", appliesTo: initial?.appliesTo ?? "ALL",
    startsAt: toLocal(initial?.startsAt), endsAt: toLocal(initial?.endsAt), stackable: initial?.stackable ?? false, showBanner: initial?.showBanner ?? type === "FLASH_SALE", maxUses: num(initial?.maxUses),
    // settings (money as text in pounds)
    percent: num(c0.percent), amount: c0.amountMinor ? fromMinor(Number(c0.amountMinor)) : "", buyQty: num(c0.buyQty ?? 1), getQty: num(c0.getQty ?? 1), getPercent: num(c0.getPercent ?? 100),
    bundle: c0.bundlePriceMinor ? fromMinor(Number(c0.bundlePriceMinor)) : "", minSubtotal: c0.minSubtotalMinor ? fromMinor(Number(c0.minSubtotalMinor)) : "", discountType: String(c0.discountType ?? "PERCENT"),
    value: c0.value === undefined ? "" : c0.discountType === "FIXED" ? fromMinor(Number(c0.value)) : String(c0.value), maxDiscount: c0.maxDiscountMinor ? fromMinor(Number(c0.maxDiscountMinor)) : "",
    giftQty: num(c0.giftQty ?? 1), code: "", perCustomer: "",
  });
  const [tiers, setTiers] = useState<{ minQty: string; percent: string }[]>(((c0.tiers as { minQty: number; percent: number }[]) ?? [{ minQty: 2, percent: 10 }, { minQty: 3, percent: 15 }]).map((x) => ({ minQty: String(x.minQty), percent: String(x.percent) })));
  const [productIds, setProductIds] = useState<string[]>(initial?.productIds ?? []);
  const [products, setProducts] = useState<Product[]>(initial?.products ?? []);
  const [cats, setCats] = useState<Cat[]>([]);
  const [gift, setGift] = useState<{ productId: string; variantId: string }>({ productId: "", variantId: String(c0.giftVariantId ?? "") });
  const giftProduct = useApi<{ variants: { id: string; comboKey: string; status: string }[] }>(gift.productId ? `store/products/${gift.productId}` : null);
  const set = (p: Partial<typeof f>) => { setF((x) => ({ ...x, ...p })); for (const k of Object.keys(p)) fe.clear(k); };
  const look = TYPE_LOOK[type];

  const config = (): Record<string, unknown> => {
    switch (type) {
      case "PERCENT_OFF": case "FLASH_SALE": return { percent: Number(f.percent) };
      case "FIXED_OFF": return { amountMinor: toMinor(f.amount) };
      case "BUY_X_GET_Y": return { buyQty: Number(f.buyQty), getQty: Number(f.getQty), getPercent: Number(f.getPercent || 100) };
      case "BUNDLE_PRICE": return { bundlePriceMinor: toMinor(f.bundle) };
      case "TIERED_QTY": return { tiers: tiers.map((x) => ({ minQty: Number(x.minQty), percent: Number(x.percent) })) };
      case "ORDER_THRESHOLD": return { minSubtotalMinor: toMinor(f.minSubtotal), discountType: f.discountType, value: f.discountType === "FIXED" ? toMinor(f.value) : Number(f.value), maxDiscountMinor: f.maxDiscount ? toMinor(f.maxDiscount) : undefined };
      case "FREE_SHIPPING": return { minSubtotalMinor: f.minSubtotal ? toMinor(f.minSubtotal) : 0 };
      case "GIFT_WITH_PURCHASE": return { minSubtotalMinor: f.minSubtotal ? toMinor(f.minSubtotal) : 0, giftVariantId: gift.variantId, giftQty: Number(f.giftQty || 1) };
      case "FIRST_ORDER": case "COUPON": return { discountType: f.discountType, value: f.discountType === "FIXED" ? toMinor(f.value) : Number(f.value), code: f.code };
    }
  };

  const save = useAction(async (draft: boolean) => {
    const local: Record<string, string | false> = { name: type !== "COUPON" && !f.name.trim() && t("srv.Give the offer a name") };
    if (f.startsAt && f.endsAt && f.endsAt <= f.startsAt) local.endsAt = t("srv.The end must be after the start");
    if (type === "FLASH_SALE" && !f.endsAt) local.endsAt = t("srv.A flash sale needs an end time for its countdown");
    if (type === "COUPON") {
      if (!/^[A-Za-z0-9_-]{3,40}$/.test(f.code.trim())) local.code = t("offer.codeRule");
      if (!(Number(f.value) > 0) || (f.discountType === "PERCENT" && Number(f.value) > 100)) local["config.value"] = t("offer.valueRule");
    }
    if (!fe.check(local)) return;
    try {
      if (type === "COUPON") {
        await api("store/coupons", { body: { code: f.code.trim().toUpperCase(), discountType: f.discountType, value: f.discountType === "PERCENT" ? Number(f.value) : toMinor(f.value), minOrderMinor: f.minSubtotal ? toMinor(f.minSubtotal) : 0,
          startsAt: fromLocal(f.startsAt), endsAt: fromLocal(f.endsAt), maxRedemptions: f.maxUses ? Number(f.maxUses) : undefined, perCustomerLimit: f.perCustomer ? Number(f.perCustomer) : undefined } });
      } else {
        const body = { name: f.name.trim(), type, active: !draft, draft, startsAt: fromLocal(f.startsAt), endsAt: fromLocal(f.endsAt), stackable: f.stackable, appliesTo: type === "BUNDLE_PRICE" ? "PRODUCTS" : f.appliesTo,
          productIds, categoryIds: cats.map((x) => x.id).concat(initial?.categoryIds ?? []).filter((v, i, a) => a.indexOf(v) === i), badgeText: f.badgeText, description: f.description, showBanner: f.showBanner,
          config: config(), maxUses: f.maxUses ? Number(f.maxUses) : null };
        if (initial) await api(`store/offers/${initial.id}`, { method: "PUT", body });
        else await api("store/offers", { body });
      }
    } catch (e) { if (!fe.fromError(e)) throw e; return; }
    onSaved();
  });

  const cardCls = "space-y-4 rounded-2xl bg-white p-5 shadow-card";
  const section = (title: string, children: ReactNode, step?: number) => (
    <section className={cardCls}><h3 className="flex items-center gap-2 font-bold text-ink">{step && <span className="grid h-6 w-6 place-items-center rounded-full bg-brand text-xs text-white">{step}</span>}{title}</h3>{children}</section>
  );
  const discountKind = (
    <div className="grid gap-3 sm:grid-cols-2">
      <Field label={t("offer.discountKind")}><Select value={f.discountType} onChange={(e) => set({ discountType: e.target.value })}><option value="PERCENT">{t("offer.kindPercent")}</option><option value="FIXED">{t("offer.kindFixed", { c: currency })}</option></Select></Field>
      <Field name="config.value" error={fe.err("config.value")} label={f.discountType === "PERCENT" ? t("offer.percent") : t("offer.amount")}><Input dir="ltr" inputMode="decimal" value={f.value} onChange={(e) => set({ value: e.target.value })} placeholder={f.discountType === "PERCENT" ? "10" : "50"} /></Field>
    </div>
  );

  return (
    <form onSubmit={(e) => { e.preventDefault(); void save.run(false); }} className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_320px]">
      <div className="space-y-5">
        <div className="flex items-center gap-3 rounded-2xl p-4 text-white shadow-lg" style={{ background: `linear-gradient(120deg, ${look.from}, ${look.to})` }}>
          <span className="text-3xl">{look.emoji}</span>
          <div className="min-w-0 flex-1"><p className="text-lg font-extrabold">{t(`offer.type.${type}`)}</p><p className="text-sm opacity-90">{t(`offer.desc.${type}`)}</p></div>
          {onBack && !initial && <button type="button" onClick={onBack} className="rounded-xl bg-white/20 px-3 py-2 text-sm font-semibold hover:bg-white/30">{t("offer.changeType")}</button>}
        </div>

        {section(t("offer.s.basics"), (
          <>
            {type === "COUPON" ? (
              <Field name="code" error={fe.err("code")} label={t("offer.code")} hint={t("offer.codeHint")}><Input dir="ltr" className="font-mono uppercase tracking-widest" value={f.code} onChange={(e) => set({ code: e.target.value })} placeholder="RAMADAN25" /></Field>
            ) : (
              <Field name="name" error={fe.err("name")} label={t("offer.name")} hint={t("offer.nameHint")}><Input value={f.name} onChange={(e) => set({ name: e.target.value })} placeholder={t(`offer.ph.${type}`)} /></Field>
            )}
            {type !== "COUPON" && <Field label={t("offer.descriptionLabel")}><Textarea rows={2} value={f.description} onChange={(e) => set({ description: e.target.value })} placeholder={t("offer.descriptionPh")} /></Field>}
          </>
        ), 1)}

        {section(t("offer.s.value"), (
          <>
            {(type === "PERCENT_OFF" || type === "FLASH_SALE") && (
              <Field name="config.percent" error={fe.err("config.percent")} label={t("offer.percent")}>
                <div className="flex flex-wrap items-center gap-2">
                  {[10, 15, 20, 25, 30, 50].map((p) => <button key={p} type="button" onClick={() => set({ percent: String(p) })} className={`chip ${f.percent === String(p) ? "chip-on" : ""}`}>{p}%</button>)}
                  <Input dir="ltr" inputMode="numeric" className="!w-24" value={f.percent} onChange={(e) => set({ percent: e.target.value.replace(/[^0-9]/g, "") })} placeholder="%" />
                </div>
              </Field>
            )}
            {type === "FIXED_OFF" && <Field name="config.amountMinor" error={fe.err("config.amountMinor")} label={t("offer.amountPerItem", { c: currency })}><Input dir="ltr" inputMode="decimal" value={f.amount} onChange={(e) => set({ amount: e.target.value })} placeholder="50" /></Field>}
            {type === "BUY_X_GET_Y" && (
              <div className="grid gap-3 sm:grid-cols-3">
                <Field name="config.buyQty" error={fe.err("config.buyQty")} label={t("offer.buyQty")}><Input dir="ltr" inputMode="numeric" value={f.buyQty} onChange={(e) => set({ buyQty: e.target.value })} /></Field>
                <Field name="config.getQty" error={fe.err("config.getQty")} label={t("offer.getQty")}><Input dir="ltr" inputMode="numeric" value={f.getQty} onChange={(e) => set({ getQty: e.target.value })} /></Field>
                <Field name="config.getPercent" error={fe.err("config.getPercent")} label={t("offer.getPercent")} hint={t("offer.getPercentHint")}><Input dir="ltr" inputMode="numeric" value={f.getPercent} onChange={(e) => set({ getPercent: e.target.value })} /></Field>
              </div>
            )}
            {type === "BUNDLE_PRICE" && <Field name="config.bundlePriceMinor" error={fe.err("config.bundlePriceMinor")} label={t("offer.bundlePrice", { c: currency })} hint={t("offer.bundleHint")}><Input dir="ltr" inputMode="decimal" value={f.bundle} onChange={(e) => set({ bundle: e.target.value })} /></Field>}
            {type === "TIERED_QTY" && (
              <div data-field="config.tiers" className="space-y-2">
                {tiers.map((tr, i) => (
                  <div key={i} className="flex items-center gap-2 rounded-xl bg-slate-50 p-2 text-sm">
                    <span className="text-slate-500">{t("offer.tierBuy")}</span><Input dir="ltr" inputMode="numeric" className="!w-20" value={tr.minQty} onChange={(e) => setTiers(tiers.map((x, k) => (k === i ? { ...x, minQty: e.target.value } : x)))} />
                    <span className="text-slate-500">{t("offer.tierGet")}</span><Input dir="ltr" inputMode="numeric" className="!w-20" value={tr.percent} onChange={(e) => setTiers(tiers.map((x, k) => (k === i ? { ...x, percent: e.target.value } : x)))} /><span>%</span>
                    <button type="button" className="ms-auto grid h-8 w-8 place-items-center rounded-full text-slate-400 hover:bg-white hover:text-red-500" onClick={() => setTiers(tiers.filter((_, k) => k !== i))}><Icon name="close" className="h-4 w-4" /></button>
                  </div>
                ))}
                {fe.err("config.tiers") && <p className="text-xs font-medium text-red-600">{fe.err("config.tiers")}</p>}
                <Button type="button" size="sm" variant="secondary" onClick={() => setTiers([...tiers, { minQty: String((Number(tiers[tiers.length - 1]?.minQty) || 1) + 2), percent: "" }])}>+ {t("offer.addTier")}</Button>
              </div>
            )}
            {(type === "ORDER_THRESHOLD" || type === "FREE_SHIPPING" || type === "GIFT_WITH_PURCHASE" || type === "COUPON") && (
              <Field name="config.minSubtotalMinor" error={fe.err("config.minSubtotalMinor")} label={t("offer.minSubtotal", { c: currency })} hint={type === "ORDER_THRESHOLD" ? undefined : t("offer.minSubtotalHint")}><Input dir="ltr" inputMode="decimal" value={f.minSubtotal} onChange={(e) => set({ minSubtotal: e.target.value })} placeholder="500" /></Field>
            )}
            {(type === "ORDER_THRESHOLD" || type === "FIRST_ORDER" || type === "COUPON") && discountKind}
            {type === "ORDER_THRESHOLD" && f.discountType === "PERCENT" && <Field label={t("offer.maxDiscount", { c: currency })}><Input dir="ltr" inputMode="decimal" value={f.maxDiscount} onChange={(e) => set({ maxDiscount: e.target.value })} placeholder={t("offer.optional")} /></Field>}
            {type === "GIFT_WITH_PURCHASE" && (
              <div data-field="config.giftVariantId" className="space-y-2">
                <p className="text-sm font-semibold text-slate-700">{t("offer.giftPick")}</p>
                <ProductPicker value={gift.productId ? [gift.productId] : []} products={products} onChange={(ids, ps) => { const id = ids[ids.length - 1] ?? ""; setGift({ productId: id, variantId: "" }); setProducts(ps); }} />
                {giftProduct.data && <Select value={gift.variantId} onChange={(e) => setGift({ ...gift, variantId: e.target.value })}><option value="">{t("offer.giftWhich")}</option>{giftProduct.data.variants.filter((v) => v.status === "ACTIVE").map((v) => <option key={v.id} value={v.id}>{v.comboKey.replace(/\|/g, " / ").replace(/[^/|]*=/g, "") || t("offer.giftOnly")}</option>)}</Select>}
                {fe.err("config.giftVariantId") && <p className="text-xs font-medium text-red-600">{fe.err("config.giftVariantId")}</p>}
                <Field label={t("offer.giftQty")}><Input dir="ltr" inputMode="numeric" className="!w-24" value={f.giftQty} onChange={(e) => set({ giftQty: e.target.value })} /></Field>
              </div>
            )}
          </>
        ), 2)}

        {type !== "COUPON" && type !== "FIRST_ORDER" && type !== "ORDER_THRESHOLD" && section(t("offer.s.products"), (
          <>
            {type !== "BUNDLE_PRICE" && (
              <div className="flex flex-wrap gap-2">{["ALL", "PRODUCTS", "CATEGORIES"].map((a) => <button key={a} type="button" onClick={() => set({ appliesTo: a })} className={`chip ${f.appliesTo === a ? "chip-on" : ""}`}>{t(`offer.applies.${a}`)}</button>)}</div>
            )}
            {(f.appliesTo === "PRODUCTS" || type === "BUNDLE_PRICE") && <div data-field="productIds"><ProductPicker value={productIds} products={products} onChange={(ids, ps) => { setProductIds(ids); setProducts(ps); fe.clear("productIds"); }} />{fe.err("productIds") && <p className="mt-1 text-xs font-medium text-red-600">{fe.err("productIds")}</p>}</div>}
            {f.appliesTo === "CATEGORIES" && type !== "BUNDLE_PRICE" && (
              <div data-field="categoryIds" className="space-y-2">
                <div className="flex flex-wrap gap-1.5">{cats.map((c) => <span key={c.id} className="inline-flex items-center gap-1 rounded-full bg-brand-soft py-1 pe-1 ps-3 text-xs font-semibold text-brand-dark">{c.name}<button type="button" className="grid h-5 w-5 place-items-center rounded-full hover:bg-brand/20" onClick={() => setCats(cats.filter((x) => x.id !== c.id))}><Icon name="close" className="h-3 w-3" /></button></span>)}
                  {(initial?.categoryIds?.length ?? 0) > 0 && cats.length === 0 && <span className="text-xs text-slate-500">{t("offer.catsKept", { n: initial!.categoryIds!.length })}</span>}</div>
                <TaxonomyPicker value={null} onChange={(n: TaxonomyNode) => { if (!cats.some((c) => c.id === n.id)) setCats([...cats, { id: n.id, name: n.name || n.breadcrumb }]); fe.clear("categoryIds"); }} />
                {fe.err("categoryIds") && <p className="text-xs font-medium text-red-600">{fe.err("categoryIds")}</p>}
              </div>
            )}
          </>
        ), 3)}

        {section(t("offer.s.when"), (
          <>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field name="startsAt" error={fe.err("startsAt")} label={t("offer.startsAt")} hint={t("offer.startsHint")}><Input type="datetime-local" value={f.startsAt} onChange={(e) => set({ startsAt: e.target.value })} /></Field>
              <Field name="endsAt" error={fe.err("endsAt")} label={t("offer.endsAt")} hint={t("offer.endsHint")}><Input type="datetime-local" value={f.endsAt} onChange={(e) => set({ endsAt: e.target.value })} /></Field>
            </div>
            <div className="flex flex-wrap gap-2">
              {[{ k: "24h", h: 24 }, { k: "3d", h: 72 }, { k: "7d", h: 168 }, { k: "30d", h: 720 }].map((p) => (
                <button key={p.k} type="button" className="chip" onClick={() => { const s = f.startsAt ? new Date(f.startsAt) : new Date(); set({ startsAt: toLocal(s.toISOString()), endsAt: toLocal(new Date(s.getTime() + p.h * 3600000).toISOString()) }); }}>{t(`offer.len.${p.k}`)}</button>
              ))}
            </div>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field name="maxUses" error={fe.err("maxUses")} label={type === "COUPON" ? t("offer.maxRedemptions") : t("offer.maxUses")} hint={t("offer.optional")}><Input dir="ltr" inputMode="numeric" value={f.maxUses} onChange={(e) => set({ maxUses: e.target.value.replace(/[^0-9]/g, "") })} /></Field>
              {type === "COUPON" && <Field label={t("offer.perCustomer")} hint={t("offer.optional")}><Input dir="ltr" inputMode="numeric" value={f.perCustomer} onChange={(e) => set({ perCustomer: e.target.value.replace(/[^0-9]/g, "") })} /></Field>}
            </div>
          </>
        ), 4)}

        {type !== "COUPON" && section(t("offer.s.show"), (
          <>
            <Field name="badgeText" error={fe.err("badgeText")} label={t("offer.badge")} hint={t("offer.badgeHint")}><Input value={f.badgeText} maxLength={40} onChange={(e) => set({ badgeText: e.target.value })} placeholder={t(`offer.badgePh.${look.scope}`)} /></Field>
            <label className="flex items-center justify-between gap-3 rounded-xl bg-slate-50 p-3 text-sm"><span><b className="block text-slate-800">{t("offer.banner")}</b><span className="text-xs text-slate-500">{t("offer.bannerHint")}</span></span><input type="checkbox" className="h-5 w-5 accent-[var(--brand)]" checked={f.showBanner} onChange={(e) => set({ showBanner: e.target.checked })} /></label>
            {look.scope === "cart" && <label className="flex items-center justify-between gap-3 rounded-xl bg-slate-50 p-3 text-sm"><span><b className="block text-slate-800">{t("offer.stackable")}</b><span className="text-xs text-slate-500">{t("offer.stackableHint")}</span></span><input type="checkbox" className="h-5 w-5 accent-[var(--brand)]" checked={f.stackable} onChange={(e) => set({ stackable: e.target.checked })} /></label>}
          </>
        ), 5)}

        {fe.any ? <div className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm font-medium text-red-700">{t("form.fixMarked")}</div> : <ErrorText error={save.error} />}
        <div className="sticky bottom-3 z-10 flex flex-wrap gap-2 rounded-2xl bg-white/90 p-2 shadow-lift backdrop-blur">
          <Button type="submit" loading={save.loading}>{initial ? t("common.save") : type === "COUPON" ? t("offer.createCoupon") : t("offer.launch")}</Button>
          {type !== "COUPON" && !initial && <Button type="button" variant="secondary" disabled={save.loading} onClick={() => save.run(true)}>{t("offer.saveDraft")}</Button>}
        </div>
      </div>
      <aside className="lg:sticky lg:top-20 lg:self-start">
        <div className="rounded-2xl bg-gradient-to-b from-slate-50 to-white p-4 shadow-card ring-1 ring-slate-100">
          <Preview type={type} name={f.name} badge={f.badgeText} config={type === "COUPON" ? { ...config(), code: f.code } : config()} endsAt={f.endsAt ? new Date(f.endsAt).toISOString() : ""} fmt={fmt} />
        </div>
      </aside>
    </form>
  );
}
