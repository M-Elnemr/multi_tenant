import { cnPage } from "@/components/clinic/templates/cn";
import { DoctorsPage } from "@/components/clinic/templates/shared/pages";

export default async function Doctors() {
  return <DoctorsPage c={await cnPage("doctors")} />;
}
