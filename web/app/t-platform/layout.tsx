import Link from "next/link";
import { getT } from "@/lib/i18n-server";

export default async function PlatformLayout({ children }: { children: React.ReactNode }) {
  const { t, locale } = await getT();
  return (
    <div className="flex min-h-screen flex-col">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-3">
          <Link href="/" className="text-lg font-bold text-brand">{t("brand.name")}</Link>
          <nav className="flex items-center gap-4 text-sm">
            <Link href="/pricing" className="text-slate-600 hover:text-slate-900">{t("nav.pricing")}</Link>
            <Link href="/login" className="text-slate-600 hover:text-slate-900">{t("nav.login")}</Link>
            <Link href="/register" className="rounded-lg bg-brand px-3 py-2 font-medium text-white">{t("nav.startFree")}</Link>
            <a href={`/api/lang?l=${locale === "ar" ? "en" : "ar"}`} className="text-slate-500 hover:text-slate-800">{locale === "ar" ? "EN" : "عربي"}</a>
          </nav>
        </div>
      </header>
      <main className="flex-1">{children}</main>
      <footer className="border-t border-slate-200 bg-white py-6 text-center text-sm text-slate-500">© {new Date().getFullYear()} {t("brand.name")}</footer>
    </div>
  );
}
