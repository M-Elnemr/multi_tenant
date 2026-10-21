import Link from "next/link";
import type { Metadata } from "next";
import { GuestOrder } from "@/components/open/guest-order";
import { getT } from "@/lib/i18n-server";
import { dec } from "@/lib/open-types";

export async function generateMetadata(): Promise<Metadata> { const { t } = await getT(); return { title: t("open.orderDetails") }; }

export default async function GuestOrderPage({ params }: { params: Promise<{ shop: string; number: string }> }) {
  const { t } = await getT();
  const { shop, number } = await params;
  return (
    <div className="o-container max-w-3xl pt-6 md:pt-10">
      <Link href="/orders" className="o-link">‹ {t("open.myOrders")}</Link>
      <h1 className="mb-5 mt-3 text-2xl font-black md:text-3xl">{t("open.orderDetails")}</h1>
      <GuestOrder shop={dec(shop)} number={dec(number)} />
    </div>
  );
}
