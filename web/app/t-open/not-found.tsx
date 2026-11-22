import { OEmpty } from "@/components/open/empty";
import { getT } from "@/lib/i18n-server";

export default async function NotFound() {
  const { t } = await getT();
  return <div className="o-container pt-12 md:pt-20"><OEmpty icon="search" title={t("open.notFound.title")} body={t("open.notFound.body")} href="/" cta={t("shop.home")} /></div>;
}
