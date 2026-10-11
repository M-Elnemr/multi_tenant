import Link from "next/link";
import { Logo, LogoMark } from "@/components/brand";
import { PlatformAccountLinks, PlatformFooterLinks } from "@/components/platform-bits";
import { getT } from "@/lib/i18n-server";

export default async function PlatformLayout({ children }: { children: React.ReactNode }) {
  const { t, locale } = await getT();
  const lang = <a href={`/api/lang?l=${locale === "ar" ? "en" : "ar"}`} className="rounded-lg px-2 py-1 text-sm font-medium text-slate-500 transition hover:bg-slate-100 hover:text-slate-900">{locale === "ar" ? "EN" : "عربي"}</a>;
  return (
    <div className="flex min-h-screen flex-col">
      <header className="glass sticky top-0 z-40 border-b border-slate-200/70">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-2.5">
          <Logo name={t("brand.name")} className="h-10 w-10" textClass="text-xl font-extrabold" />
          <nav className="flex items-center gap-1 text-sm sm:gap-3">
            <Link href="/pricing" className="hidden rounded-lg px-3 py-2 font-medium text-slate-600 transition hover:bg-slate-100 hover:text-slate-900 sm:block">{t("nav.pricing")}</Link>
            <PlatformAccountLinks />
            {lang}
          </nav>
        </div>
      </header>
      <main className="flex-1">{children}</main>
      <footer className="border-t border-slate-200/70 bg-white">
        <div className="mx-auto flex max-w-6xl flex-col items-center justify-between gap-4 px-4 py-8 sm:flex-row">
          <div className="flex items-center gap-3"><LogoMark className="h-9 w-9" /><span className="font-bold text-ink">{t("brand.name")}</span></div>
          <nav className="flex items-center gap-5 text-sm text-slate-500">
            <Link href="/pricing" className="hover:text-slate-900">{t("nav.pricing")}</Link>
            <PlatformFooterLinks className="hover:text-slate-900" />
            <Link href="/privacy" className="hover:text-slate-900">{t("nav.privacy")}</Link>
            <Link href="/delete-account" className="hover:text-slate-900">{t("nav.deleteAccount")}</Link>
          </nav>
          <p className="text-sm text-slate-400">© {new Date().getFullYear()} {t("brand.name")}</p>
        </div>
      </footer>
    </div>
  );
}
