import Link from "next/link";
import { LogoMark } from "@/components/brand";
import { getT } from "@/lib/i18n-server";

export default async function NotFound() {
  const { t } = await getT();
  return (
    <main className="mx-auto flex min-h-[70vh] max-w-md animate-fade-up flex-col items-center justify-center gap-4 p-6 text-center">
      <LogoMark className="h-24 w-24 animate-float" />
      <h1 className="text-gradient text-6xl font-extrabold">404</h1>
      <p className="text-slate-600">{t("common.notFound")}</p>
      <Link href="/" className="rounded-xl bg-brand-gradient px-6 py-2.5 text-sm font-semibold text-white shadow-brand transition hover:-translate-y-px active:scale-[.97]">{t("common.home")}</Link>
    </main>
  );
}
