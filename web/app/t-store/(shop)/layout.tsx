import { VisitTracker } from "@/components/visit-tracker";
import "./shop.css";
import "./templates.css";
import { templateFontVars } from "@/components/shop/templates/fonts";
import { isTemplate } from "@/components/shop/templates/ids";
import { TemplateBridge } from "@/components/shop/templates/template-bridge";
import { SwRegister } from "@/components/shop/sw-register";
import { ShopFooter } from "@/components/shop/footer";
import { ShopHeader } from "@/components/shop/header";
import { MobileBar } from "@/components/shop/mobile-bar";
import type { ShopBranch, ShopCategory, ShopProfile } from "@/components/shop/types";
import { LiveChat } from "@/components/shop/live-chat";
import { MarketingTags } from "@/components/shop/tracking";
import { WhatsAppFloat } from "@/components/shop/whatsapp-float";
import { backendJson, currentHost } from "@/lib/backend";
import { getT } from "@/lib/i18n-server";
import { resolveHost } from "@/lib/tenant";

type ProfileRes = { profile: ShopProfile };

/** The storefront shell. It deliberately shows the shop's own logo, name and colour, so a visitor sees the shop, not the platform behind it. */
export default async function ShopLayout({ children }: { children: React.ReactNode }) {
  const { t, locale } = await getT();
  const info = await resolveHost(await currentHost());
  const name = info.kind === "TENANT" ? info.name : "";
  const [res, categories, branches] = await Promise.all([
    backendJson<ProfileRes>("/shop/profile").catch(() => null),
    backendJson<ShopCategory[]>(`/shop/categories?lang=${locale}`).catch(() => [] as ShopCategory[]),
    backendJson<ShopBranch[]>("/shop/branches").catch(() => [] as ShopBranch[]),
  ]);
  const p: ShopProfile = res?.profile ?? { storeName: name };
  const storeName = p.storeName || name;
  const logoId = p.branding?.logoFileId ?? (info.kind === "TENANT" ? (info.branding as { logo_file_id?: string }).logo_file_id : undefined);
  const logoUrl = logoId ? `/api/bff/files/${logoId}/content?variant=thumb` : null;
  return (
    <div className={`shop flex min-h-dvh flex-col ${templateFontVars}`} data-template={isTemplate(p.template) ? p.template : "original"}>
      <TemplateBridge />
      <SwRegister />
      <ShopHeader name={storeName} logoUrl={logoUrl} announcement={p.announcement} closedMessage={p.closedMessage} isOpen={p.isOpen !== false} categories={categories} />
      <VisitTracker />
      <main className="flex-1 pb-chrome">{children}</main>
      <ShopFooter p={p} name={storeName} logoUrl={logoUrl} branches={branches} t={t} poweredLabel={t("brand.powered")} poweredName={t("brand.name")} />
      <WhatsAppFloat phone={p.whatsapp} storeName={storeName} label={t("shop.chatWithUs")} />
      <LiveChat />
      <MobileBar />
      <MarketingTags metaPixelId={p.metaPixelId} tiktokPixelId={p.tiktokPixelId} gaId={p.gaId} />
    </div>
  );
}
