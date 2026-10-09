"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { api, newKey } from "@/lib/client";
import { clearCart, useCart } from "@/lib/cart";
import { money } from "@/lib/format";
import { GOVERNORATES } from "@/lib/governorates";
import { useAction, useApi, useMe } from "@/components/hooks";
import { useI18n } from "@/components/i18n-provider";
import { Loading, ErrorText } from "@/components/ui";
import { GoogleButton } from "@/components/google-button";
import { PhoneInput } from "@/components/inputs";

type Method = { id: string; type: string; name: string; feeMinor: number; freeAboveMinor?: number };
type Zone = { id: string; name: string; governorateCodes: string[]; feeMinor: number; freeAboveMinor?: number; codFeeMinor: number; etaMinDays: number; etaMaxDays: number };
type Profile = { profile: { isOpen?: boolean; closedMessage?: string; minOrderMinor?: number }; paymentMethods: { method: string }[]; shippingMethods: Method[]; shippingZones: Zone[] };
type Quote = { subtotalMinor: number; discountMinor: number; shippingMinor: number; codFeeMinor: number; totalMinor: number; currency: string; etaMinDays?: number | null; etaMaxDays?: number | null };
type Saved = { id: string; recipientName: string; phone: string; addressLine1: string; city: string; state?: string; district?: string; isDefaultShipping: boolean };
type Placed = { orderId: string; orderNumber: string; totalMinor: number };
type Addr = { recipientName: string; phone: string; phone2: string; governorateCode: string; city: string; area: string; addressLine1: string; landmark: string };

function Section({ n, title, children }: { n: number; title: string; children: React.ReactNode }) {
  return (
    <section className="s-card space-y-4 p-5 sm:p-7">
      <h2 className="flex items-center gap-3 text-lg font-extrabold"><span className="grid h-8 w-8 place-items-center rounded-full text-sm text-white" style={{ background: "var(--brand)" }}>{n}</span>{title}</h2>
      {children}
    </section>
  );
}

const Field = ({ label, children, hint }: { label: string; children: React.ReactNode; hint?: string }) => (
  <label className="block space-y-1.5"><span className="text-sm font-bold">{label}</span>{children}{hint && <span className="block text-xs text-[var(--s-mute)]">{hint}</span>}</label>
);

