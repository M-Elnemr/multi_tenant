import Link from "next/link";
import type { Metadata } from "next";
import { OrderGroupView, type Group } from "@/components/open/order-group";
import { BackendError } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";
import { openJson } from "@/lib/open";

export const dynamic = "force-dynamic";
export async function generateMetadata(): Promise<Metadata> { const { t } = await getT(); return { title: t("open.done.title") }; }

export default async function DonePage({ searchParams }: { searchParams: Promise<{ g?: string; p?: string }> }) {
  const { t } = await getT();
  const { g, p } = await searchParams;
  const group = g ? await openJson<Group>(`/orders/${encodeURIComponent(g)}${p ? `?phone=${encodeURIComponent(p)}` : ""}`).catch((e) => { if (e instanceof BackendError) return null; throw e; }) : null;
  return (
    <div className="o-container max-w-3xl pt-8 md:pt-14">
      <div className="o-card o-reveal overflow-hidden text-center">
        <div className="bg-gradient-to-br from-[#12b76a] to-[#0e9f6e] px-6 py-10 text-white">
          <span className="o-pop mx-auto grid h-20 w-20 place-items-center rounded-full bg-white/20 text-5xl backdrop-blur">✓</span>
          <h1 className="mt-4 text-2xl font-black md:text-3xl">{t("open.done.title")}</h1>
          <p className="mx-auto mt-2 max-w-md text-white/90">{t("open.done.sub")}</p>
        </div>
        <div className="p-5 text-start md:p-8">
          {group ? <OrderGroupView group={group} compact /> : <p className="o-mute text-center">{t("open.done.notFound")}</p>}
          <div className="mt-6 flex flex-wrap justify-center gap-3">
            {g && <Link href={`/orders/${g}${p ? `?phone=${encodeURIComponent(p)}` : ""}`} className="o-btn o-btn-dark">{t("open.done.track")}</Link>}
            <Link href="/" className="o-btn o-btn-ghost">{t("open.cart.startShopping")}</Link>
          </div>
        </div>
      </div>
    </div>
  );
}
