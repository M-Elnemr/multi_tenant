import Link from "next/link";
import type { Metadata } from "next";
import { OEmpty } from "@/components/open/empty";
import { Icon } from "@/components/icons";
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
      <Link href="/orders" className="o-link inline-flex items-center gap-1"><Icon name="chevron" className="h-3.5 w-3.5 rotate-180 rtl:rotate-0" />{t("open.myOrders")}</Link>
      {!order ? (
        <div className="mt-4"><OEmpty icon="box" title={t("open.order.notFound")} body={t("open.order.notFoundHint")} href="/orders" cta={t("open.myOrders")} /></div>
      ) : (
        <>
          <h1 className="o-h1 mb-5 mt-3">{t("open.orderDetails")}</h1>
          <OrderView order={order} canReview />
        </>
      )}
    </div>
  );
}
