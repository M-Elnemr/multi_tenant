import Link from "next/link";
import { LogoutButton } from "@/components/logout-button";
import { currentHost } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";
import { resolveHost } from "@/lib/tenant";

export default async function PortalLayout({ children }: { children: React.ReactNode }) {
  const { t, locale } = await getT();
  const info = await resolveHost(await currentHost());
  return (
    <div className="min-h-screen bg-slate-50">
      <header className="border-b bg-white">
        <div className="mx-auto flex max-w-4xl items-center justify-between gap-3 px-4 py-3">
          <Link href="/" className="font-bold text-brand">{info.kind === "TENANT" ? info.name : ""}</Link>
          <nav className="flex items-center gap-1 text-sm">
            <Link href="/portal" className="rounded-lg px-3 py-1.5 hover:bg-slate-100">{t("portal.appointments")}</Link>
            <Link href="/portal/record" className="rounded-lg px-3 py-1.5 hover:bg-slate-100">{t("portal.record")}</Link>
            <Link href="/book" className="rounded-lg bg-brand px-3 py-1.5 text-white">{t("clinic.book")}</Link>
            <LogoutButton />
            <a href={`/api/lang?l=${locale === "ar" ? "en" : "ar"}`} className="px-1 text-slate-500">{locale === "ar" ? "EN" : "عربي"}</a>
          </nav>
        </div>
      </header>
      <main className="mx-auto max-w-4xl px-4 py-8">{children}</main>
    </div>
  );
}
