import Link from "next/link";
import { PoweredBy } from "@/components/brand";
import { AccountLink, CartLink } from "@/components/store-bits";
import { backendJson, currentHost } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";
import { resolveHost } from "@/lib/tenant";

type Profile = { profile: { storeName: string; shortDescription?: string; supportPhone?: string; shippingPolicy?: string; returnPolicy?: string } };

export default async function ShopLayout({ children }: { children: React.ReactNode }) {
  const { t, locale } = await getT();
  const info = await resolveHost(await currentHost());
  const name = info.kind === "TENANT" ? info.name : "";
  const p = await backendJson<Profile>("/shop/profile").catch(() => null);
  const logo = info.kind === "TENANT" && (info.branding as { logo_file_id?: string }).logo_file_id;
  return (
    <div className="flex min-h-screen flex-col">
      <header className="glass sticky top-0 z-30 border-b border-slate-200/70">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-3 px-4 py-3">
          <Link href="/" className="flex items-center gap-2.5 text-lg font-extrabold text-brand">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            {logo && <img src={`/api/bff/files/${logo}/content?variant=thumb`} alt="" className="h-9 w-9 rounded-xl object-cover shadow-sm" />}
            {p?.profile.storeName ?? name}
          </Link>
          <nav className="flex items-center gap-1 text-sm">
            <Link href="/products" className="rounded-lg px-3 py-1.5 font-medium transition hover:bg-brand-soft hover:text-brand">{t("nav.products")}</Link>
            <CartLink />
            <AccountLink />
            <a href={`/api/lang?l=${locale === "ar" ? "en" : "ar"}`} className="rounded-lg px-2 py-1 font-medium text-slate-500 transition hover:bg-slate-100 hover:text-slate-900">{locale === "ar" ? "EN" : "عربي"}</a>
          </nav>
        </div>
      </header>
      <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-8"><div className="animate-fade-up">{children}</div></main>
      <footer className="border-t border-slate-200/70 bg-white py-8 text-center text-sm text-slate-500">
        <p className="font-medium text-slate-600">© {new Date().getFullYear()} {p?.profile.storeName ?? name}{p?.profile.supportPhone && <> · <span dir="ltr">{p.profile.supportPhone}</span></>}</p>
        <div className="mt-2"><PoweredBy label={t("brand.powered")} name={t("brand.name")} /></div>
      </footer>
    </div>
  );
}
