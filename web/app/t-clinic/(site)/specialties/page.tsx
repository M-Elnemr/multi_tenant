import { cnPage } from "@/components/clinic/templates/cn";
import { SpecialtiesPage } from "@/components/clinic/templates/shared/pages";

export default async function Specialties() {
  return <SpecialtiesPage c={await cnPage("doctors")} />;
}
