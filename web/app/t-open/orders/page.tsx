import Link from "next/link";
import type { Metadata } from "next";
import { GuestOrders } from "./guest-orders";
import { BackendError } from "@/lib/backend";
import { dateTime, money } from "@/lib/format";
import { getT } from "@/lib/i18n-server";
import { OEmpty } from "@/components/open/empty";
import { Monogram } from "@/components/open/media";
import { openJson, openToken, type OpenOrder, type OpenPage } from "@/lib/open";

export const dynamic = "force-dynamic";
export async function generateMetadata(): Promise<Metadata> { const { t } = await getT(); return { title: t("open.myOrders") }; }

export default async function OrdersPage() {
  const { t, locale } = await getT();
  const signedIn = !!(await openToken());
  const list = signedIn ? await openJson<OpenPage<OpenOrder>>("/orders?pageSize=30").catch((e) => { if (e instanceof BackendError && e.status === 401) return null; throw e; }) : null;
  return (
    <div className="o-container max-w-3xl pt-6 md:pt-10">
      <h1 className="o-h1">{t("open.myOrders")}</h1>
      {list ? (
        list.data.length === 0 ? (
          <div className="mt-5"><OEmpty icon="box" title={t("open.orders.none")} href="/" cta={t("open.cart.startShopping")} /></div>
        ) : (
          <ul className="mt-5 grid gap-3">
            {list.data.map((o) => (
              <li key={o.id}>
                <Link href={`/order/${o.id}`} className="o-card block p-4 transition hover:shadow-[var(--o-shadow-lg)]">
                  <div className="flex items-center justify-between gap-3"><p className="min-w-0 truncate font-extrabold">{o.shopName}</p><span className="shrink-0 rounded-full bg-[var(--o-soft)] px-2.5 py-0.5 text-xs font-extrabold">{t(`open.status.${o.status}`)}</span></div>
                  <p className="o-mute mt-0.5 text-xs" dir="ltr">{o.orderNumber} · {dateTime(o.createdAt, locale)}</p>
                  <div className="mt-2.5 flex items-center justify-between gap-3">
                    <span className="flex -space-x-2 rtl:space-x-reverse">{o.items.slice(0, 4).map((i, n) =>   i.image ? <img key={n} src={i.image} alt="" width={36} height={36} className="h-9 w-9 rounded-lg border-2 border-[var(--o-paper)] object-cover" loading="lazy" /> : <span key={n} className="relative h-9 w-9 overflow-hidden rounded-lg border-2 border-[var(--o-paper)]"><Monogram name={i.name} /></span>)}</span>
                    <b>{money(o.totalMinor, o.currency, locale)}</b>
                  </div>
                </Link>
              </li>
            ))}
          </ul>
        )
      ) : (
        <GuestOrders signedOut={!signedIn || list === null} />
      )}
    </div>
  );
}
