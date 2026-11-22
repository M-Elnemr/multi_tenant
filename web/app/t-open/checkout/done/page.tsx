import Link from "next/link";
import type { Metadata } from "next";
import { GuestOrder } from "@/components/open/guest-order";
import { Icon } from "@/components/icons";
import { getT } from "@/lib/i18n-server";

export const dynamic = "force-dynamic";
export async function generateMetadata(): Promise<Metadata> { const { t } = await getT(); return { title: t("open.done.title") }; }

export default async function DonePage({ searchParams }: { searchParams: Promise<{ shop?: string; n?: string }> }) {
  const { t } = await getT();
  const { shop, n } = await searchParams;
  return (
    <div className="o-container max-w-3xl pt-8 md:pt-14">
      <div className="o-card o-reveal overflow-hidden text-center">
        <div className="border-b border-[var(--o-line)] bg-[var(--o-green-soft)] px-6 py-10">
          <span className="o-pop mx-auto grid h-16 w-16 place-items-center rounded-full bg-[var(--o-green)] text-white"><Icon name="check" className="h-8 w-8" /></span>
          <h1 className="o-h1 mt-4">{t("open.done.title")}</h1>
          <p className="mx-auto mt-2 max-w-md text-[var(--o-mute)]">{t("open.done.sub")}</p>
        </div>
        <div className="p-5 text-start md:p-8">
          {shop && n && <GuestOrder shop={shop} number={n} compact />}
          <div className="mt-6 flex flex-wrap justify-center gap-3">
            {shop && n && <Link href={`/orders/${encodeURIComponent(shop)}/${encodeURIComponent(n)}`} className="o-btn o-btn-dark">{t("open.done.track")}</Link>}
            <Link href="/" className="o-btn o-btn-ghost">{t("open.cart.startShopping")}</Link>
          </div>
        </div>
      </div>
    </div>
  );
}
