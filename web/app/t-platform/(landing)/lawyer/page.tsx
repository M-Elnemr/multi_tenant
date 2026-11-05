import { LawyerLanding, lawyerLandingMetadata } from "@/components/landing/lawyer/landing";

export const generateMetadata = lawyerLandingMetadata;

/** elmanassa.shop/lawyer: the same lawyers page as /mohami (canonical /mohami). */
export default function Page() {
  return <LawyerLanding />;
}
