import Link from "next/link";
import type { Metadata } from "next";
import { GuestOrders } from "./guest-orders";
import { BackendError } from "@/lib/backend";
import { dateTime, money } from "@/lib/format";
import { getT } from "@/lib/i18n-server";
import { openJson, openToken, type OpenPage } from "@/lib/open";
import type { Group } from "@/components/open/order-group";

export const dynamic = "force-dynamic";
export async function generateMetadata(): Promise<Metadata> { const { t } = await getT(); return { title: t("open.myOrders") }; }

export default async function OrdersPage() {
  const { t, locale } = await getT();
  const signedIn = !!(await openToken());
  const list = signedIn ? await openJson<OpenPage<Group>>("/orders?pageSize=30").catch((e) => { if (e instanceof BackendError && e.status === 401) return null; throw e; }) : null;
  return (
    <div className="o-container max-w-3xl pt-6 md:pt-10">
      <h1 className="text-2xl font-black md:text-3xl">{t("open.myOrders")}</h1>
      {list ? (
        list.data.length === 0 ? (
          <div className="o-card mt-5 px-6 py-16 text-center"><p className="text-5xl">📦</p><h2 className="o-title mt-3">{t("open.orders.none")}</h2><Link href="/" className="o-btn mt-5">{t("open.cart.startShopping")}</Link></div>
        ) : (
          <ul className="mt-5 grid gap-3">
            {list.data.map((g) => (
              <li key={g.groupId}>
                <Link href={`/orders/${g.groupId}`} className="o-card block p-4 transition hover:shadow-[var(--o-shadow-lg)]">
                  <div className="flex items-center justify-between gap-3">
                    <p className="text-sm font-bold">{dateTime(g.createdAt, locale)}</p>
                    <b>{money(g.totalMinor, g.orders[0]?.currency ?? "EGP", locale)}</b>
                  </div>
                  <ul className="mt-2.5 grid gap-1.5">
                    {g.orders.map((o) => (
                      <li key={o.id} className="flex items-center justify-between gap-2 text-sm">
                        <span className="min-w-0 truncate"><b>{o.shopName}</b> <span className="o-mute" dir="ltr">{o.orderNumber}</span></span>
                        <span className="shrink-0 rounded-full bg-[var(--o-soft)] px-2.5 py-0.5 text-xs font-extrabold">{t(`open.status.${o.status}`)}</span>
                      </li>
                    ))}
                  </ul>
                </Link>
              </li>
            ))}
          </ul>
        )
      ) : (
        <GuestOrders signedOut={signedIn === false} />
      )}
    </div>
  );
}
