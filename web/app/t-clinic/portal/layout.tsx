import { ForcePasswordChange } from "@/components/force-password-change";
import { PortalNav } from "@/components/portal-nav";
import { currentHost } from "@/lib/backend";
import { fileUrl } from "@/lib/media";
import { resolveHost } from "@/lib/tenant";

export default async function PortalLayout({ children }: { children: React.ReactNode }) {
  const info = await resolveHost(await currentHost());
  const tenant = info.kind === "TENANT" ? info : null;
  const logoId = tenant ? (tenant.branding as { logo_file_id?: string }).logo_file_id ?? null : null;
  return (
    <div className="min-h-screen bg-gradient-to-b from-brand-soft/50 via-slate-50 to-slate-50">
      <PortalNav name={tenant?.name ?? ""} logo={logoId ? fileUrl(logoId, "thumb") : null} />
      <main className="mx-auto max-w-5xl px-4 pb-28 pt-6 md:pb-12 md:pt-8"><ForcePasswordChange />{children}</main>
    </div>
  );
}
