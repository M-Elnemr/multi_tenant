import Link from "next/link";
import { AuthCard } from "@/components/auth-card";
import { ShopSignIn } from "@/components/shop-sign-in";
import { getT } from "@/lib/i18n-server";

export default async function ShopLogin() {
  const { t } = await getT();
  return (
    <AuthCard title={t("login.title")} subtitle={t("login.shopHint")}>
      <ShopSignIn />
      <p className="mt-4 text-center text-sm text-slate-600">{t("login.guestNote")} <Link href="/products" className="font-medium text-brand underline">{t("shop.browse")}</Link></p>
      <p className="mt-6 border-t pt-4 text-center text-sm text-slate-500">{t("login.storeStaff")} <Link href="/dashboard/login" className="font-semibold text-brand underline">{t("staffLogin.go")}</Link></p>
    </AuthCard>
  );
}
