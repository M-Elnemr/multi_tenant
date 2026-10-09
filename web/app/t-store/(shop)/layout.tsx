import "./shop.css";
import { ShopFooter } from "@/components/shop/footer";
import { ShopHeader } from "@/components/shop/header";
import { MobileBar } from "@/components/shop/mobile-bar";
import type { ShopBranch, ShopCategory, ShopProfile } from "@/components/shop/types";
import { WhatsAppFloat } from "@/components/shop/whatsapp-float";
import { backendJson, currentHost } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";
import { resolveHost } from "@/lib/tenant";

type ProfileRes = { profile: ShopProfile };

/** The storefront shell. It deliberately shows the shop's own logo, name and colour, so a visitor sees the shop, not the platform behind it. */
export default async function ShopLayout({ children }: { children: React.ReactNode }) {
  const { t } = await getT();
  const info = await resolveHost(await currentHost());
  const name = info.kind === "TENANT" ? info.name : "";
  const [res, categories, branches] = await Promise.all([
    backendJson<ProfileRes>("/shop/profile").catch(() => null),
    backendJson<ShopCategory[]>("/shop/categories").catch(() => [] as ShopCategory[]),
    backendJson<ShopBranch[]>("/shop/branches").catch(() => [] as ShopBranch[]),
  ]);
  const p: ShopProfile = res?.profile ?? { storeName: name };
  const storeName = p.storeName || name;
  const logoId = p.branding?.logoFileId ?? (info.kind === "TENANT" ? (info.branding as { logo_file_id?: string }).logo_file_id : undefined);
  const logoUrl = logoId ? `/api/bff/files/${logoId}/content?variant=thumb` : null;
  return (
    <div className="shop flex min-h-screen flex-col">
      <ShopHeader name={storeName} logoUrl={logoUrl} announcement={p.announcement} closedMessage={p.closedMessage} isOpen={p.isOpen !== false} categories={categories} />
      <main className="flex-1 pb-24 md:pb-0">{children}</main>
      <ShopFooter p={p} name={storeName} logoUrl={logoUrl} branches={branches} t={t} poweredLabel={t("brand.powered")} poweredName={t("brand.name")} />
      <WhatsAppFloat phone={p.whatsapp} storeName={storeName} label={t("shop.chatWithUs")} />
      <MobileBar />
    </div>
  );
}
