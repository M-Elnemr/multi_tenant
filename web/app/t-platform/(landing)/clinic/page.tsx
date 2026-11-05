import { ClinicLanding, clinicLandingMetadata } from "@/components/landing/clinic/landing";

export const generateMetadata = clinicLandingMetadata;

/** elmanassa.shop/clinic: the same doctors & clinics page as /tabib (canonical /tabib). */
export default function Page() {
  return <ClinicLanding />;
}
