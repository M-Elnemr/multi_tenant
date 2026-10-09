import { currentHost } from "@/lib/backend";
import { resolveHost } from "@/lib/tenant";
import { LogoMark } from "./brand";

/** Centered card on a soft gradient, used by login/register style screens. Shows the tenant's own logo on tenant sites. */
export async function AuthCard({ title, subtitle, children, wide }: { title: string; subtitle?: string; children: React.ReactNode; wide?: boolean }) {
  const info = await resolveHost(await currentHost());
  const logo = info.kind === "TENANT" ? (info.branding as { logo_file_id?: string }).logo_file_id : undefined;
  return (
    <div className={`mx-auto px-4 py-10 ${wide ? "max-w-xl" : "max-w-md"}`}>
      <div className="mb-6 animate-fade-up text-center">
        {logo ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={`/api/bff/files/${logo}/content?variant=thumb`} alt="" className="mx-auto mb-3 h-16 w-16 rounded-2xl object-cover shadow-soft" />
        ) : (
          <LogoMark className="mx-auto mb-3 h-16 w-16 animate-float" />
        )}
        <h1 className="text-2xl font-bold tracking-tight text-ink">{title}</h1>
        {subtitle && <p className="mt-1.5 text-sm text-slate-500">{subtitle}</p>}
      </div>
      <div className="animate-fade-up rounded-3xl border border-slate-200/80 bg-white p-6 shadow-lift [animation-delay:80ms] sm:p-8">{children}</div>
    </div>
  );
}
