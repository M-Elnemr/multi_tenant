import { VisitTracker } from "@/components/visit-tracker";
import "./shop.css";
import "./templates.css";
import "./templates-v2.css";
import { templateFontVars } from "@/components/shop/templates/fonts";
import { PreviewBar } from "@/components/shop/templates/preview-bar";
import { PreviewLinks } from "@/components/shop/templates/preview-links";
import { shopLook } from "@/lib/shop-look";
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

/** The storefront shell. It deliberately shows the shop's own logo, name and colour, so a visitor sees the shop, not the platform behind it. */
export default async function ShopLayout({ children }: { children: React.ReactNode }) {
  const { t, locale } = await getT();
  const info = await resolveHost(await currentHost());
  const name = info.kind === "TENANT" ? info.name : "";
  const [look, categories, branches, audiences] = await Promise.all([
    shopLook(),
    backendJson<ShopCategory[]>(`/shop/categories?lang=${locale}`).catch(() => [] as ShopCategory[]),
    backendJson<ShopBranch[]>("/shop/branches").catch(() => [] as ShopBranch[]),
    // who the shop's products are for (only audiences that really have products): drives the Men / Women / Kids shortcuts
    backendJson<{ audiences?: { audience: string; n: number }[] }>("/shop/products/facets").then((f) => (f.audiences ?? []).filter((a) => a.n > 0).map((a) => a.audience)).catch(() => [] as string[]),
  ]);
  const p: ShopProfile = look.profile ?? { storeName: name };
  const storeName = p.storeName || name;
  const logoId = p.branding?.logoFileId ?? (info.kind === "TENANT" ? (info.branding as { logo_file_id?: string }).logo_file_id : undefined);
  const logoUrl = logoId ? `/api/bff/files/${logoId}/content?variant=thumb` : null;
  return (
    <div className={`shop flex min-h-dvh flex-col ${templateFontVars}`} data-template={look.template} data-header={look.look.header} data-dark={look.look.dark ? "" : undefined}>
      {look.previewing && !look.inFrame && <PreviewBar label={t("tpl.previewing", { name: t(`tpl.${look.template}`) })} exit={t("tpl.exitPreview")} />}
      {look.inFrame && <PreviewLinks tpl={look.template} />}
      <SwRegister />
      <ShopHeader name={storeName} logoUrl={logoUrl} announcement={p.announcement} closedMessage={p.closedMessage} isOpen={p.isOpen !== false} categories={categories} audiences={audiences} variant={look.look.header} />
      {!look.inFrame && <VisitTracker />}
      <main className="flex-1 pb-chrome">{children}</main>
      <ShopFooter p={p} name={storeName} logoUrl={logoUrl} branches={branches} t={t} poweredLabel={info.kind === "TENANT" && info.whiteLabel ? "" : t("brand.powered")} poweredName={t("brand.name")} />
      {!look.inFrame && <WhatsAppFloat phone={p.whatsapp} storeName={storeName} label={t("shop.chatWithUs")} />}
      {!look.inFrame && <LiveChat />}
      <MobileBar />
      {!look.inFrame && <MarketingTags metaPixelId={p.metaPixelId} tiktokPixelId={p.tiktokPixelId} gaId={p.gaId} />}
    </div>
  );
}
