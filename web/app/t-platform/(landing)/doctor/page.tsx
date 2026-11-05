import { ClinicLanding, clinicLandingMetadata } from "@/components/landing/clinic/landing";

export const generateMetadata = clinicLandingMetadata;

/** elmanassa.shop/doctor: the same doctors & clinics page as /tabib (canonical /tabib). */
export default function Page() {
  return <ClinicLanding />;
}
