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
import { GoogleButton } from "@/components/google-button";
import { PhoneInput } from "@/components/inputs";

type Profile = { paymentMethods: { method: string }[]; shippingMethods: { id: string; type: string; name: string; feeMinor: number; freeAboveMinor?: number }[] };
type Quote = { subtotalMinor: number; discountMinor: number; shippingMinor: number; totalMinor: number; currency: string };
type Saved = { id: string; recipientName: string; phone: string; addressLine1: string; city: string; district?: string; isDefaultShipping: boolean };
type Placed = { orderId: string; orderNumber: string; checkoutUrl?: string; totalMinor: number };

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
  const [coupon, setCoupon] = useState("");
  const [notes, setNotes] = useState("");
  const [quote, setQuote] = useState<Quote | null>(null);
  const [key] = useState(newKey);
  const [saveDetails, setSaveDetails] = useState(true);

  const savedDefault = saved.data?.find((s) => s.isDefaultShipping) ?? saved.data?.[0];
  const base: Addr = savedDefault
    ? { recipientName: savedDefault.recipientName, phone: savedDefault.phone, addressLine1: savedDefault.addressLine1, city: savedDefault.city, district: savedDefault.district ?? "" }
    : { recipientName: me ? `${me.firstName} ${me.lastName}`.trim() : "", phone: me?.phone ?? "", addressLine1: "", city: "", district: "" };
  const addr: Addr = { ...base, ...edits };
  const setAddr = (a: Addr) => setEdits(a);
  const shippingId = chosenShip || profile.data?.shippingMethods[0]?.id || "";
  const payment = "CASH_ON_DELIVERY";   // online card payment is switched off for now: every order is paid in cash on delivery

  const items = useMemo(() => lines.map((l) => ({ variantId: l.variantId, quantity: l.quantity })), [lines]);
  const shipping = profile.data?.shippingMethods.find((s) => s.id === shippingId);
  const needsAddress = shipping ? shipping.type !== "PICKUP" : true;
  const body = { items, shippingMethodId: shippingId, paymentMethod: payment, couponCode: coupon.trim() || undefined, notes: notes || undefined, address: needsAddress ? addr : { recipientName: addr.recipientName, phone: addr.phone } };

  const quoteAction = useAction(async () => { setQuote(await api<Quote>("shop/cart/quote", { body })); });
  useEffect(() => {
    if (!shippingId || items.length === 0) return;
    const h = setTimeout(() => { void quoteAction.run(); }, 300);
    return () => clearTimeout(h);
  }, [shippingId, coupon, addr.addressLine1, addr.city, addr.phone, items.length]); // eslint-disable-line react-hooks/exhaustive-deps

  const place = useAction(async () => {
    const r = await api<Placed>("shop/checkout", { body, idempotencyKey: key });
    clearCart();
    if (me && saveDetails) {
      // best effort: the order is already placed, so a failure here must not look like a failed order
      await api("shop/me", { method: "PUT", body: { name: addr.recipientName, phone: addr.phone } }).catch(() => null);
      if (needsAddress && !(saved.data ?? []).some((a) => a.addressLine1 === addr.addressLine1 && a.city === addr.city))
        await api("shop/addresses", { body: { makeDefault: (saved.data?.length ?? 0) === 0, address: addr } }).catch(() => null);
    }
    router.replace(me ? `/orders/${r.orderId}` : `/checkout/done?n=${encodeURIComponent(r.orderNumber)}`);
  });

  if (!ready || profile.loading) return <Loading />;
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
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label={t("checkout.recipient")}><Input value={addr.recipientName} onChange={(e) => setAddr({ ...addr, recipientName: e.target.value })} required autoComplete="name" /></Field>
              <Field label={t("register.phone")}><PhoneInput value={addr.phone} onValue={(phone) => setAddr({ ...addr, phone })} required /></Field>
              {needsAddress && (
                <>
                  <div className="sm:col-span-2"><Field label={t("checkout.address")}><Input value={addr.addressLine1} onChange={(e) => setAddr({ ...addr, addressLine1: e.target.value })} required /></Field></div>
                  <Field label={t("checkout.city")}><Input value={addr.city} onChange={(e) => setAddr({ ...addr, city: e.target.value })} required /></Field>
                  <Field label={t("checkout.district")}><Input value={addr.district} onChange={(e) => setAddr({ ...addr, district: e.target.value })} /></Field>
                </>
              )}
            </div>
            <Field label={t("checkout.notes")}><Input value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
          </Card>
          <Card className="space-y-3">
            <h2 className="font-medium">{t("checkout.payment")}</h2>
            {(profile.data?.paymentMethods ?? []).some((m) => m.method === "CASH_ON_DELIVERY") ? (
              <div className="flex items-center gap-3 rounded-xl border border-brand bg-brand-soft p-3 text-sm font-medium"><span aria-hidden>💵</span>{t("pay.CASH_ON_DELIVERY")}</div>
            ) : <Alert tone="amber">{t("checkout.noPayment")}</Alert>}
            <p className="text-xs text-slate-500">{t("checkout.cashOnly")}</p>
          </Card>
          <Card className="space-y-3">
            {me ? (
              <label className="flex cursor-pointer items-center gap-2 text-sm"><input type="checkbox" checked={saveDetails} onChange={(e) => setSaveDetails(e.target.checked)} />{t("checkout.saveDetails")}</label>
            ) : (
              <>
                <p className="text-sm font-medium">{t("checkout.guestTitle")}</p>
                <p className="text-xs text-slate-500">{t("checkout.guestHint")}</p>
                <GoogleButton onSignedIn={() => { router.refresh(); }} />
              </>
            )}
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
          <Button type="submit" form="co" loading={place.loading} disabled={!shippingId || !(profile.data?.paymentMethods ?? []).some((m) => m.method === "CASH_ON_DELIVERY")} className="w-full">{t("checkout.place")}</Button>
        </Card>
      </div>
    </>
  );
}
