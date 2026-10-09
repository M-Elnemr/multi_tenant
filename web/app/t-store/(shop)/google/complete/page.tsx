import { AuthCard } from "@/components/auth-card";
import { GoogleComplete } from "@/components/google-relay";
import { getT } from "@/lib/i18n-server";

export const dynamic = "force-dynamic";

export default async function GoogleCompletePage() {
  const { t } = await getT();
  return <AuthCard title={t("login.title")} subtitle={t("login.shopHint")}><GoogleComplete /></AuthCard>;
}
