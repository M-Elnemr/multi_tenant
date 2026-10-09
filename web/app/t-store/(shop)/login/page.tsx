import Link from "next/link";
import { Suspense } from "react";
import { AuthCard } from "@/components/auth-card";
import { LoginForm } from "@/components/login-form";
import { getT } from "@/lib/i18n-server";

export default async function ShopLogin({ searchParams }: { searchParams: Promise<{ phone?: string }> }) {
  const { t } = await getT();
  const { phone } = await searchParams;
  return (
    <AuthCard title={t("login.title")} subtitle={t("login.shopHint")}>
      <Suspense><LoginForm defaultIdentifier={phone ?? ""} /></Suspense>
      <p className="mt-4 text-center text-sm text-slate-600">{t("login.noAccount")} <Link href="/register" className="font-medium text-brand underline">{t("login.createAccount")}</Link></p>
    </AuthCard>
  );
}
