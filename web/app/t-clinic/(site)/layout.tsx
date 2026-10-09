import Link from "next/link";
import { PoweredBy } from "@/components/brand";
import { ClinicAccountLink } from "@/components/clinic-bits";
import { backendJson, currentHost } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";
import { resolveHost } from "@/lib/tenant";

export default async function ClinicSiteLayout({ children }: { children: React.ReactNode }) {
  const { t, locale } = await getT();
  const info = await resolveHost(await currentHost());
  // Patient accounts are off for now: no online booking and no patient login on the public site (staff still sign in).
  const portal = await backendJson<{ patientPortalEnabled?: boolean }>("/clinic/public/profile").then((p) => p.patientPortalEnabled === true).catch(() => false);
  const logo = info.kind === "TENANT" && (info.branding as { logo_file_id?: string }).logo_file_id;
  return (
    <div className="flex min-h-screen flex-col">
      <header className="glass sticky top-0 z-30 border-b border-slate-200/70">
        <div className="mx-auto flex max-w-5xl items-center justify-between gap-3 px-4 py-3">
          <Link href="/" className="flex items-center gap-2.5 text-lg font-extrabold text-brand">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            {logo && <img src={`/api/bff/files/${logo}/content?variant=thumb`} alt="" className="h-9 w-9 rounded-xl object-cover shadow-sm" />}
            {info.kind === "TENANT" ? info.name : ""}
          </Link>
          <nav className="flex items-center gap-2 text-sm">
            {portal && <Link href="/book" className="rounded-xl bg-brand-gradient px-4 py-2 font-semibold text-white shadow-brand transition hover:-translate-y-px active:scale-[.97]">{t("clinic.book")}</Link>}
            <ClinicAccountLink portal={portal} />
            <a href={`/api/lang?l=${locale === "ar" ? "en" : "ar"}`} className="rounded-lg px-2 py-1 font-medium text-slate-500 transition hover:bg-slate-100 hover:text-slate-900">{locale === "ar" ? "EN" : "عربي"}</a>
          </nav>
        </div>
      </header>
      <main className="mx-auto w-full max-w-5xl flex-1 px-4 py-8"><div className="animate-fade-up">{children}</div></main>
      <footer className="border-t border-slate-200/70 bg-white py-8 text-center text-sm text-slate-500"><p className="font-medium text-slate-600">© {new Date().getFullYear()} {info.kind === "TENANT" ? info.name : ""}</p><div className="mt-2"><PoweredBy label={t("brand.powered")} name={t("brand.name")} /></div></footer>
    </div>
  );
}
