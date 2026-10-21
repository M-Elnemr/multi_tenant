import Link from "next/link";
import type { Metadata } from "next";
import { OrderView } from "@/components/open/order-view";
import { BackendError } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";
import { openJson, openToken, type OpenOrder } from "@/lib/open";

export const dynamic = "force-dynamic";
export async function generateMetadata(): Promise<Metadata> { const { t } = await getT(); return { title: t("open.orderDetails") }; }

/** One of the signed-in shopper's own orders. */
export default async function OrderPage({ params }: { params: Promise<{ id: string }> }) {
  const { t } = await getT();
  const { id } = await params;
  const signedIn = !!(await openToken());
  const order = signedIn ? await openJson<OpenOrder>(`/orders/${encodeURIComponent(id)}`).catch((e) => { if (e instanceof BackendError) return null; throw e; }) : null;
  return (
    <div className="o-container max-w-3xl pt-6 md:pt-10">
      <Link href="/orders" className="o-link">‹ {t("open.myOrders")}</Link>
      {!order ? (
        <div className="o-card mt-4 px-6 py-16 text-center"><p className="text-5xl">📦</p><h1 className="o-title mt-3">{t("open.order.notFound")}</h1><p className="o-mute mt-1">{t("open.order.notFoundHint")}</p></div>
      ) : (
        <>
          <h1 className="mb-5 mt-3 text-2xl font-black md:text-3xl">{t("open.orderDetails")}</h1>
          <OrderView order={order} canReview />
        </>
      )}
    </div>
  );
}
