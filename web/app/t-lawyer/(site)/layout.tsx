import Link from "next/link";
import { VisitTracker } from "@/components/visit-tracker";
import { templateFontVars } from "@/components/shop/templates/fonts";
import { backendJson, currentHost } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";
import { fileUrl } from "@/lib/media";
import { resolveHost } from "@/lib/tenant";
import { whatsappLink } from "@/lib/whatsapp";
import type { LawyerSite } from "./types";
import "./lawyer.css";

export default async function LawyerSiteLayout({ children }: { children: React.ReactNode }) {
  const { t, locale } = await getT();
  const info = await resolveHost(await currentHost());
  const p = await backendJson<LawyerSite>("/lawyer/public/profile").catch(() => null);
  const logo = info.kind === "TENANT" && (info.branding as { logo_file_id?: string }).logo_file_id;
  const name = p ? `${p.title ? p.title + " " : ""}${p.displayName}` : info.kind === "TENANT" ? info.name : "";
  const nav = [
    { href: "/#areas", label: t("lws.nav.areas") }, { href: "/#about", label: t("lws.nav.about") }, { href: "/#services", label: t("lws.nav.services") },
    ...(p?.testimonials.length ? [{ href: "/#reviews", label: t("lws.nav.reviews") }] : []), { href: "/#contact", label: t("lws.nav.contact") },
  ];
  const year = new Date().getFullYear();
  return (
    <div className={`lw flex min-h-dvh flex-col ${templateFontVars}`} data-tpl={p?.template ?? "classic"}>
      <header className="lw-header">
        <div className="lw-container flex items-center justify-between gap-3 py-3">
          <Link href="/" className="flex min-w-0 items-center gap-3">
            {logo
              /* eslint-disable-next-line @next/next/no-img-element */
              ? <img src={fileUrl(logo, "thumb")} alt="" className="h-11 w-11 shrink-0 rounded-xl object-cover" />
              : <span className="grid h-11 w-11 shrink-0 place-items-center rounded-xl text-xl" style={{ background: "var(--lw-hero)", color: "var(--lw-accent)" }}>⚖</span>}
            <span className="min-w-0"><span className="lw-display block truncate text-lg font-bold leading-tight">{name}</span>{p?.barLevel && <span className="block truncate text-[11px] font-semibold" style={{ color: "var(--lw-mute)" }}>{p.barLevel}</span>}</span>
          </Link>
          <nav className="lw-nav hidden items-center gap-1 lg:flex">{nav.map((n) => <a key={n.href} href={n.href}>{n.label}</a>)}</nav>
          <div className="flex shrink-0 items-center gap-2">
            {p?.bookingEnabled !== false && <Link href="/book" className="lw-btn lw-btn-sm">{t("lws.book")}</Link>}
            <Link href="/dashboard/login" aria-label={t("lws.staff")} title={t("lws.staff")} className="grid h-9 w-9 place-items-center rounded-lg transition hover:opacity-100" style={{ color: "var(--lw-mute)" }}><LockIcon /></Link>
            <a href={`/api/lang?l=${locale === "ar" ? "en" : "ar"}`} className="rounded-lg px-2 py-1 text-sm font-bold" style={{ color: "var(--lw-mute)" }}>{locale === "ar" ? "EN" : "عربي"}</a>
          </div>
        </div>
      </header>
      <VisitTracker />
      <main className="flex-1">{children}</main>
      <footer className="lw-dark">
        <div className="lw-container grid gap-10 py-14 md:grid-cols-3">
          <div>
            <p className="lw-display text-2xl font-bold">{name}</p>
            {p?.headline && <p className="mt-2 text-sm opacity-75">{p.headline}</p>}
            {p?.barLevel && <p className="mt-3 inline-flex rounded-full px-3 py-1 text-xs font-bold" style={{ background: "color-mix(in srgb, var(--lw-accent) 22%, transparent)", color: "var(--lw-accent)" }}>{p.barLevel}</p>}
          </div>
          <div className="space-y-2 text-sm opacity-90">
            <p className="text-xs font-bold uppercase tracking-widest opacity-60">{t("lws.nav.contact")}</p>
            {p?.phone && <p><a href={`tel:${p.phone}`} dir="ltr">📞 {p.phone}</a></p>}
            {p?.email && <p><a href={`mailto:${p.email}`}>✉️ {p.email}</a></p>}
            {(p?.officeAddress || p?.officeCity) && <p>📍 {[p.officeAddress, p.officeCity].filter(Boolean).join("، ")}</p>}
          </div>
          <div className="flex flex-col items-start gap-3 md:items-end">
            {p?.whatsapp && <a href={whatsappLink(p.whatsapp, t("lws.waHello"))} target="_blank" rel="noopener noreferrer" className="lw-btn lw-btn-sm">WhatsApp</a>}
            <div className="flex gap-3 text-sm opacity-80">{Object.entries(p?.social ?? {}).filter(([, v]) => v).map(([k, v]) => <a key={k} href={v} target="_blank" rel="noopener noreferrer" className="capitalize hover:opacity-100">{k}</a>)}</div>
            <Link href="/dashboard/login" className="inline-flex items-center gap-2 rounded-xl border border-white/25 px-3.5 py-2 text-sm font-semibold opacity-90 transition hover:border-white/50 hover:opacity-100"><LockIcon />{t("lws.staff")}</Link>
          </div>
        </div>
        <div className="border-t border-white/10 py-5 text-center text-xs opacity-50">© {year} {name}</div>
      </footer>
      {p?.whatsapp && <a href={whatsappLink(p.whatsapp, t("lws.waHello"))} target="_blank" rel="noopener noreferrer" aria-label="WhatsApp" className="fixed bottom-5 end-5 z-40 grid h-14 w-14 place-items-center rounded-full bg-[#25d366] text-2xl text-white shadow-xl transition hover:scale-105">
        <svg viewBox="0 0 24 24" className="h-7 w-7" fill="currentColor"><path d="M20.5 3.5A11.8 11.8 0 0 0 1.9 17.6L.4 23.5l6-1.6A11.8 11.8 0 0 0 20.5 3.5Zm-8.6 18.2c-1.8 0-3.5-.5-5-1.4l-.4-.2-3.6.9 1-3.5-.2-.4a9.8 9.8 0 1 1 8.2 4.6Zm5.4-7.3c-.3-.1-1.7-.9-2-1s-.5-.1-.7.1-.8 1-1 1.2-.4.2-.6.1a8 8 0 0 1-4-3.5c-.3-.5.3-.5.9-1.6.1-.2 0-.4 0-.5l-.9-2.2c-.2-.6-.5-.5-.7-.5h-.6a1.2 1.2 0 0 0-.8.4 3.5 3.5 0 0 0-1.1 2.6 6.1 6.1 0 0 0 1.3 3.2 13.9 13.9 0 0 0 5.3 4.7c2 .8 2.8.9 3.8.7.6-.1 1.7-.7 2-1.4s.2-1.2.2-1.4-.3-.2-.6-.3Z" /></svg>
      </a>}
    </div>
  );
}

function LockIcon() {
  return <svg viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><rect x="4" y="11" width="16" height="10" rx="2" /><path d="M8 11V7a4 4 0 0 1 8 0v4" /></svg>;
}
