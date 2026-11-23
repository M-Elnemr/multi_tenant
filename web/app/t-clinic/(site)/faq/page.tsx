import { cnPage } from "@/components/clinic/templates/cn";
import { FaqPage } from "@/components/clinic/templates/shared/pages";

export default async function Faq() {
  return <FaqPage c={await cnPage("faq")} />;
}
