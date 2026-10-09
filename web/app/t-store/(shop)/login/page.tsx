import Link from "next/link";
import { Suspense } from "react";
import { AuthCard } from "@/components/auth-card";
import { LoginForm } from "@/components/login-form";
import { ShopSignIn } from "@/components/shop-sign-in";
import { getT } from "@/lib/i18n-server";

export default async function ShopLogin() {
  const { t } = await getT();
  return (
    <AuthCard title={t("login.title")} subtitle={t("login.shopHint")}>
      <ShopSignIn />
      <p className="mt-4 text-center text-sm text-slate-600">{t("login.guestNote")} <Link href="/products" className="font-medium text-brand underline">{t("shop.browse")}</Link></p>
      <details className="mt-6 border-t pt-4">
        <summary className="cursor-pointer text-center text-sm text-slate-500">{t("login.storeStaff")}</summary>
        <div className="mt-4"><Suspense><LoginForm /></Suspense></div>
      </details>
    </AuthCard>
  );
}
