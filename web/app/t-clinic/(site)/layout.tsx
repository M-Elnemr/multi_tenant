import Link from "next/link";
import { PoweredBy } from "@/components/brand";
import { ClinicAccountLink } from "@/components/clinic-bits";
import { Announcement, MobileBar, mainPhone } from "@/components/clinic/sections";
import { Icon } from "@/components/icons";
import { SocialLinks, hasSocial } from "@/components/social-icons";
import { backendJson, currentHost } from "@/lib/backend";
import { type ClinicSite } from "@/lib/clinic-site";
import { fileUrl } from "@/lib/media";
import { getT } from "@/lib/i18n-server";
import { resolveHost } from "@/lib/tenant";
import { ScrollHeader, ScrollProgress } from "@/components/clinic/motion";
import "./clinic.css";

export default async function ClinicSiteLayout({ children }: { children: React.ReactNode }) {
  const { t, locale } = await getT();
  const info = await resolveHost(await currentHost());
  // Patients sign in with the account their clinic created; online booking stays hidden while the platform has it switched off.
  const p = await backendJson<ClinicSite>("/clinic/public/profile").catch(() => null);
  const portal = p?.patientPortalEnabled === true;
  const booking = p?.bookingEnabled === true;
  const logo = info.kind === "TENANT" && (info.branding as { logo_file_id?: string }).logo_file_id;
  const name = p?.clinicName || (info.kind === "TENANT" ? info.name : "");
  const nav = p ? [
    p.doctors.length > 0 && { href: "/#doctors", label: t("clinic.doctors") },
    p.services.length > 0 && { href: "/#services", label: t("clinic.services") },
    (p.branches.length > 0 || !!p.workingHours) && { href: "/#hours", label: t("csite.visit") },
    (p.faqs?.length ?? 0) > 0 && { href: "/#faq", label: t("csite.faqEyebrow") },
    { href: "/#contact", label: t("csite.contact") },
  ].filter(Boolean) as { href: string; label: string }[] : [];
  const phone = p ? mainPhone(p) : "";
  return (
    <div className="clinic flex min-h-screen flex-col">
      {p && <Announcement p={p} t={t} />}
      <ScrollProgress />
      <ScrollHeader>
        <div className="c-container flex items-center justify-between gap-3 py-2.5">
          <Link href="/" className="flex min-w-0 items-center gap-2.5 text-lg font-extrabold" style={{ color: "var(--brand)" }}>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            {logo && <img src={fileUrl(logo, "thumb")} alt="" className="h-10 w-10 shrink-0 rounded-xl object-cover shadow-sm" />}
            <span className="truncate">{name}</span>
          </Link>
          <nav className="c-nav hidden items-center gap-1 lg:flex">{nav.map((n) => <a key={n.href} href={n.href}>{n.label}</a>)}</nav>
          <div className="flex shrink-0 items-center gap-2 text-sm">
            {booking && <Link href="/book" className="c-btn c-btn-sm">{t("clinic.book")}</Link>}
            {!booking && phone && <a href={`tel:${phone}`} className="c-btn c-btn-sm hidden sm:inline-flex"><Icon name="phone" className="h-4 w-4" />{t("csite.call")}</a>}
            <ClinicAccountLink portal={portal} />
            <a href={`/api/lang?l=${locale === "ar" ? "en" : "ar"}`} className="rounded-lg px-2 py-1 font-medium text-slate-500 transition hover:bg-slate-100 hover:text-slate-900">{locale === "ar" ? "EN" : "عربي"}</a>
          </div>
        </div>
      </ScrollHeader>
      <main className="flex-1 pb-24 md:pb-0">{children}</main>
      <footer className="mt-20 border-t border-[var(--c-line)] bg-white">
        <div className="c-container grid gap-8 py-10 md:grid-cols-3">
          <div>
            <p className="flex items-center gap-2.5 text-xl font-extrabold" style={{ color: "var(--brand)" }}>
              {/* eslint-disable-next-line @next/next/no-img-element */}
              {logo && <img src={fileUrl(logo, "thumb")} alt="" className="h-10 w-10 rounded-xl object-cover" />}{name}
            </p>
            {p?.tagline && <p className="c-mute mt-2 text-sm">{p.tagline}</p>}
            {p && hasSocial(p) && <SocialLinks p={p} className="mt-4" size={38} />}
          </div>
          <ul className="c-mute space-y-2 text-sm">
            {p?.addressText && <li className="flex gap-2"><Icon name="pin" className="mt-0.5 h-4 w-4 shrink-0" />{p.addressText}</li>}
            {phone && <li className="flex gap-2"><Icon name="phone" className="mt-0.5 h-4 w-4 shrink-0" /><a href={`tel:${phone}`} dir="ltr" className="hover:underline">{phone}</a></li>}
            {p?.email && <li className="flex gap-2"><Icon name="mail" className="mt-0.5 h-4 w-4 shrink-0" /><a href={`mailto:${p.email}`} className="break-all hover:underline">{p.email}</a></li>}
          </ul>
          <nav className="c-mute flex flex-wrap content-start gap-x-5 gap-y-2 text-sm">{nav.map((n) => <a key={n.href} href={n.href} className="hover:text-[var(--brand)]">{n.label}</a>)}</nav>
        </div>
        <div className="border-t border-[var(--c-line)] py-5 text-center text-xs text-slate-500">
          <p>© {new Date().getFullYear()} {name}. {t("footer.rights")}</p>
          <div className="mt-2"><PoweredBy label={t("brand.powered")} name={t("brand.name")} /></div>
        </div>
      </footer>
      {p && <MobileBar p={p} t={t} hrefBook={booking ? "/book" : undefined} />}
    </div>
  );
}
