"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import { dateTime } from "@/lib/format";
import { useKnownGroups } from "@/lib/open-client";

/** Not signed in: the purchases made on this device (guests keep working without an account) and a way to sign in. */
export function GuestOrders({ signedOut }: { signedOut: boolean }) {
  const { t, locale } = useI18n();
  const groups = useKnownGroups();
  const [mounted, setMounted] = useState(false);
  useEffect(() => { const id = setTimeout(() => setMounted(true), 0); return () => clearTimeout(id); }, []);
  return (
    <div className="mt-5 grid gap-4">
      {signedOut && <div className="o-card flex flex-wrap items-center justify-between gap-3 p-5"><div><p className="font-extrabold">{t("open.orders.signInTitle")}</p><p className="o-mute text-sm">{t("open.orders.signInHint")}</p></div><Link href="/login?next=/orders" className="o-btn">{t("open.signIn")}</Link></div>}
      {mounted && groups.length > 0 && (
        <section>
          <h2 className="mb-2 text-lg font-extrabold">{t("open.orders.onThisDevice")}</h2>
          <ul className="grid gap-2">{groups.map((g) => <li key={g.groupId}><Link href={`/orders/${g.groupId}?phone=${encodeURIComponent(g.phone)}`} className="o-card flex items-center justify-between p-4 transition hover:shadow-[var(--o-shadow-lg)]"><span className="font-bold">{dateTime(new Date(g.at).toISOString(), locale)}</span><span className="o-link">{t("open.done.track")}</span></Link></li>)}</ul>
        </section>
      )}
      {mounted && groups.length === 0 && !signedOut && <p className="o-mute">{t("open.orders.none")}</p>}
    </div>
  );
}
