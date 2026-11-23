import { GoogleCompleteOpen } from "./complete";
import { getT } from "@/lib/i18n-server";

export const dynamic = "force-dynamic";

export default async function GoogleCompletePage() {
  const { t } = await getT();
  return <div className="o-container max-w-md pt-16 text-center"><p className="text-4xl">🔐</p><h1 className="mt-3 text-xl font-black">{t("open.signIn")}</h1><div className="mt-4"><GoogleCompleteOpen /></div></div>;
}
