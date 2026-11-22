import { cnPage } from "@/components/clinic/templates/cn";
import { SpecialtyPage } from "@/components/clinic/templates/shared/pages";

export default async function Specialty({ params }: { params: Promise<{ key: string }> }) {
  const { key } = await params;
  return <SpecialtyPage c={await cnPage("doctors")} specKey={decodeURIComponent(key)} />;
}
