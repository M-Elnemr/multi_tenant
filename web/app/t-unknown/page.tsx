import Link from "next/link";
import { LogoMark } from "@/components/brand";
import { getT } from "@/lib/i18n-server";

export default async function UnknownHost({ searchParams }: { searchParams?: Promise<Record<string, string | string[] | undefined>> }) {
  const { t } = await getT();
  const disabled = (await searchParams)?.reason === "disabled";
  return (
    <main className="mx-auto flex min-h-screen max-w-md animate-fade-up flex-col items-center justify-center gap-3 p-6 text-center">
      <LogoMark className="mb-2 h-24 w-24 animate-float" />
      <h1 className="text-2xl font-bold">{disabled ? t("disabled.title") : t("unknown.title")}</h1>
      <p className="text-slate-600">{disabled ? t("disabled.body") : t("unknown.body")}</p>
      {disabled && <Link href="/login?next=/dashboard/billing/pay" className="mt-3 text-sm font-semibold text-brand underline">{t("disabled.owner")}</Link>}
    </main>
  );
}
