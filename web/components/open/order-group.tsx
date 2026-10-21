import Link from "next/link";
import { money } from "@/lib/format";
import { dateTime } from "@/lib/format";
import { getT } from "@/lib/i18n-server";
import { localPhone } from "@/lib/phone";
import { Icon } from "../icons";
import { ReviewButton } from "./review-button";

export type GItem = { orderId: string; productId: string; productSlug?: string; name: string; variantName?: string | null; quantity: number; unitPriceMinor: number; lineTotalMinor: number; image?: string | null };
export type GOrder = {
  id: string; shopId: string; shopSlug: string; shopName: string; shopHost?: string; supportPhone?: string | null; orderNumber: string; status: string; paymentStatus: string; currency: string;
  subtotalMinor: number; discountMinor: number; shippingMinor: number; codFeeMinor: number; totalMinor: number; couponCode?: string | null;
  courierName?: string | null; trackingNumber?: string | null; trackingUrl?: string | null; etaMinDays?: number | null; etaMaxDays?: number | null; createdAt: string; deliveredAt?: string | null; items: GItem[];
};
export type Group = { groupId: string; name: string; phone: string; createdAt: string; totalMinor: number; orders: GOrder[] };

const STEPS = ["REQUESTED", "PREPARING", "SHIPPED", "ARRIVED"] as const;

