import type { ComponentType } from "react";
import type { ShopProduct } from "@/components/shop/product-card";
import type { OfferBanner } from "@/components/shop/offer-strip";
import type { ShopBranch, ShopCategory, ShopProfile } from "@/components/shop/types";
import type { Locale, Translate } from "@/lib/i18n";
import type { ProductDetail } from "@/app/t-store/(shop)/(page)/products/[slug]/buy";
import type { ListingData } from "./load";

export type HomeData = { offerBanners?: OfferBanner[]; featured: ShopProduct[]; newest: ShopProduct[]; offers: ShopProduct[]; bestSellers: ShopProduct[]; banners: { id: string; imageFileId: string; title: string; subtitle: string; linkUrl: string }[] };
export type Slide = { id: string; imageUrl: string; title: string; subtitle: string; href?: string };

export type HeaderProps = { name: string; logoUrl: string | null; announcement?: string; closedMessage?: string; isOpen: boolean; categories: ShopCategory[]; audiences: string[] };
export type FooterProps = { p: ShopProfile; name: string; logoUrl: string | null; branches: ShopBranch[]; t: Translate; poweredLabel: string; poweredName: string };
export type HomeProps = { p?: ShopProfile; name: string; home: HomeData; slides: Slide[]; cats: ShopCategory[]; branches: ShopBranch[]; t: Translate; locale: Locale };
export type ListingProps = { d: ListingData; t: Translate; locale: Locale };
export type PdpProps = { product: ProductDetail; related: ShopProduct[]; together: { id: string; name: string; slug: string; currency: string; priceMinor: number }[]; profile?: ShopProfile; crumbs: { slug: string; name: string }[]; t: Translate; locale: Locale };
export type PolicyProps = { p: ShopProfile; slug: string; title: string; body: string; t: Translate };
export type ContactProps = { p: ShopProfile; t: Translate };

/** What one large template provides: its own component for every storefront page. */
export type LargeSet = {
  Header: ComponentType<HeaderProps>; Footer: ComponentType<FooterProps>; Home: ComponentType<HomeProps>; Listing: ComponentType<ListingProps>;
  Pdp: ComponentType<PdpProps>; Cart: ComponentType; Policy?: ComponentType<PolicyProps>; Contact?: ComponentType<ContactProps>;
};
