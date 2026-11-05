import { LawyerLanding, lawyerLandingMetadata } from "@/components/landing/lawyer/landing";

export const generateMetadata = lawyerLandingMetadata;

/** elmanassa.shop/mohami: the lawyers & law firms landing page. */
export default function MohamiPage() {
  return <LawyerLanding />;
}
