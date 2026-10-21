import Link from "next/link";
import { getT } from "@/lib/i18n-server";

export default async function NotFound() {
  const { t } = await getT();
  return (
    <div className="o-container pt-20 text-center">
      <p className="text-6xl">🧭</p>
      <h1 className="mt-4 text-2xl font-black">{t("open.notFound.title")}</h1>
      <p className="o-mute mt-1">{t("open.notFound.body")}</p>
      <Link href="/" className="o-btn mt-6">{t("shop.home")}</Link>
    </div>
  );
}
