import { cnPage } from "@/components/clinic/templates/cn";
import { DoctorPage } from "@/components/clinic/templates/shared/pages";

export default async function Doctor({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <DoctorPage c={await cnPage("doctors")} id={id} />;
}
