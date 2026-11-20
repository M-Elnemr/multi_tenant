import { ServiceLanding, serviceLandingMetadata } from "@/components/landing/service/landing";

export const generateMetadata = serviceLandingMetadata;

/** elmanassa.shop/services: the service businesses (salons, repair, cleaning...) landing page. */
export default function ServicesPage() {
  return <ServiceLanding />;
}
