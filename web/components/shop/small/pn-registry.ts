import type { ShopProduct } from "@/components/shop/product-card";
import type { ShopProfile } from "@/components/shop/types";
import type { Locale } from "@/lib/i18n";
import { BrandHome } from "./pn-brand/home";
import { FlagshipHome } from "./pn-flagship/home";
import { LaunchHome } from "./pn-launch/home";
import { ShowcaseHome } from "./pn-showcase/home";
import { TrioHome } from "./pn-trio/home";
import type { T } from "./pn-kit/primitives";

export const PN_IDS = ["pn-flagship", "pn-showcase", "pn-trio", "pn-launch", "pn-brand"] as const;
export type PnId = (typeof PN_IDS)[number];
export type PnHomeProps = { p: ShopProfile | undefined; products: ShopProduct[]; t: T; locale: Locale };

/** The pn-* small-shop designs. A design's home is an async server component; the other storefront pages are restyled through the design's tokens (pn-templates.css). */
export const PN_HOMES: Record<PnId, (props: PnHomeProps) => Promise<React.ReactElement>> = {
  "pn-flagship": FlagshipHome, "pn-showcase": ShowcaseHome, "pn-trio": TrioHome, "pn-launch": LaunchHome, "pn-brand": BrandHome,
};
export const pnHome = (id: string) => ((PN_IDS as readonly string[]).includes(id) ? PN_HOMES[id as PnId] : undefined);
