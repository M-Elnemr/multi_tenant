import Link from "next/link";
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
      <header className="sticky top-0 z-30 border-b border-slate-200 bg-white/95 backdrop-blur">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-3 px-4 py-3">
          <Link href="/" className="flex items-center gap-2 text-lg font-bold text-brand">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            {logo && <img src={`/api/bff/files/${logo}/content`} alt="" className="h-8 w-8 rounded object-cover" />}
            {p?.profile.storeName ?? name}
          </Link>
          <nav className="flex items-center gap-1 text-sm">
            <Link href="/products" className="rounded-lg px-2 py-1.5 hover:bg-slate-100">{t("nav.products")}</Link>
            <CartLink />
            <AccountLink />
            <a href={`/api/lang?l=${locale === "ar" ? "en" : "ar"}`} className="px-2 text-slate-500">{locale === "ar" ? "EN" : "عربي"}</a>
          </nav>
        </div>
      </header>
      <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-8">{children}</main>
      <footer className="border-t border-slate-200 bg-white py-6 text-center text-sm text-slate-500">
        <p>© {new Date().getFullYear()} {p?.profile.storeName ?? name}{p?.profile.supportPhone && <> · <span dir="ltr">{p.profile.supportPhone}</span></>}</p>
      </footer>
    </div>
  );
}
