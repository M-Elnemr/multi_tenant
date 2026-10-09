import { LogoMark } from "@/components/brand";
import { getT } from "@/lib/i18n-server";

export default async function UnknownHost() {
  const { t } = await getT();
  return (
    <main className="mx-auto flex min-h-screen max-w-md animate-fade-up flex-col items-center justify-center gap-3 p-6 text-center">
      <LogoMark className="mb-2 h-24 w-24 animate-float" />
      <h1 className="text-2xl font-bold">{t("unknown.title")}</h1>
      <p className="text-slate-600">{t("unknown.body")}</p>
    </main>
  );
}
