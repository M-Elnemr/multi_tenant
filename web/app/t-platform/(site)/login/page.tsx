import { Suspense } from "react";
import { AuthCard } from "@/components/auth-card";
import { LoginForm } from "@/components/login-form";
import { getT } from "@/lib/i18n-server";

export default async function PlatformLogin({ searchParams }: { searchParams: Promise<{ phone?: string }> }) {
  const { t } = await getT();
  const { phone } = await searchParams;
  return (
    <AuthCard title={t("login.title")} subtitle={t("login.platformHint")}>
      <Suspense><LoginForm defaultIdentifier={phone ?? ""} /></Suspense>
    </AuthCard>
  );
}
