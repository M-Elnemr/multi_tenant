import Link from "next/link";
import { Suspense } from "react";
import { LoginForm } from "@/components/login-form";
import { backendJson } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";

export default async function ClinicLogin({ searchParams }: { searchParams: Promise<{ phone?: string }> }) {
  const { t } = await getT();
  const { phone } = await searchParams;
  // patient accounts are off for now: the clinic login is for the doctor and staff only, with no patient wording or "claim my file" link
  const portal = await backendJson<{ patientPortalEnabled?: boolean }>("/clinic/public/profile").then((p) => p.patientPortalEnabled === true).catch(() => false);
  return (
    <div className="mx-auto max-w-md">
      <h1 className="mb-1 text-2xl font-semibold">{t("login.title")}</h1>
      <p className="mb-6 text-sm text-slate-500">{portal ? t("login.clinicHint") : t("login.staffHint")}</p>
      <div className="rounded-2xl border border-slate-200 bg-white p-6"><Suspense><LoginForm defaultIdentifier={phone ?? ""} /></Suspense></div>
      {portal && <p className="mt-4 text-center text-sm text-slate-600">{t("login.haveCode")} <Link href="/link" className="font-medium text-brand underline">{t("link.title")}</Link></p>}
    </div>
  );
}
