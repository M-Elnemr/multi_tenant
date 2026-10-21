import Link from "next/link";
import type { Metadata } from "next";
import { OrderGroupView, type Group } from "@/components/open/order-group";
import { BackendError } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";
import { openJson, openToken } from "@/lib/open";

export const dynamic = "force-dynamic";
export async function generateMetadata(): Promise<Metadata> { const { t } = await getT(); return { title: t("open.orderDetails") }; }

export default async function OrderPage({ params, searchParams }: { params: Promise<{ group: string }>; searchParams: Promise<{ phone?: string }> }) {
  const { t } = await getT();
  const { group: id } = await params;
  const { phone } = await searchParams;
  const group = await openJson<Group>(`/orders/${encodeURIComponent(id)}${phone ? `?phone=${encodeURIComponent(phone)}` : ""}`).catch((e) => { if (e instanceof BackendError) return null; throw e; });
  const signedIn = !!(await openToken());
  return (
    <div className="o-container max-w-3xl pt-6 md:pt-10">
      <Link href="/orders" className="o-link">‹ {t("open.myOrders")}</Link>
      {!group ? (
        <div className="o-card mt-4 px-6 py-16 text-center"><p className="text-5xl">📦</p><h1 className="o-title mt-3">{t("open.order.notFound")}</h1><p className="o-mute mt-1">{t("open.order.notFoundHint")}</p></div>
      ) : (
        <>
          <h1 className="mt-3 text-2xl font-black md:text-3xl">{t("open.orderDetails")}</h1>
          <p className="o-mute mb-5 text-sm">{group.name} · <span dir="ltr">{group.phone}</span></p>
          <OrderGroupView group={group} canReview={signedIn} />
          {!signedIn && group.orders.some((o) => o.status === "ARRIVED") && <p className="o-card mt-4 p-4 text-sm">{t("open.review.signInHint")} <Link href={`/login?next=/orders/${id}`} className="o-link">{t("open.signIn")}</Link></p>}
        </>
      )}
    </div>
  );
}
