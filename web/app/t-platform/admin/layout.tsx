import Link from "next/link";
import { getT } from "@/lib/i18n-server";
import { LogoutButton } from "@/components/logout-button";

export default async function AdminLayout({ children }: { children: React.ReactNode }) {
  const { t } = await getT();
  return (
    <div className="mx-auto max-w-6xl px-4 py-8">
      <div className="mb-6 flex items-center justify-between border-b border-slate-200 pb-4">
        <nav className="flex gap-5 text-sm font-medium">
          <Link href="/admin" className="text-slate-700 hover:text-brand">{t("admin.overview")}</Link>
          <Link href="/admin/tenants" className="text-slate-700 hover:text-brand">{t("admin.tenants")}</Link>
          <Link href="/admin/taxonomy" className="text-slate-700 hover:text-brand">{t("admin.taxonomy")}</Link>
          <Link href="/admin/audit" className="text-slate-700 hover:text-brand">{t("admin.audit")}</Link>
        </nav>
        <LogoutButton />
      </div>
      {children}
    </div>
  );
}