/** One purchase: a card per shop with its own order number, status timeline, items and the shop's contact. */
export async function OrderGroupView({ group, compact = false, canReview = false }: { group: Group; compact?: boolean; canReview?: boolean }) {
  const { t, locale } = await getT();
  return (
    <div className="grid gap-4">
      {group.orders.map((o) => {
        const idx = STEPS.indexOf(o.status as (typeof STEPS)[number]);
        const dead = o.status === "CANCELLED" || o.status === "RETURNED";
        return (
          <section key={o.id} className="overflow-hidden rounded-3xl border border-[var(--o-line)] bg-white">
            <header className="flex flex-wrap items-center justify-between gap-2 border-b border-[var(--o-line)] bg-[var(--o-soft)] px-4 py-3">
              <div className="min-w-0">
                <Link href={`/shop/${o.shopSlug}`} className="flex items-center gap-2 font-extrabold hover:text-[var(--o-accent)]"><Icon name="bag" className="h-4 w-4" />{o.shopName}</Link>
                <p className="o-mute text-xs" dir="ltr">{o.orderNumber} · {dateTime(o.createdAt, locale)}</p>
              </div>
              <span className={`rounded-full px-3 py-1 text-xs font-extrabold ${dead ? "bg-[#fef3f2] text-[#b42318]" : o.status === "ARRIVED" ? "bg-[#e8f8f0] text-[#067647]" : "bg-[#fff1ea] text-[#c4320a]"}`}>{t(`open.status.${o.status}`)}</span>
            </header>

            {!dead && (
              <ol className="grid grid-cols-4 gap-1 px-4 pt-4" aria-label={t("open.orderProgress")}>
                {STEPS.map((s, n) => (
                  <li key={s} className="text-center">
                    <div className="relative mb-1.5 h-1.5 overflow-hidden rounded-full bg-[var(--o-line)]"><div className={`absolute inset-0 rounded-full transition-all ${n <= idx ? "bg-[var(--o-green)]" : ""}`} /></div>
                    <p className={`text-[11px] font-bold ${n <= idx ? "text-[var(--o-ink)]" : "text-[var(--o-mute)]"}`}>{t(`open.status.${s}`)}</p>
                  </li>
                ))}
              </ol>
            )}

            <ul className="divide-y divide-[var(--o-line)]">
              {o.items.map((i, n) => (
                <li key={n} className="flex gap-3 p-4">
                  <span className="h-16 w-16 shrink-0 overflow-hidden rounded-xl bg-[var(--o-soft)]">
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    {i.image ? <img src={i.image} alt="" width={64} height={64} className="h-full w-full object-cover" loading="lazy" /> : <span className="grid h-full place-items-center text-2xl opacity-40">🛍️</span>}
                  </span>
                  <div className="min-w-0 flex-1">
                    {i.productSlug ? <Link href={`/p/${o.shopSlug}/${i.productSlug}`} className="line-clamp-2 text-sm font-bold hover:text-[var(--o-accent)]">{i.name}</Link> : <p className="line-clamp-2 text-sm font-bold">{i.name}</p>}
                    {i.variantName && <p className="o-mute text-xs">{i.variantName}</p>}
                    <p className="o-mute mt-0.5 text-xs">{money(i.unitPriceMinor, o.currency, locale)} × {i.quantity}</p>
                    {canReview && o.status === "ARRIVED" && <ReviewButton productId={i.productId} />}
                  </div>
                  <p className="shrink-0 text-sm font-extrabold">{money(i.lineTotalMinor, o.currency, locale)}</p>
                </li>
              ))}
            </ul>

            <div className="grid gap-2 border-t border-[var(--o-line)] bg-[var(--o-soft)] px-4 py-3.5 text-sm">
              {!compact && (
                <>
                  <p className="flex justify-between"><span className="o-mute">{t("open.cart.items", { n: o.items.reduce((s, i) => s + i.quantity, 0) })}</span><b>{money(o.subtotalMinor, o.currency, locale)}</b></p>
                  {o.discountMinor > 0 && <p className="flex justify-between text-[#067647]"><span>{t("open.cart.discount")}{o.couponCode ? ` (${o.couponCode})` : ""}</span><b>−{money(o.discountMinor, o.currency, locale)}</b></p>}
                  <p className="flex justify-between"><span className="o-mute">{t("open.cart.shipping")}</span><b>{o.shippingMinor === 0 ? t("open.freeShipping") : money(o.shippingMinor, o.currency, locale)}</b></p>
                  {o.codFeeMinor > 0 && <p className="flex justify-between"><span className="o-mute">{t("open.checkout.codFee")}</span><b>{money(o.codFeeMinor, o.currency, locale)}</b></p>}
                </>
              )}
              <p className="flex justify-between text-base"><span className="font-extrabold">{t("open.checkout.total")}</span><b className="text-lg font-black">{money(o.totalMinor, o.currency, locale)}</b></p>
              <p className="o-mute flex items-center gap-1.5 text-xs"><Icon name="wallet" className="h-4 w-4" />{o.paymentStatus === "PAID" ? t("open.paid") : t("open.payOnDelivery")}{o.etaMinDays && !dead && o.status !== "ARRIVED" ? ` · ${t("open.eta", { min: o.etaMinDays, max: o.etaMaxDays ?? o.etaMinDays })}` : ""}</p>
              {(o.trackingNumber || o.trackingUrl) && (
                <p className="flex flex-wrap items-center gap-2 text-xs"><Icon name="navigate" className="h-4 w-4" /><span>{o.courierName ?? t("open.courier")}: <b dir="ltr">{o.trackingNumber}</b></span>{o.trackingUrl && <a href={o.trackingUrl} target="_blank" rel="noopener noreferrer" className="o-link">{t("open.trackShipment")}</a>}</p>
              )}
              {o.supportPhone && <p className="flex flex-wrap gap-3 text-xs"><a href={`tel:${o.supportPhone}`} className="o-link flex items-center gap-1"><Icon name="phone" className="h-4 w-4" />{localPhone(o.supportPhone)}</a><a href={`https://wa.me/${o.supportPhone.replace(/\D/g, "")}`} target="_blank" rel="noopener noreferrer" className="o-link">WhatsApp</a></p>}
            </div>
          </section>
        );
      })}
      {group.orders.length > 1 && <p className="flex items-center justify-between rounded-2xl bg-[var(--o-navy)] px-5 py-4 text-white"><span className="font-bold">{t("open.groupTotal")}</span><b className="text-xl font-black">{money(group.totalMinor, group.orders[0].currency, locale)}</b></p>}
    </div>
  );
}
