"use client";

import { useEffect, useState } from "react";
import { ApiError } from "@/lib/client";
import { errMsg, oapi, rememberOrder, useKnownOrders } from "@/lib/open-client";
import type { OpenOrder } from "@/lib/open-types";
import { useI18n } from "../i18n-provider";
import { OrderView } from "./order-view";

/** Follows one order by shop + number + the phone it was placed with. The phone is remembered on this device (not put in the address); if it is not known, it is asked for. */
export function GuestOrder({ shop, number, compact = false }: { shop: string; number: string; compact?: boolean }) {
  const { t } = useI18n();
  const known = useKnownOrders();
  const [mounted, setMounted] = useState(false);
  const [order, setOrder] = useState<OpenOrder | null>(null);
  const [phone, setPhone] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  useEffect(() => { const id = setTimeout(() => setMounted(true), 0); return () => clearTimeout(id); }, []);

  const load = async (p: string) => {
    setBusy(true); setError("");
    try {
      const o = await oapi<OpenOrder>("orders/track", { body: { shop, orderNumber: number, phone: p } });
      setOrder(o);
      rememberOrder({ shop, shopName: o.shopName, number, phone: p, at: Date.now() });
    } catch (e) { setError(e instanceof ApiError && e.status === 404 ? t("open.order.wrongPhone") : errMsg(t, e)); } finally { setBusy(false); }
  };
  const saved = mounted ? known.find((k) => k.shop === shop && k.number === number)?.phone : undefined;
  useEffect(() => { if (saved && !order) { const id = setTimeout(() => void load(saved), 0); return () => clearTimeout(id); } /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [saved]);

  if (order) return <OrderView order={order} compact={compact} />;
  if (!mounted || (saved && !error)) return <div className="o-skel h-56" style={{ borderRadius: 24 }} />;
  return (
    <form onSubmit={(e) => { e.preventDefault(); if (phone.trim()) void load(phone.trim()); }} className="o-card grid gap-3 p-5">
      <p className="font-extrabold">{t("open.order.enterPhone")}</p>
      <input value={phone} onChange={(e) => setPhone(e.target.value)} type="tel" inputMode="tel" dir="ltr" placeholder="01xxxxxxxxx" aria-label={t("open.checkout.phone")} className="o-input" />
      {error && <p role="alert" className="text-sm font-bold text-[var(--o-red)]">{error}</p>}
      <button className="o-btn w-fit" disabled={busy}>{t("open.done.track")}</button>
    </form>
  );
}
