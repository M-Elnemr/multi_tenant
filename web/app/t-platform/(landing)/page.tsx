import { ShopLanding, shopLandingMetadata } from "@/components/landing/shop/landing";

export const generateMetadata = shopLandingMetadata;

/** elmanassa.shop: the shop-owner landing page (the same page as /shop). */
export default function Home() {
  return <ShopLanding />;
}
