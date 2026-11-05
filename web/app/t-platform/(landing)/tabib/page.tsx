import { ClinicLanding, clinicLandingMetadata } from "@/components/landing/clinic/landing";

export const generateMetadata = clinicLandingMetadata;

/** elmanassa.shop/tabib: the doctors & clinics landing page. */
export default function TabibPage() {
  return <ClinicLanding />;
}
