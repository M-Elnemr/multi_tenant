import Link from "next/link";
import { getT } from "@/lib/i18n-server";

export default async function NotFound() {
  const { t } = await getT();
  return (
    <main className="mx-auto flex min-h-[60vh] max-w-md flex-col items-center justify-center gap-4 p-6 text-center">
      <h1 className="text-5xl font-bold text-slate-300">404</h1>
      <p className="text-slate-600">{t("common.notFound")}</p>
      <Link href="/" className="rounded-lg bg-brand px-4 py-2 text-sm font-medium text-white">{t("common.home")}</Link>
    </main>
  );
}
