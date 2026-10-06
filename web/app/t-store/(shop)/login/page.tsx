import Link from "next/link";
import { Suspense } from "react";
import { LoginForm } from "@/components/login-form";
import { getT } from "@/lib/i18n-server";

export default async function ShopLogin({ searchParams }: { searchParams: Promise<{ phone?: string }> }) {
  const { t } = await getT();
  const { phone } = await searchParams;
  return (
    <div className="mx-auto max-w-md">
      <h1 className="mb-1 text-2xl font-semibold">{t("login.title")}</h1>
      <p className="mb-6 text-sm text-slate-500">{t("login.shopHint")}</p>
      <div className="rounded-2xl border border-slate-200 bg-white p-6"><Suspense><LoginForm defaultIdentifier={phone ?? ""} /></Suspense></div>
      <p className="mt-4 text-center text-sm text-slate-600">{t("login.noAccount")} <Link href="/register" className="font-medium text-brand underline">{t("login.createAccount")}</Link></p>
    </div>
  );
}
