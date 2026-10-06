import { getT } from "@/lib/i18n-server";

export default async function UnknownHost() {
  const { t } = await getT();
  return (
    <main className="mx-auto flex min-h-screen max-w-md flex-col items-center justify-center gap-3 p-6 text-center">
      <h1 className="text-2xl font-semibold">{t("unknown.title")}</h1>
      <p className="text-slate-600">{t("unknown.body")}</p>
    </main>
  );
}
