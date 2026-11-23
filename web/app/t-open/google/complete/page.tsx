import { GoogleCompleteOpen } from "./complete";
import { Icon } from "@/components/icons";
import { getT } from "@/lib/i18n-server";

export const dynamic = "force-dynamic";

export default async function GoogleCompletePage() {
  const { t } = await getT();
  return <div className="o-container max-w-md pt-16 text-center"><span className="mx-auto grid h-12 w-12 place-items-center rounded-full border border-[var(--o-line)] bg-[var(--o-soft)] text-[var(--o-accent)]"><Icon name="shield" className="h-6 w-6" /></span><h1 className="o-h1 mt-3 !text-2xl">{t("open.signIn")}</h1><div className="mt-4"><GoogleCompleteOpen /></div></div>;
}
