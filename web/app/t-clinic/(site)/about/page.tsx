import { cnPage } from "@/components/clinic/templates/cn";
import { AboutPage } from "@/components/clinic/templates/shared/pages";

export default async function About() {
  return <AboutPage c={await cnPage("about")} />;
}
