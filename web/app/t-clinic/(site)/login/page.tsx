import { Suspense } from "react";
import { AuthCard } from "@/components/auth-card";
import { LoginForm } from "@/components/login-form";
import { backendJson } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";

export default async function ClinicLogin({ searchParams }: { searchParams: Promise<{ phone?: string }> }) {
  const { t } = await getT();
  const { phone } = await searchParams;
  // patients use the mobile number + temporary password their clinic gave them; there is no self-registration
  const portal = await backendJson<{ patientPortalEnabled?: boolean }>("/clinic/public/profile").then((p) => p.patientPortalEnabled === true).catch(() => false);
  return (
    <AuthCard title={t("login.title")} subtitle={portal ? t("login.clinicHint") : t("login.staffHint")}>
      <Suspense><LoginForm defaultIdentifier={phone ?? ""} /></Suspense>
      {portal && <p className="mt-4 text-center text-sm text-slate-600">{t("login.patientNote")}</p>}
    </AuthCard>
  );
}
