import { cnPage } from "@/components/clinic/templates/cn";
import { ContactPage } from "@/components/clinic/templates/shared/pages";

export default async function Contact() {
  return <ContactPage c={await cnPage("contact")} />;
}
