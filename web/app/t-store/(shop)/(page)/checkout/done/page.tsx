import Link from "next/link";
import { getT } from "@/lib/i18n-server";

export default async function OrderPlaced({ searchParams }: { searchParams: Promise<{ n?: string }> }) {
  const { t } = await getT();
  const { n } = await searchParams;
  return (
    <div className="mx-auto max-w-md animate-scale-in rounded-3xl border border-emerald-200 bg-white p-8 text-center shadow-soft">
      <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-emerald-100 text-3xl">✓</div>
      <h1 className="text-2xl font-extrabold">{t("checkout.doneTitle")}</h1>
      {n && <p className="mt-2 font-mono text-lg text-brand" dir="ltr">{n}</p>}
      <p className="mt-3 text-slate-600">{t("checkout.doneBody")}</p>
      <Link href="/products" className="mt-6 inline-block rounded-xl bg-brand-gradient px-6 py-2.5 font-semibold text-white shadow-brand">{t("shop.browse")}</Link>
    </div>
  );
}
