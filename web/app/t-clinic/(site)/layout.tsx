import Link from "next/link";
import { ClinicAccountLink } from "@/components/clinic-bits";
import { currentHost } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";
import { resolveHost } from "@/lib/tenant";

export default async function ClinicSiteLayout({ children }: { children: React.ReactNode }) {
  const { t, locale } = await getT();
  const info = await resolveHost(await currentHost());
  const logo = info.kind === "TENANT" && (info.branding as { logo_file_id?: string }).logo_file_id;
  return (
    <div className="flex min-h-screen flex-col">
      <header className="sticky top-0 z-30 border-b border-slate-200 bg-white/95 backdrop-blur">
        <div className="mx-auto flex max-w-5xl items-center justify-between gap-3 px-4 py-3">
          <Link href="/" className="flex items-center gap-2 text-lg font-bold text-brand">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            {logo && <img src={`/api/bff/files/${logo}/content?variant=thumb`} alt="" className="h-8 w-8 rounded object-cover" />}
            {info.kind === "TENANT" ? info.name : ""}
          </Link>
          <nav className="flex items-center gap-2 text-sm">
            <Link href="/book" className="rounded-lg bg-brand px-3 py-1.5 font-medium text-white">{t("clinic.book")}</Link>
            <ClinicAccountLink />
            <a href={`/api/lang?l=${locale === "ar" ? "en" : "ar"}`} className="px-1 text-slate-500">{locale === "ar" ? "EN" : "عربي"}</a>
          </nav>
        </div>
      </header>
      <main className="mx-auto w-full max-w-5xl flex-1 px-4 py-8">{children}</main>
      <footer className="border-t bg-white py-6 text-center text-sm text-slate-500">© {new Date().getFullYear()} {info.kind === "TENANT" ? info.name : ""}</footer>
    </div>
  );
}
