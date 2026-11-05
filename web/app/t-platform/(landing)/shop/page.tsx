import { ShopLanding, shopLandingMetadata } from "@/components/landing/shop/landing";

export const generateMetadata = shopLandingMetadata;

/** elmanassa.shop/shop: the shop-owner landing page; canonical is "/" (see shopLandingMetadata). */
export default function ShopPage() {
  return <ShopLanding />;
}
