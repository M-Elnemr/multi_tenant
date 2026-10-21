"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useRef, useState } from "react";
import { Icon } from "@/components/icons";
import { useI18n } from "@/components/i18n-provider";
import { money } from "@/lib/format";
import { GOVERNORATES } from "@/lib/governorates";
import { clearCart, errMsg, loadCoupon, oapi, rememberOrder, useOpenCart, useOpenMe, uuid, type CartQuote } from "@/lib/open-client";

type Form = { name: string; phone: string; phone2: string; governorate: string; city: string; area: string; address: string; landmark: string; notes: string };
const EMPTY: Form = { name: "", phone: "", phone2: "", governorate: "", city: "", area: "", address: "", landmark: "", notes: "" };
const DRAFT = "ocheck:v1";
const PHONE_RE = /^(\+?20|0)?1[0125]\d{8}$/;

export function CheckoutView() {
  const { t, locale } = useI18n();
  const router = useRouter();
  const lines = useOpenCart();
  const me = useOpenMe();
  const [mounted, setMounted] = useState(false);
  const [f, setF] = useState<Form>(EMPTY);
  const [coupon, setCoupon] = useState("");
  const [quote, setQuote] = useState<CartQuote | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>("");
  const [touched, setTouched] = useState(false);
  const done = useRef(false);
  const key = useRef<string>("");

  useEffect(() => {
    const id = setTimeout(() => {
      try { const d = JSON.parse(localStorage.getItem(DRAFT) ?? "null") as Partial<Form> | null; if (d) setF((x) => ({ ...x, ...d })); } catch { /* ignore a broken draft */ }
      setCoupon(loadCoupon());
      key.current = uuid();
      setMounted(true);
    }, 0);
    return () => clearTimeout(id);
  }, []);
  useEffect(() => {
    if (!me) return;
    const id = setTimeout(() => setF((x) => ({ ...x, name: x.name || me.name, phone: x.phone || (me.phone ?? "").replace(/^\+20/, "0") })), 0);
    return () => clearTimeout(id);
  }, [me]);
  useEffect(() => { if (mounted && !done.current) try { localStorage.setItem(DRAFT, JSON.stringify(f)); } catch { /* private mode */ } }, [f, mounted]);

  const set = (k: keyof Form) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => setF((x) => ({ ...x, [k]: e.target.value }));
  const address = useMemo(() => ({ recipientName: f.name.trim(), phone: f.phone.trim(), phone2: f.phone2.trim() || undefined, addressLine1: f.address.trim(), governorateCode: f.governorate, city: f.city.trim() || f.governorate, area: f.area.trim(), landmark: f.landmark.trim() }), [f.name, f.phone, f.phone2, f.address, f.governorate, f.city, f.area, f.landmark]);

  const sig = useMemo(() => JSON.stringify([lines.map((l) => [l.variantId, l.quantity]), coupon, f.governorate]), [lines, coupon, f.governorate]);
  useEffect(() => {
    if (!mounted || lines.length === 0) return;
    let alive = true;
    const id = setTimeout(() => {
      oapi<CartQuote>("cart/quote", { body: { items: lines.map((l) => ({ variantId: l.variantId, quantity: l.quantity })), couponCode: coupon || undefined, address: f.governorate ? address : undefined } })
        .then((q) => alive && setQuote(q)).catch(() => alive && setQuote(null));
    }, 120);
    return () => { alive = false; clearTimeout(id); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sig, mounted]);

  const errs = {
    name: f.name.trim().length < 2,
    phone: !PHONE_RE.test(f.phone.replace(/[\s-]/g, "")),
    phone2: f.phone2.trim() !== "" && !PHONE_RE.test(f.phone2.replace(/[\s-]/g, "")),
    governorate: !f.governorate,
    address: f.address.trim().length < 5,
  };
  const valid = !Object.values(errs).some(Boolean);
  const currency = quote?.currency ?? lines[0]?.currency ?? "EGP";

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setTouched(true);
    if (!valid || busy || lines.length === 0) return;
    setBusy(true); setError("");
    try {
      const res = await oapi<{ orderNumber: string; shop: { slug: string; name: string } }>("checkout", {
        body: { items: lines.map((l) => ({ variantId: l.variantId, quantity: l.quantity })), couponCode: coupon || undefined, address, notes: f.notes.trim() || undefined }, idempotencyKey: key.current,
      });
      done.current = true;
      rememberOrder({ shop: res.shop.slug, shopName: res.shop.name, number: res.orderNumber, phone: address.phone, at: Date.now() });
      clearCart();
      try { localStorage.removeItem(DRAFT); } catch { /* ignore */ }
      router.replace(`/checkout/done?shop=${encodeURIComponent(res.shop.slug)}&n=${encodeURIComponent(res.orderNumber)}`);
    } catch (err) {
      setError(errMsg(t, err));
      key.current = uuid();   // a failed attempt must not be replayed as the same request
    } finally { setBusy(false); }
  };

  if (!mounted) return <div className="o-container pt-8"><div className="o-skel h-8 w-48" /><div className="o-skel mt-5 h-96" /></div>;
  if (lines.length === 0) {
    return <div className="o-container pt-16 text-center"><p className="text-6xl">🛒</p><h1 className="mt-4 text-2xl font-black">{t("open.cart.empty")}</h1><Link href="/" className="o-btn mt-6">{t("open.cart.startShopping")}</Link></div>;
  }
  const field = (k: keyof Form, label: string, props: React.InputHTMLAttributes<HTMLInputElement> = {}, bad = false) => (
    <div>
      <label className="o-label" htmlFor={`f-${k}`}>{label}</label>
      <input id={`f-${k}`} value={f[k]} onChange={set(k)} aria-invalid={touched && bad} className={`o-input ${touched && bad ? "!border-[var(--o-red)]" : ""}`} {...props} />
    </div>
  );

  return (
    <form onSubmit={submit} noValidate className="o-container pt-5 md:pt-8">
      <h1 className="text-2xl font-black md:text-3xl">{t("open.checkout.title")}</h1>
      <div className="mt-5 grid items-start gap-6 lg:grid-cols-[1fr_24rem]">
        <div className="grid gap-5">
          {me === null && <p className="o-card flex flex-wrap items-center justify-between gap-2 p-4 text-sm"><span>{t("open.checkout.guestHint")}</span><Link href="/login?next=/checkout" className="o-link">{t("open.signIn")}</Link></p>}

          <section className="o-card p-5">
            <h2 className="mb-4 flex items-center gap-2 text-lg font-extrabold"><span className="grid h-7 w-7 place-items-center rounded-full bg-[var(--o-navy)] text-xs text-white">1</span>{t("open.checkout.contact")}</h2>
            <div className="grid gap-4 sm:grid-cols-2">
              {field("name", t("open.checkout.name"), { autoComplete: "name" }, errs.name)}
              <div>{field("phone", t("open.checkout.phone"), { type: "tel", inputMode: "tel", autoComplete: "tel", placeholder: "01xxxxxxxxx", dir: "ltr" }, errs.phone)}{me && !me.phone && <p className="o-mute mt-1 text-xs">{t("open.checkout.phoneSaved")}</p>}</div>
              {field("phone2", t("open.checkout.phone2"), { type: "tel", inputMode: "tel", placeholder: "01xxxxxxxxx", dir: "ltr" }, errs.phone2)}
            </div>
          </section>

          <section className="o-card p-5">
            <h2 className="mb-4 flex items-center gap-2 text-lg font-extrabold"><span className="grid h-7 w-7 place-items-center rounded-full bg-[var(--o-navy)] text-xs text-white">2</span>{t("open.checkout.delivery")}</h2>
            <div className="grid gap-4 sm:grid-cols-2">
              <div>
                <label className="o-label" htmlFor="f-gov">{t("open.checkout.governorate")}</label>
                <select id="f-gov" value={f.governorate} onChange={set("governorate")} aria-invalid={touched && errs.governorate} className={`o-input ${touched && errs.governorate ? "!border-[var(--o-red)]" : ""}`}>
                  <option value="">{t("open.checkout.choose")}</option>
                  {GOVERNORATES.map((g) => <option key={g.code} value={g.code}>{locale === "ar" ? g.ar : g.en}</option>)}
                </select>
              </div>
              {field("city", t("open.checkout.city"), { autoComplete: "address-level2" })}
              {field("area", t("open.checkout.area"))}
              {field("landmark", t("open.checkout.landmark"))}
              <div className="sm:col-span-2">{field("address", t("open.checkout.address"), { autoComplete: "street-address", placeholder: t("open.checkout.addressHint") }, errs.address)}</div>
              <div className="sm:col-span-2"><label className="o-label" htmlFor="f-notes">{t("open.checkout.notes")}</label><textarea id="f-notes" value={f.notes} onChange={set("notes")} rows={2} className="o-input" /></div>
            </div>
          </section>

          <section className="o-card p-5">
            <h2 className="mb-3 flex items-center gap-2 text-lg font-extrabold"><span className="grid h-7 w-7 place-items-center rounded-full bg-[var(--o-navy)] text-xs text-white">3</span>{t("open.checkout.payment")}</h2>
            <label className="flex items-center gap-3 rounded-2xl border-2 border-[var(--o-accent)] bg-[#fff6f2] p-4">
              <input type="radio" checked readOnly className="h-5 w-5 accent-[var(--o-accent)]" />
              <span className="text-2xl">💵</span>
              <span><b>{t("open.trust.cod")}</b><span className="block text-sm text-[var(--o-mute)]">{t("open.trust.codSub")}</span></span>
            </label>
          </section>
        </div>

        <aside className="o-card sticky top-36 p-5">
          <h2 className="text-lg font-extrabold">{t("open.cart.summary")}</h2>
          {quote && (
            <div className="mt-4 rounded-2xl bg-[var(--o-soft)] p-3.5 text-sm">
              <p className="flex items-center gap-2 font-extrabold"><Icon name="bag" className="h-4 w-4" />{quote.shop.name}</p>
              <dl className="mt-2 grid gap-1">
                {quote.lines.map((l) => <div key={l.variantId} className="flex justify-between gap-2"><dt className="line-clamp-1 text-[var(--o-mute)]">{l.productName} × {l.quantity}</dt><dd className="shrink-0 font-semibold">{money(l.unitPriceMinor * l.quantity, currency, locale)}</dd></div>)}
                {quote.discountMinor > 0 && <div className="flex justify-between text-[#067647]"><dt>{t("open.cart.discount")}</dt><dd className="font-bold">−{money(quote.discountMinor, currency, locale)}</dd></div>}
                <div className="flex justify-between"><dt className="text-[var(--o-mute)]">{t("open.cart.shipping")}{quote.etaMinDays ? ` (${t("open.eta", { min: quote.etaMinDays, max: quote.etaMaxDays ?? quote.etaMinDays })})` : ""}</dt><dd className="font-semibold">{quote.shippingKnown ? (quote.shippingMinor === 0 ? t("open.freeShipping") : money(quote.shippingMinor, currency, locale)) : "—"}</dd></div>
                {quote.codFeeMinor > 0 && <div className="flex justify-between"><dt className="text-[var(--o-mute)]">{t("open.checkout.codFee")}</dt><dd className="font-semibold">{money(quote.codFeeMinor, currency, locale)}</dd></div>}
              </dl>
            </div>
          )}
          <div className="mt-4 flex items-baseline justify-between border-t border-[var(--o-line)] pt-4"><span className="font-extrabold">{t("open.checkout.total")}</span><span className="text-2xl font-black">{quote ? money(quote.totalMinor, currency, locale) : "—"}</span></div>
          {!quote?.shippingKnown && <p className="o-mute mt-1 text-xs">{t("open.checkout.pickGovernorate")}</p>}
          {error && <div role="alert" className="mt-3 rounded-xl bg-[#fef3f2] p-3 text-sm font-bold text-[#b42318]">{error}</div>}
          <button type="submit" disabled={busy} className="o-btn o-btn-block mt-4 !min-h-12">{busy ? t("common.loading") : t("open.checkout.place")}</button>
          {touched && !valid && <p className="mt-2 text-xs font-bold text-[var(--o-red)]">{t("open.checkout.fixFields")}</p>}
          <p className="o-mute mt-3 text-center text-xs">{t("open.checkout.shopNote", { shop: lines[0].shopName })}</p>
        </aside>
      </div>
    </form>
  );
}
