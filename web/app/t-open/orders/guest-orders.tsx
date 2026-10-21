"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import { dateTime } from "@/lib/format";
import { useKnownOrders } from "@/lib/open-client";

/** Not signed in: the orders placed on this device (guests keep working without an account) and a way to sign in. */
export function GuestOrders({ signedOut }: { signedOut: boolean }) {
  const { t, locale } = useI18n();
  const orders = useKnownOrders();
  const [mounted, setMounted] = useState(false);
  useEffect(() => { const id = setTimeout(() => setMounted(true), 0); return () => clearTimeout(id); }, []);
  return (
    <div className="mt-5 grid gap-4">
      {signedOut && <div className="o-card flex flex-wrap items-center justify-between gap-3 p-5"><div><p className="font-extrabold">{t("open.orders.signInTitle")}</p><p className="o-mute text-sm">{t("open.orders.signInHint")}</p></div><Link href="/login?next=/orders" className="o-btn">{t("open.signIn")}</Link></div>}
      {mounted && orders.length > 0 && (
        <section>
          <h2 className="mb-2 text-lg font-extrabold">{t("open.orders.onThisDevice")}</h2>
          <ul className="grid gap-2">{orders.map((o) => <li key={o.shop + o.number}><Link href={`/orders/${encodeURIComponent(o.shop)}/${encodeURIComponent(o.number)}`} className="o-card flex items-center justify-between gap-3 p-4 transition hover:shadow-[var(--o-shadow-lg)]"><span className="min-w-0"><b className="block truncate">{o.shopName}</b><span className="o-mute text-xs" dir="ltr">{o.number} · {dateTime(new Date(o.at).toISOString(), locale)}</span></span><span className="o-link shrink-0">{t("open.done.track")}</span></Link></li>)}</ul>
        </section>
      )}
      {mounted && orders.length === 0 && !signedOut && <p className="o-mute">{t("open.orders.none")}</p>}
    </div>
  );
}
