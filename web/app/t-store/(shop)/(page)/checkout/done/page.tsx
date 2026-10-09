import Link from "next/link";
import type { ShopProfile } from "@/components/shop/types";
import { backendJson } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";
import { whatsappLink } from "@/lib/whatsapp";

export default async function OrderPlaced({ searchParams }: { searchParams: Promise<{ n?: string; p?: string }> }) {
  const { t } = await getT();
  const { n, p } = await searchParams;
  const profile = (await backendJson<{ profile: ShopProfile }>("/shop/profile").catch(() => null))?.profile;
  return (
    <div className="s-card mx-auto max-w-lg animate-scale-in space-y-4 p-8 text-center sm:p-12">
      <div className="mx-auto grid h-20 w-20 place-items-center rounded-full bg-emerald-100 text-4xl text-emerald-600">✓</div>
      <h1 className="text-3xl font-extrabold">{t("checkout.doneTitle")}</h1>
      {n && <p className="rounded-2xl bg-[var(--s-soft)] py-3 font-mono text-xl font-extrabold" dir="ltr" style={{ color: "var(--brand)" }}>{n}</p>}
      <p className="text-[var(--s-mute)]">{t("checkout.doneBody")}</p>
      <p className="text-sm font-bold">{t("checkout.doneCall")}</p>
      <div className="flex flex-col gap-3 pt-2 sm:flex-row sm:justify-center">
        {n && <Link href={`/track?n=${encodeURIComponent(n)}${p ? `&p=${encodeURIComponent(p)}` : ""}`} className="s-btn">{t("shop.trackOrder")}</Link>}
        {profile?.whatsapp && <a href={whatsappLink(profile.whatsapp, n ? `${t("track.whatsappMsg")} ${n}` : "")} target="_blank" rel="noopener noreferrer" className="s-btn s-btn-ghost">💬 {t("shop.chatWithUs")}</a>}
      </div>
      <Link href="/products" className="block pt-2 text-sm font-bold" style={{ color: "var(--brand)" }}>← {t("cart.continue")}</Link>
    </div>
  );
}
