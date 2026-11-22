import type { ComponentType } from "react";
import type { VnTemplateId } from "@/components/shop/templates/service-looks";
import type { BookInit, ChromeProps, DetailProps, FooterProps, VnConfig, VnData } from "./vn-core/types";
import { WellnessHeader } from "./vn-wellness/header";
import { WellnessFooter } from "./vn-wellness/footer";
import { WellnessHome } from "./vn-wellness/home";
import { WellnessCatalog, WellnessContact, WellnessDetail } from "./vn-wellness/pages";
import { WellnessBook, WellnessManage } from "./vn-wellness/book";

/** What a vn-* design provides. The storefront routes render these instead of the shared service pages when the site's template is one of them. */
export type VnDesign = {
  Header: ComponentType<ChromeProps>; Footer: ComponentType<FooterProps>; Home: ComponentType<VnData>;
  Catalog: ComponentType<{ d: VnData; cat?: string }>; Detail: ComponentType<DetailProps>; Contact: ComponentType<VnData>;
  Book: ComponentType<{ cfg: VnConfig; init: BookInit }>; Manage: ComponentType;
};

/** Designs that are built so far; a vn-* id without an entry here falls back to the shared service pages. */
export const VN_DESIGNS: Partial<Record<VnTemplateId, VnDesign>> = {
  "vn-wellness": { Header: WellnessHeader, Footer: WellnessFooter, Home: WellnessHome, Catalog: WellnessCatalog, Detail: WellnessDetail, Contact: WellnessContact, Book: WellnessBook, Manage: WellnessManage },
};

export const vnDesign = (id: string): VnDesign | undefined => (VN_DESIGNS as Record<string, VnDesign | undefined>)[id];
