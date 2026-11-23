import { cnPage } from "@/components/clinic/templates/cn";
import { ServicesPage } from "@/components/clinic/templates/shared/pages";

export default async function Services() {
  return <ServicesPage c={await cnPage("services")} />;
}