export default function Checkout() {
  const { t, locale, currency } = useI18n();
  const router = useRouter();
  const lines = useCart();
  const { me, ready } = useMe();
  const profile = useApi<Profile>("shop/profile");
  const saved = useApi<Saved[]>(me ? "shop/addresses" : null);
  const [edits, setEdits] = useState<Partial<Addr>>({});
  const [chosenShip, setShippingId] = useState("");
  const [coupon, setCoupon] = useState("");
  const [notes, setNotes] = useState("");
  const [quote, setQuote] = useState<Quote | null>(null);
  const [key] = useState(newKey);
  const [saveDetails, setSaveDetails] = useState(true);

  const savedDefault = saved.data?.find((s) => s.isDefaultShipping) ?? saved.data?.[0];
  const base: Addr = savedDefault
    ? { recipientName: savedDefault.recipientName, phone: savedDefault.phone, phone2: "", governorateCode: savedDefault.state ?? "", city: savedDefault.city, area: savedDefault.district ?? "", addressLine1: savedDefault.addressLine1, landmark: "" }
    : { recipientName: me ? `${me.firstName} ${me.lastName}`.trim() : "", phone: me?.phone ?? "", phone2: "", governorateCode: "", city: "", area: "", addressLine1: "", landmark: "" };
  const addr: Addr = { ...base, ...edits };
  const set = (patch: Partial<Addr>) => setEdits({ ...edits, ...patch });
  const methods = profile.data?.shippingMethods ?? [];
  const shippingId = chosenShip || methods[0]?.id || "";
  const shipping = methods.find((s) => s.id === shippingId);
  const zonesMode = shipping?.type === "ZONES";
  const needsAddress = shipping ? shipping.type !== "PICKUP" : true;
  const open = profile.data?.profile.isOpen !== false;
  const codOk = (profile.data?.paymentMethods ?? []).some((m) => m.method === "CASH_ON_DELIVERY");
  const zone = zonesMode ? profile.data?.shippingZones.find((z) => z.governorateCodes.includes(addr.governorateCode)) : undefined;

  const items = useMemo(() => lines.map((l) => ({ variantId: l.variantId, quantity: l.quantity })), [lines]);
  const body = { items, shippingMethodId: shippingId, paymentMethod: "CASH_ON_DELIVERY", couponCode: coupon.trim() || undefined, notes: notes || undefined,
    address: needsAddress ? addr : { recipientName: addr.recipientName, phone: addr.phone, phone2: addr.phone2 } };

  const quoteAction = useAction(async () => { setQuote(await api<Quote>("shop/cart/quote", { body })); });
  useEffect(() => {
    if (!shippingId || items.length === 0) return;
    const h = setTimeout(() => { void quoteAction.run(); }, 300);
    return () => clearTimeout(h);
  }, [shippingId, coupon, addr.addressLine1, addr.city, addr.governorateCode, addr.phone, items.length]); // eslint-disable-line react-hooks/exhaustive-deps

  const place = useAction(async () => {
    const r = await api<Placed>("shop/checkout", { body, idempotencyKey: key });
    clearCart();
    if (me && saveDetails) {
      await api("shop/me", { method: "PUT", body: { name: addr.recipientName, phone: addr.phone } }).catch(() => null);
      if (needsAddress && !(saved.data ?? []).some((a) => a.addressLine1 === addr.addressLine1 && a.city === addr.city))
        await api("shop/addresses", { body: { makeDefault: (saved.data?.length ?? 0) === 0, address: { recipientName: addr.recipientName, phone: addr.phone, addressLine1: addr.addressLine1, city: addr.city || addr.area, state: addr.governorateCode, district: addr.area } } }).catch(() => null);
    }
    router.replace(`/checkout/done?n=${encodeURIComponent(r.orderNumber)}&p=${encodeURIComponent(addr.phone)}`);
  });

  if (!ready || profile.loading) return <Loading />;
  if (lines.length === 0) return <div className="s-card mx-auto max-w-md p-12 text-center"><p className="text-5xl">🛒</p><p className="mt-3 font-extrabold">{t("cart.empty")}</p><Link href="/products" className="s-btn mt-5">{t("shop.browse")}</Link></div>;

  const total = lines.reduce((n, l) => n + l.unitPriceMinor * l.quantity, 0);
  return (
    <>
      <h1 className="mb-6 text-3xl font-extrabold">{t("checkout.title")}</h1>
      {!open && <div className="mb-6 rounded-2xl bg-[var(--s-ink)] p-4 text-center font-bold text-white">🌙 {profile.data?.profile.closedMessage || t("shop.closedNow")}</div>}
      <div className="grid items-start gap-6 lg:grid-cols-[1fr_24rem]">
        <form id="co" onSubmit={(e) => { e.preventDefault(); void place.run(); }} className="space-y-5">
          <Section n={1} title={t("checkout.contact")}>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label={t("checkout.recipient")}><input className="s-input" value={addr.recipientName} onChange={(e) => set({ recipientName: e.target.value })} required autoComplete="name" /></Field>
              <Field label={t("register.phone")}><PhoneInput value={addr.phone} onValue={(phone) => set({ phone })} required /></Field>
              <Field label={t("checkout.phone2")} hint={t("checkout.phone2Hint")}><PhoneInput value={addr.phone2} onValue={(phone2) => set({ phone2 })} /></Field>
            </div>
            {!me && (
              <div className="rounded-2xl bg-[var(--s-soft)] p-4"><p className="text-sm font-bold">{t("checkout.guestTitle")}</p><p className="mb-3 text-xs text-[var(--s-mute)]">{t("checkout.guestHint")}</p><GoogleButton onSignedIn={() => { router.refresh(); }} /></div>
            )}
          </Section>
          <Section n={2} title={t("checkout.shipping")}>
            {methods.length > 1 && (
              <div className="grid gap-2 sm:grid-cols-2">
                {methods.map((m) => (
                  <label key={m.id} className={`flex cursor-pointer items-center gap-3 rounded-2xl border p-4 transition ${m.id === shippingId ? "border-[var(--brand)] bg-[var(--s-soft)]" : "border-[var(--s-line)] hover:border-[var(--brand)]/50"}`}>
                    <input type="radio" name="ship" checked={m.id === shippingId} onChange={() => setShippingId(m.id)} className="accent-[var(--brand)]" />
                    <span className="flex-1 font-bold">{m.name}</span>
                    <span className="text-sm font-bold text-[var(--s-mute)]">{m.type === "PICKUP" ? t("ship.PICKUP") : m.type === "ZONES" ? t("checkout.byGovernorate") : money(m.feeMinor, currency, locale)}</span>
                  </label>
                ))}
              </div>
            )}
            {needsAddress && (
              <div className="grid gap-4 sm:grid-cols-2">
                <Field label={t("checkout.governorate")}>
                  <select className="s-input" value={addr.governorateCode} onChange={(e) => set({ governorateCode: e.target.value })} required={zonesMode}>
                    <option value="">{t("checkout.chooseGovernorate")}</option>
                    {GOVERNORATES.map((g) => <option key={g.code} value={g.code}>{locale === "ar" ? g.ar : g.en}</option>)}
                  </select>
                </Field>
                <Field label={t("checkout.city")}><input className="s-input" value={addr.city} onChange={(e) => set({ city: e.target.value })} required={!zonesMode} /></Field>
                <Field label={t("checkout.area")}><input className="s-input" value={addr.area} onChange={(e) => set({ area: e.target.value })} /></Field>
                <Field label={t("checkout.landmark")} hint={t("checkout.landmarkHint")}><input className="s-input" value={addr.landmark} onChange={(e) => set({ landmark: e.target.value })} /></Field>
                <div className="sm:col-span-2"><Field label={t("checkout.address")}><input className="s-input" value={addr.addressLine1} onChange={(e) => set({ addressLine1: e.target.value })} required placeholder={t("checkout.addressHint")} /></Field></div>
              </div>
            )}
            {zonesMode && addr.governorateCode && !zone && <p className="rounded-2xl bg-red-50 p-3 text-sm font-bold text-[#e5484d]">{t("error.NO_DELIVERY_TO_GOVERNORATE")}</p>}
            {zone && quote?.etaMinDays != null && <p className="rounded-2xl bg-emerald-50 p-3 text-sm font-bold text-emerald-800">🚚 {t("checkout.eta", { min: quote.etaMinDays, max: quote.etaMaxDays ?? quote.etaMinDays })}</p>}
            <Field label={t("checkout.notes")}><input className="s-input" value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
          </Section>
          <Section n={3} title={t("checkout.payment")}>
            {codOk ? (
              <div className="flex items-center gap-3 rounded-2xl border-2 border-[var(--brand)] bg-[var(--s-soft)] p-4"><span className="text-3xl">💵</span><div><p className="font-extrabold">{t("pay.CASH_ON_DELIVERY")}</p><p className="text-sm text-[var(--s-mute)]">{t("checkout.cashOnly")}</p></div></div>
            ) : <p className="rounded-2xl bg-amber-50 p-3 text-sm font-bold text-amber-800">{t("checkout.noPayment")}</p>}
            {me && <label className="flex cursor-pointer items-center gap-2 text-sm"><input type="checkbox" checked={saveDetails} onChange={(e) => setSaveDetails(e.target.checked)} className="accent-[var(--brand)]" />{t("checkout.saveDetails")}</label>}
          </Section>
        </form>
        <aside className="s-card sticky top-28 space-y-4 p-6">
          <h2 className="text-lg font-extrabold">{t("checkout.summary")}</h2>
          <ul className="max-h-56 space-y-3 overflow-y-auto">
            {lines.map((l) => (
              <li key={l.variantId} className="flex items-center gap-3 text-sm">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                {l.imageUrl ? <img src={l.imageUrl} alt="" className="h-12 w-12 rounded-xl bg-[var(--s-soft)] object-cover" /> : <span className="h-12 w-12 rounded-xl bg-[var(--s-soft)]" />}
                <span className="min-w-0 flex-1"><span className="line-clamp-1 font-bold">{l.name}</span><span className="text-xs text-[var(--s-mute)]">{l.variantLabel} × {l.quantity}</span></span>
                <b>{money(l.unitPriceMinor * l.quantity, currency, locale)}</b>
              </li>
            ))}
          </ul>
          <label className="block space-y-1.5"><span className="text-sm font-bold">{t("checkout.coupon")}</span><input className="s-input" value={coupon} onChange={(e) => setCoupon(e.target.value.toUpperCase())} dir="ltr" /></label>
          <dl className="space-y-2 border-t border-[var(--s-line)] pt-4 text-sm">
            <div className="flex justify-between"><dt className="text-[var(--s-mute)]">{t("checkout.subtotal")}</dt><dd className="font-bold">{money(quote?.subtotalMinor ?? total, currency, locale)}</dd></div>
            {quote && quote.discountMinor > 0 && <div className="flex justify-between text-emerald-700"><dt>{t("checkout.discount")}</dt><dd className="font-bold">−{money(quote.discountMinor, currency, locale)}</dd></div>}
            <div className="flex justify-between"><dt className="text-[var(--s-mute)]">{t("checkout.shippingFee")}</dt><dd className="font-bold">{quote ? (quote.shippingMinor === 0 ? t("checkout.free") : money(quote.shippingMinor, currency, locale)) : "—"}</dd></div>
            {quote && quote.codFeeMinor > 0 && <div className="flex justify-between"><dt className="text-[var(--s-mute)]">{t("checkout.codFee")}</dt><dd className="font-bold">{money(quote.codFeeMinor, currency, locale)}</dd></div>}
            <div className="flex justify-between border-t border-[var(--s-line)] pt-3 text-lg font-extrabold"><dt>{t("checkout.total")}</dt><dd style={{ color: "var(--brand)" }}>{money(quote?.totalMinor ?? total, currency, locale)}</dd></div>
          </dl>
          <ErrorText error={quoteAction.error ?? place.error} />
          <button type="submit" form="co" disabled={place.loading || !shippingId || !codOk || !open} className="s-btn w-full !py-3.5">{place.loading ? "…" : `${t("checkout.place")} →`}</button>
          <p className="text-center text-xs text-[var(--s-mute)]">🔒 {t("product.secure")} · 💵 {t("trust.cod")}</p>
        </aside>
      </div>
    </>
  );
}
