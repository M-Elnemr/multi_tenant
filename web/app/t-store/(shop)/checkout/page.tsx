"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { api, newKey } from "@/lib/client";
import { clearCart, useCart } from "@/lib/cart";
import { money } from "@/lib/format";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Alert, Button, Card, Empty, ErrorText, Field, Input, Loading, PageHeader, Select } from "@/components/ui";

type Profile = { paymentMethods: { method: string }[]; shippingMethods: { id: string; type: string; name: string; feeMinor: number; freeAboveMinor?: number }[] };
type Quote = { subtotalMinor: number; discountMinor: number; shippingMinor: number; totalMinor: number; currency: string };
type Saved = { id: string; recipientName: string; phone: string; addressLine1: string; city: string; district?: string; isDefaultShipping: boolean };
type Placed = { orderId: string; checkoutUrl?: string; totalMinor: number };

export default function Checkout() {
  const { t, locale, currency } = useI18n();
  const router = useRouter();
  const lines = useCart();
  const { me, ready } = useMe();
  const profile = useApi<Profile>("shop/profile");
  const saved = useApi<Saved[]>(me ? "shop/addresses" : null);
  // Everything defaults from loaded data (profile, saved address, account) and only stores what the shopper typed.
  type Addr = { recipientName: string; phone: string; addressLine1: string; city: string; district: string };
  const [edits, setEdits] = useState<Partial<Addr>>({});
  const [chosenShip, setShippingId] = useState("");
  const [chosenPay, setPayment] = useState("");
  const [coupon, setCoupon] = useState("");
  const [notes, setNotes] = useState("");
  const [quote, setQuote] = useState<Quote | null>(null);
  const [key] = useState(newKey);

  const savedDefault = saved.data?.find((s) => s.isDefaultShipping) ?? saved.data?.[0];
  const base: Addr = savedDefault
    ? { recipientName: savedDefault.recipientName, phone: savedDefault.phone, addressLine1: savedDefault.addressLine1, city: savedDefault.city, district: savedDefault.district ?? "" }
    : { recipientName: me ? `${me.firstName} ${me.lastName}`.trim() : "", phone: me?.phone ?? "", addressLine1: "", city: "", district: "" };
  const addr: Addr = { ...base, ...edits };
  const setAddr = (a: Addr) => setEdits(a);
  const shippingId = chosenShip || profile.data?.shippingMethods[0]?.id || "";
  const payment = chosenPay || profile.data?.paymentMethods[0]?.method || "";

  const items = useMemo(() => lines.map((l) => ({ variantId: l.variantId, quantity: l.quantity })), [lines]);
  const shipping = profile.data?.shippingMethods.find((s) => s.id === shippingId);
  const needsAddress = shipping ? shipping.type !== "PICKUP" : true;
  const body = { items, shippingMethodId: shippingId, paymentMethod: payment, couponCode: coupon.trim() || undefined, notes: notes || undefined, address: needsAddress ? addr : undefined };

  const quoteAction = useAction(async () => { setQuote(await api<Quote>("shop/cart/quote", { body })); });
  useEffect(() => {
    if (!me || !shippingId || items.length === 0) return;
    const h = setTimeout(() => { void quoteAction.run(); }, 300);
    return () => clearTimeout(h);
  }, [shippingId, coupon, addr.addressLine1, addr.city, addr.phone, items.length, me]); // eslint-disable-line react-hooks/exhaustive-deps

  const place = useAction(async () => {
    const r = await api<Placed>("shop/checkout", { body, idempotencyKey: key });
    clearCart();
    if (r.checkoutUrl) {
      if (r.checkoutUrl.startsWith("http")) window.location.href = r.checkoutUrl;
      else router.replace(`/checkout/pay?kind=order&id=${r.orderId}&amount=${r.totalMinor}`);
    } else router.replace(`/orders/${r.orderId}`);
  });

  if (!ready || profile.loading) return <Loading />;
  if (!me) { router.replace("/login?next=/checkout"); return <Loading />; }
  if (lines.length === 0) return <Empty>{t("cart.empty")} <Link href="/products" className="text-brand underline">{t("shop.browse")}</Link></Empty>;

  return (
    <>
      <PageHeader title={t("checkout.title")} />
      <div className="grid gap-6 lg:grid-cols-3">
        <form id="co" onSubmit={(e) => { e.preventDefault(); void place.run(); }} className="space-y-5 lg:col-span-2">
          <Card className="space-y-4">
            <h2 className="font-medium">{t("checkout.shipping")}</h2>
            <Select value={shippingId} onChange={(e) => setShippingId(e.target.value)}>
              {profile.data?.shippingMethods.map((s) => <option key={s.id} value={s.id}>{s.name} {s.type !== "PICKUP" && `- ${money(s.feeMinor, currency, locale)}`}</option>)}
            </Select>
            {needsAddress && (
              <div className="grid gap-3 sm:grid-cols-2">
                <Field label={t("checkout.recipient")}><Input value={addr.recipientName} onChange={(e) => setAddr({ ...addr, recipientName: e.target.value })} required /></Field>
                <Field label={t("register.phone")}><Input value={addr.phone} onChange={(e) => setAddr({ ...addr, phone: e.target.value })} dir="ltr" required /></Field>
                <div className="sm:col-span-2"><Field label={t("checkout.address")}><Input value={addr.addressLine1} onChange={(e) => setAddr({ ...addr, addressLine1: e.target.value })} required /></Field></div>
                <Field label={t("checkout.city")}><Input value={addr.city} onChange={(e) => setAddr({ ...addr, city: e.target.value })} required /></Field>
                <Field label={t("checkout.district")}><Input value={addr.district} onChange={(e) => setAddr({ ...addr, district: e.target.value })} /></Field>
              </div>
            )}
            <Field label={t("checkout.notes")}><Input value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
          </Card>
          <Card className="space-y-3">
            <h2 className="font-medium">{t("checkout.payment")}</h2>
            {profile.data?.paymentMethods.length === 0 && <Alert tone="amber">{t("checkout.noPayment")}</Alert>}
            {profile.data?.paymentMethods.map((m) => (
              <label key={m.method} className={`flex cursor-pointer items-center gap-3 rounded-lg border p-3 ${payment === m.method ? "border-brand bg-brand/5" : "border-slate-200"}`}>
                <input type="radio" name="pay" checked={payment === m.method} onChange={() => setPayment(m.method)} />
                <span>{t(`pay.${m.method}`)}</span>
              </label>
            ))}
          </Card>
        </form>
        <Card className="h-fit space-y-3">
          <Field label={t("checkout.coupon")}><Input value={coupon} onChange={(e) => setCoupon(e.target.value.toUpperCase())} dir="ltr" /></Field>
          {quote && (
            <dl className="space-y-1.5 text-sm">
              <div className="flex justify-between"><dt>{t("checkout.subtotal")}</dt><dd>{money(quote.subtotalMinor, quote.currency, locale)}</dd></div>
              {quote.discountMinor > 0 && <div className="flex justify-between text-emerald-700"><dt>{t("checkout.discount")}</dt><dd>−{money(quote.discountMinor, quote.currency, locale)}</dd></div>}
              <div className="flex justify-between"><dt>{t("checkout.shippingFee")}</dt><dd>{money(quote.shippingMinor, quote.currency, locale)}</dd></div>
              <div className="flex justify-between border-t pt-2 text-base font-bold"><dt>{t("checkout.total")}</dt><dd>{money(quote.totalMinor, quote.currency, locale)}</dd></div>
            </dl>
          )}
          <ErrorText error={quoteAction.error ?? place.error} />
          <Button type="submit" form="co" loading={place.loading} disabled={!payment || !shippingId} className="w-full">{t("checkout.place")}</Button>
        </Card>
      </div>
    </>
  );
}
