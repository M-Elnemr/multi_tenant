import type { ComponentType } from "react";
import type { VnTemplateId } from "@/components/shop/templates/service-looks";
import type { BookInit, ChromeProps, DetailProps, FooterProps, VnConfig, VnData } from "./vn-core/types";
import { BookUI, ManageUI, type BookLayout } from "./vn-core/book-ui";
import { VnContact } from "./vn-core/shared";
import { WellnessHeader } from "./vn-wellness/header";
import { WellnessFooter } from "./vn-wellness/footer";
import { WellnessHome } from "./vn-wellness/home";
import { WellnessCatalog, WellnessContact, WellnessDetail } from "./vn-wellness/pages";
import { WellnessBook, WellnessManage } from "./vn-wellness/book";
import { HomeproHeader } from "./vn-homepro/header";
import { HomeproFooter } from "./vn-homepro/footer";
import { HomeproCatalog, HomeproDetail, HomeproHome } from "./vn-homepro/pages";

/** What a vn-* design provides. The storefront routes render these instead of the shared service pages when the site's template is one of them. */
export type VnDesign = {
  Header: ComponentType<ChromeProps>; Footer: ComponentType<FooterProps>; Home: ComponentType<VnData>;
  Catalog: ComponentType<{ d: VnData; cat?: string }>; Detail: ComponentType<DetailProps>; Contact: ComponentType<VnData>;
  Book: ComponentType<{ cfg: VnConfig; init: BookInit }>; Manage: ComponentType;
};

const bookWith = (layout: BookLayout) => function Book(p: { cfg: VnConfig; init: BookInit }) { return <BookUI {...p} layout={layout} />; };

/** Designs that are built so far; a vn-* id without an entry here falls back to the shared service pages. */
export const VN_DESIGNS: Partial<Record<VnTemplateId, VnDesign>> = {
  "vn-wellness": { Header: WellnessHeader, Footer: WellnessFooter, Home: WellnessHome, Catalog: WellnessCatalog, Detail: WellnessDetail, Contact: WellnessContact, Book: WellnessBook, Manage: WellnessManage },
  "vn-homepro": { Header: HomeproHeader, Footer: HomeproFooter, Home: HomeproHome, Catalog: HomeproCatalog, Detail: HomeproDetail, Contact: VnContact, Book: bookWith("request"), Manage: ManageUI },
};

export const vnDesign = (id: string): VnDesign | undefined => (VN_DESIGNS as Record<string, VnDesign | undefined>)[id];
